package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.kit.KitWindowOpenPolicy;
import com.zenith.plugin.stashmanager.kit.KitSourceReturnPolicy;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/** Delivers one verified kit box to its reserved export chest. */
public final class KitExporter {
    public enum State { IDLE, WALKING, OPENING, DEPOSITING, VERIFYING, CLOSING, DONE, FAILED }

    private static final int OPEN_CLICK_RETRY_TICKS = 80;
    private static final int RETURN_STABLE_TICKS = 3;
    private static final int RETURN_SETTLE_TIMEOUT_TICKS = 60;

    private final SneakReleaseGate openGate = new SneakReleaseGate();
    private final KitReturnTargetStability<StackIdentity> returnTargetStability =
            new KitReturnTargetStability<>();
    private State state = State.IDLE;
    private int[] target;
    private Map<String, Integer> expectedContents = Map.of();
    private String expectedItemId;
    private ItemStack expectedLooseStack;
    private boolean requireImportCapacity;
    private boolean verifyExistingReturn;
    private boolean verifyFailedReturn;
    private int expectedReturnSlots;
    private List<StackIdentity> returnTargetBefore = List.of();
    private int openContainerId = -1;
    private volatile int contentContainerId = -1;
    private int closeClearTicks;
    private int phaseTicks;
    private int chestMatchingBefore;
    private int playerMatchingBefore;
    private boolean verifyingPersistedDeposit;
    private int ownedRawSlot = -1;
    private RequestFuture depositFuture;
    private InventoryTransferEvidence depositEvidence;
    private PathingRequestFuture openFuture;
    private int lastOpenClickTick = -1;
    private int depositHandoffWaitTicks;
    private String failureReason;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private boolean navigationGuard;
    private BiConsumer<String, Map<String, Object>> events;

    public void setEventCallback(BiConsumer<String, Map<String, Object>> events) {
        this.events = events;
    }

    public State state() { return state; }
    public boolean isActive() { return state != State.IDLE && state != State.DONE && state != State.FAILED; }
    public String failureReason() { return failureReason; }

    public void onContainerData(int containerId) {
        if ((state == State.OPENING || state == State.DEPOSITING
                || state == State.VERIFYING || state == State.CLOSING) && containerId > 0) {
            contentContainerId = containerId;
        }
    }

    public void abortForRecovery(String reason) {
        if (isActive()) fail("interrupted:" + reason);
    }

    public boolean start(int[] exportPosition, Map<String, Integer> contents) {
        return start(exportPosition, contents, -1);
    }

    public boolean start(int[] exportPosition, Map<String, Integer> contents, int ownedRawSlot) {
        return start(exportPosition, contents, ownedRawSlot, null);
    }

    /** Also supports returning a verified borrowed box to its source chest. */
    public boolean start(int[] exportPosition, Map<String, Integer> contents,
                         int ownedRawSlot, String expectedItemId) {
        return start(exportPosition, contents, ownedRawSlot, expectedItemId, false, 0);
    }

    /** A rerouted borrowed source may enter an import only after its live window
     * proves free capacity and a stable pre-transfer snapshot. */
    public boolean start(int[] exportPosition, Map<String, Integer> contents,
                         int ownedRawSlot, String expectedItemId, boolean requireImportCapacity,
                         int expectedReturnSlots) {
        if (isActive() || exportPosition == null || exportPosition.length != 3
                || contents == null || (contents.isEmpty() && ownedRawSlot < 0)
                || !Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || (requireImportCapacity && expectedReturnSlots != 27
                    && expectedReturnSlots != 54)) return false;
        this.expectedContents = Map.copyOf(contents);
        this.expectedItemId = expectedItemId;
        this.expectedLooseStack = null;
        this.requireImportCapacity = requireImportCapacity;
        this.verifyExistingReturn = false;
        this.verifyFailedReturn = false;
        this.expectedReturnSlots = expectedReturnSlots;
        this.returnTargetBefore = List.of();
        this.returnTargetStability.reset();
        this.playerMatchingBefore = countMatchingPlayerBoxes();
        if (playerMatchingBefore < 1) return false;
        if (ownedRawSlot >= 0) {
            Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
            if (player == null || ownedRawSlot < 9 || ownedRawSlot > 44
                    || !matchingBox(player.getItemStack(ownedRawSlot))) return false;
        }
        this.ownedRawSlot = ownedRawSlot;
        this.target = exportPosition.clone();
        this.failureReason = null;
        this.openContainerId = -1;
        this.depositFuture = null;
        this.depositEvidence = null;
        this.openFuture = null;
        this.lastOpenClickTick = -1;
        this.depositHandoffWaitTicks = 0;
        this.verifyingPersistedDeposit = false;
        this.phaseTicks = 0;
        this.openGate.reset();
        guardNavigation();
        BARITONE.stop();
        state = State.WALKING;
        emit("kit_export_started", Map.of("target", targetString(),
                "borrowed_source_return", expectedItemId != null));
        return true;
    }

    /** Deposit one component-exact loose stack into a registered, live-verified import. */
    public boolean startLooseImport(int[] importPosition, ItemStack stack,
                                    int ownedRawSlot, int expectedSlots) {
        if (isActive() || importPosition == null || importPosition.length != 3
                || stack == null || stack.getAmount() < 1 || stack.getAmount() > 64
                || ownedRawSlot < 9 || ownedRawSlot > 44
                || (expectedSlots != 27 && expectedSlots != 54)
                || !Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0)
            return false;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null || !sameVariant(player.getItemStack(ownedRawSlot), stack)
                || player.getItemStack(ownedRawSlot).getAmount() != stack.getAmount())
            return false;
        this.expectedContents = Map.of();
        this.expectedItemId = null;
        this.expectedLooseStack = stack;
        this.requireImportCapacity = true;
        this.verifyExistingReturn = false;
        this.verifyFailedReturn = false;
        this.expectedReturnSlots = expectedSlots;
        this.returnTargetBefore = List.of();
        this.returnTargetStability.reset();
        this.playerMatchingBefore = countMatchingPlayerBoxes();
        if (playerMatchingBefore != stack.getAmount()) return false;
        this.ownedRawSlot = ownedRawSlot;
        this.target = importPosition.clone();
        this.failureReason = null;
        this.openContainerId = -1;
        this.depositFuture = null;
        this.depositEvidence = null;
        this.openFuture = null;
        this.lastOpenClickTick = -1;
        this.depositHandoffWaitTicks = 0;
        this.verifyingPersistedDeposit = false;
        this.phaseTicks = 0;
        this.openGate.reset();
        guardNavigation();
        BARITONE.stop();
        state = State.WALKING;
        emit("kit_loose_import_started", Map.of("target", targetString(),
                "amount", stack.getAmount()));
        return true;
    }

    /** Reopen a previously used return target twice and verify one exact box.
     * This mode never reaches DEPOSITING or submits an inventory click. */
    public boolean startVerifyExistingReturn(int[] returnPosition, String itemId,
                                             Map<String, Integer> contents, int slots) {
        if (isActive() || returnPosition == null || returnPosition.length != 3
                || itemId == null || contents == null || contents.isEmpty()
                || (slots != 27 && slots != 54)
                || !Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || INVENTORY.hasActiveRequest()) return false;
        this.expectedContents = Map.copyOf(contents);
        this.expectedItemId = itemId;
        this.expectedLooseStack = null;
        this.requireImportCapacity = true;
        this.verifyExistingReturn = true;
        this.verifyFailedReturn = false;
        this.expectedReturnSlots = slots;
        this.returnTargetBefore = List.of();
        this.returnTargetStability.reset();
        if (countMatchingPlayerBoxes() != 0) {
            this.verifyExistingReturn = false;
            return false;
        }
        this.playerMatchingBefore = 0;
        this.ownedRawSlot = -1;
        this.target = returnPosition.clone();
        this.failureReason = null;
        this.openContainerId = -1;
        this.depositFuture = null;
        this.depositEvidence = null;
        this.openFuture = null;
        this.lastOpenClickTick = -1;
        this.depositHandoffWaitTicks = 0;
        this.verifyingPersistedDeposit = false;
        this.phaseTicks = 0;
        this.openGate.reset();
        guardNavigation();
        BARITONE.stop();
        state = State.WALKING;
        emit("kit_return_audit_started", Map.of("target", targetString(),
                "item_id", itemId));
        return true;
    }

    /** Check a failed source return without submitting an inventory click. The
     * original chest must be full, lack this box, and be observed twice. */
    public boolean startVerifyFailedReturn(int[] sourcePosition, String itemId,
                                           Map<String, Integer> contents, int slots) {
        if (isActive() || sourcePosition == null || sourcePosition.length != 3
                || itemId == null || contents == null || contents.isEmpty()
                || (slots != 27 && slots != 54)
                || !Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || INVENTORY.hasActiveRequest()) return false;
        this.expectedContents = Map.copyOf(contents);
        this.expectedItemId = itemId;
        this.expectedLooseStack = null;
        this.requireImportCapacity = true;
        this.verifyExistingReturn = false;
        this.verifyFailedReturn = true;
        this.expectedReturnSlots = slots;
        this.returnTargetBefore = List.of();
        this.returnTargetStability.reset();
        if (countMatchingPlayerBoxes() != 1) {
            this.verifyFailedReturn = false;
            return false;
        }
        this.playerMatchingBefore = 1;
        this.ownedRawSlot = -1;
        this.target = sourcePosition.clone();
        this.failureReason = null;
        this.openContainerId = -1;
        this.depositFuture = null;
        this.depositEvidence = null;
        this.openFuture = null;
        this.lastOpenClickTick = -1;
        this.depositHandoffWaitTicks = 0;
        this.verifyingPersistedDeposit = false;
        this.phaseTicks = 0;
        this.openGate.reset();
        guardNavigation();
        BARITONE.stop();
        state = State.WALKING;
        emit("kit_failed_return_audit_started", Map.of("target", targetString(),
                "item_id", itemId));
        return true;
    }

    public void tick() {
        if (!isActive()) return;
        phaseTicks++;
        switch (state) {
            case WALKING -> tickWalking();
            case OPENING -> tickOpening();
            case DEPOSITING -> tickDepositing();
            case VERIFYING -> tickVerifying();
            case CLOSING -> tickClosing();
            default -> { }
        }
    }

    private void tickWalking() {
        if (atAccessPosition()) {
            BARITONE.stop();
            advance(State.OPENING);
            openGate.reset();
            return;
        }
        if (phaseTicks > 2400) { fail("export_walk_timeout"); return; }
        if (phaseTicks == 1 || (phaseTicks % 40 == 0
                && !BARITONE.getCustomGoalProcess().isActive())) {
            BARITONE.pathTo(new GoalGetToBlock(new BlockPos(target[0], target[1], target[2])));
        }
    }

    private void tickOpening() {
        if ((requireImportCapacity || verifyFailedReturn) && !isLiveReturnContainerBlock()) {
            fail("export_target_block_changed");
            return;
        }
        if (!openGate.tick(BOT.isSneaking())) {
            INPUTS.submit(InputRequest.builder().owner(this)
                    .input(Input.builder().sneaking(false).build())
                    .priority(SneakReleaseGate.INPUT_PRIORITY).build());
            phaseTicks = 0;
            return;
        }
        Container open = liveOpen();
        int liveId = open == null ? -1 : open.getContainerId();
        int slots = open == null ? 0 : containerSlots(open);
        if (liveId > 0 && openFuture != null) {
            BARITONE.stop();
            openFuture = null;
        }
        var decision = KitWindowOpenPolicy.evaluate(liveId, contentContainerId, slots,
                phaseTicks, 180, 60, 27, 54);
        if (decision == KitWindowOpenPolicy.Result.READY) {
            if (requireImportCapacity && slots != expectedReturnSlots) {
                fail("export_target_footprint_changed");
                return;
            }
            if (verifyFailedReturn) {
                if (!returnTargetSettled(open, slots)) return;
                int chestMatches = matchingInChest(open, slots);
                int playerMatches = countMatchingPlayerBoxes();
                int occupied = occupiedChestSlots(open, slots);
                if (occupied != slots || chestMatches != 0 || playerMatches != 1) {
                    emit("kit_failed_return_audit_mismatch", Map.of("target", targetString(),
                            "occupied", occupied, "slots", slots,
                            "chest_matches", chestMatches, "player_matches", playerMatches,
                            "second_open", verifyingPersistedDeposit));
                    fail("failed_return_source_not_full_or_identity_mismatch");
                    return;
                }
                emit("kit_failed_return_audit_window_verified", Map.of("target", targetString(),
                        "second_open", verifyingPersistedDeposit));
                advance(State.CLOSING);
                return;
            }
            if (verifyExistingReturn) {
                if (!returnTargetSettled(open, slots)) return;
                int chestMatches = matchingInChest(open, slots);
                int playerMatches = countMatchingPlayerBoxes();
                if (chestMatches != 1 || playerMatches != 0) {
                    emit("kit_return_audit_mismatch", Map.of("target", targetString(),
                            "chest_matches", chestMatches,
                            "player_matches", playerMatches,
                            "second_open", verifyingPersistedDeposit));
                    fail("existing_return_identity_mismatch");
                    return;
                }
                emit("kit_return_audit_window_verified", Map.of("target", targetString(),
                        "second_open", verifyingPersistedDeposit));
                advance(State.CLOSING);
                return;
            }
            if (verifyingPersistedDeposit) {
                // The first content packet after reopening can describe a chest that is
                // still being populated. Never submit another deposit click here, but
                // wait for a stable live window before judging the persisted transfer.
                if (!postcloseTargetSettled(open, slots)) return;
                if (!depositCountVerified(open, slots)
                        || depositEvidence == null || depositEvidence.moved(open) <= 0) {
                    emit("kit_export_postclose_mismatch", Map.of(
                            "target", targetString(),
                            "matching_before", chestMatchingBefore,
                            "matching_after", matchingInChest(open, slots),
                            "player_moved", depositEvidence == null ? -1
                                    : depositEvidence.moved(open)));
                    fail("export_postclose_verification_mismatch");
                    return;
                }
                emit("kit_export_postclose_verified", Map.of("container_id", liveId));
                advance(State.CLOSING);
                return;
            }
            if (requireImportCapacity && !KitSourceReturnPolicy.hasLiveCapacity(
                    occupiedChestSlots(open, slots), slots)) {
                fail("export_chest_full");
                return;
            }
            if (occupiedChestSlots(open, slots) >= slots) { fail("export_chest_full"); return; }
            if (requireImportCapacity && !returnTargetSettled(open, slots)) return;
            chestMatchingBefore = matchingInChest(open, slots);
            advance(State.DEPOSITING);
            return;
        }
        if (decision == KitWindowOpenPolicy.Result.WRONG_TYPE) {
            emit("kit_export_window_mismatch", Map.of("target", targetString(),
                    "block", World.getBlock(target[0], target[1], target[2]).name(),
                    "container_slots", slots));
            fail("wrong_export_container_opened"); return;
        }
        if (decision == KitWindowOpenPolicy.Result.TIMED_OUT) {
            fail(liveId > 0 ? "export_contents_timeout" : "export_open_timeout"); return;
        }
        boolean clickPending = openFuture != null && !openFuture.isDone();
        if (KitWindowOpenPolicy.shouldIssueClick(decision, phaseTicks,
                lastOpenClickTick, OPEN_CLICK_RETRY_TICKS, clickPending)) {
            BARITONE.stop();
            openFuture = BARITONE.rightClickBlock(target[0], target[1], target[2]);
            lastOpenClickTick = phaseTicks;
        }
    }

    private void tickDepositing() {
        if (!rebindBeforeDeposit()) return;
        Container open = liveOpen();
        if (open == null) {
            fail(depositEvidence == null ? "export_window_lost_before_transfer"
                    : "export_window_lost_after_transfer");
            return;
        }
        int slots = containerSlots(open);
        if (depositEvidence != null) {
            if (depositCountVerified(open, slots)
                    && depositEvidence.moved(open) > 0) {
                advance(State.VERIFYING);
                return;
            }
            if (phaseTicks > 180 || (depositFuture.isCompleted() && !depositFuture.getNow())) {
                fail("export_deposit_unconfirmed");
            }
            return;
        }
        if (requireImportCapacity) {
            if (!isLiveReturnContainerBlock()) { fail("export_target_block_changed"); return; }
            if (slots != expectedReturnSlots) { fail("export_target_footprint_changed"); return; }
            if (!KitSourceReturnPolicy.hasLiveCapacity(occupiedChestSlots(open, slots), slots)) {
                fail("export_chest_full"); return;
            }
            if (!returnTargetSettled(open, slots)) return;
        }
        int sourceSlot = -1;
        for (int raw = 9; raw <= 44; raw++) {
            if (ownedRawSlot >= 0 && raw != ownedRawSlot) continue;
            int window = rawToWindow(raw, slots);
            ItemStack stack = open.getItemStack(window);
            if (matchingBox(stack)) { sourceSlot = window; break; }
        }
        if (sourceSlot < 0) { fail("verified_kit_box_missing_from_inventory"); return; }
        try {
            depositEvidence = InventoryTransferEvidence.capture(open, slots, sourceSlot);
            depositFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                    .priority(6000).actions(new ShiftClick(openContainerId, sourceSlot,
                            ShiftClickItemAction.LEFT_CLICK)).build());
            phaseTicks = 0;
        } catch (RuntimeException e) {
            fail("export_deposit_rejected");
        }
    }

    /** A delayed second open is safe to adopt only before any inventory click starts. */
    private boolean rebindBeforeDeposit() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int currentId = cache.getOpenContainerId();
        if (openContainerId <= 0 || currentId <= 0 || currentId == openContainerId) return true;
        BARITONE.stop();
        if (depositEvidence != null) { fail("export_window_lost_after_transfer"); return false; }
        Container replacement = cache.getOpenContainer();
        int slots = replacement == null ? 0 : containerSlots(replacement);
        if (replacement == null || replacement.getContainerId() != currentId
                || contentContainerId != currentId || slots == 0) {
            if (++depositHandoffWaitTicks > 60) fail("export_reopen_contents_timeout");
            return false;
        }
        if ((slots != 27 && slots != 54)
                || matchingInChest(replacement, slots) != chestMatchingBefore
                || (requireImportCapacity && (slots != expectedReturnSlots
                    || !KitSourceReturnPolicy.hasLiveCapacity(
                        occupiedChestSlots(replacement, slots), slots)
                    || !returnTargetBefore.equals(chestSnapshot(replacement, slots))))
                || !atAccessPosition()) {
            fail("export_reopened_window_mismatch");
            return false;
        }
        openContainerId = currentId;
        depositHandoffWaitTicks = 0;
        emit("kit_export_predeposit_handoff_verified", Map.of("container_id", currentId));
        return true;
    }

    private void tickVerifying() {
        Container open = liveOpen();
        if (open == null) { fail("export_verification_window_lost"); return; }
        if (!depositCountVerified(open, containerSlots(open))
                || depositEvidence == null || depositEvidence.moved(open) <= 0) {
            fail("export_verification_mismatch");
            return;
        }
        advance(State.CLOSING);
    }

    private void tickClosing() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int currentId = cache.getOpenContainerId();
        if (currentId <= 0) {
            if (++closeClearTicks < 3) return;
            if (!verifyingPersistedDeposit) {
                verifyingPersistedDeposit = true;
                returnTargetStability.reset();
                openContainerId = -1;
                openGate.reset();
                advance(State.OPENING);
                return;
            }
            restoreNavigation();
            advance(State.DONE);
            emit("kit_export_verified", Map.of());
            return;
        }
        closeClearTicks = 0;
        if (currentId != openContainerId) {
            Container reopened = cache.getOpenContainer();
            int slots = reopened == null ? 0 : containerSlots(reopened);
            if (reopened == null || reopened.getContainerId() != currentId
                    || contentContainerId != currentId || slots == 0) {
                if (phaseTicks > 120) fail("export_reopen_contents_timeout");
                return;
            }
            if ((slots != 27 && slots != 54)
                    || (verifyFailedReturn ? slots != expectedReturnSlots
                        || occupiedChestSlots(reopened, slots) != slots
                        || matchingInChest(reopened, slots) != 0
                        || countMatchingPlayerBoxes() != 1
                        : verifyExistingReturn ? slots != expectedReturnSlots
                        || matchingInChest(reopened, slots) != 1
                        || countMatchingPlayerBoxes() != 0
                        : !depositCountVerified(reopened, slots))) {
                fail("export_reopened_window_mismatch");
                return;
            }
            openContainerId = currentId;
            emit("kit_export_reopened_chest_verified", Map.of("container_id", currentId));
        }
        if (phaseTicks > 120) { fail("export_close_timeout"); return; }
        if (phaseTicks == 1 || phaseTicks % 20 == 0) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(5000)
                    .actions(new CloseContainer(openContainerId)).build());
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        BARITONE.stop();
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId > 0 && openId == openContainerId && Proxy.getInstance().isConnected()
                && !Proxy.getInstance().hasActivePlayer()) {
            try {
                INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(6000)
                        .actions(new CloseContainer(openId)).build());
            } catch (RuntimeException ignored) {
                // Leave the transaction blocked rather than retrying the deposit.
            }
        }
        restoreNavigation();
        state = State.FAILED;
        emit("kit_export_failed", Map.of("reason", reason));
    }

    private void advance(State next) {
        state = next;
        phaseTicks = 0;
        if (next == State.OPENING) {
            BARITONE.stop();
            contentContainerId = -1;
            openFuture = null;
            lastOpenClickTick = -1;
        }
        if (next == State.CLOSING) {
            BARITONE.stop();
            closeClearTicks = 0;
        }
        emit("kit_export_phase", Map.of("phase", next.name()));
    }

    /** Re-check the same live window over several ticks; never submit a click while
     * its chest slots are still arriving or changing. Capacity is checked separately
     * on every observed tick and again immediately before the transfer. */
    private boolean returnTargetSettled(Container open, int slots) {
        var observation = returnTargetStability.observe(chestSnapshot(open, slots),
                RETURN_STABLE_TICKS, RETURN_SETTLE_TIMEOUT_TICKS);
        returnTargetBefore = returnTargetStability.snapshot();
        if (observation.changedSlots() > 0 && observation.observedTicks() > 1) {
            emit("kit_export_target_snapshot_changed", Map.of(
                    "phase", state.name(), "changed_slots", observation.changedSlots(),
                    "observed_ticks", observation.observedTicks()));
        }
        if (observation.result() == KitReturnTargetStability.Result.TIMED_OUT) {
            emit("kit_export_target_unstable", Map.of(
                    "phase", state.name(), "observed_ticks", observation.observedTicks()));
            fail("export_target_changed_before_transfer");
            return false;
        }
        return observation.result() == KitReturnTargetStability.Result.STABLE;
    }

    /** A failed post-close check is terminal: settling never repeats the click. */
    private boolean postcloseTargetSettled(Container open, int slots) {
        var observation = returnTargetStability.observe(chestSnapshot(open, slots),
                RETURN_STABLE_TICKS, RETURN_SETTLE_TIMEOUT_TICKS);
        if (observation.result() == KitReturnTargetStability.Result.TIMED_OUT) {
            fail("export_postclose_target_unstable");
            return false;
        }
        return observation.result() == KitReturnTargetStability.Result.STABLE;
    }

    private boolean atAccessPosition() {
        var player = CACHE.getPlayerCache();
        return ContainerApproach.isAtAccessPosition(player.getX(), player.getY(), player.getZ(),
                target[0], target[1], target[2]);
    }

    private Container liveOpen() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int id = cache.getOpenContainerId();
        if (id <= 0) return null;
        Container open = cache.getOpenContainer();
        if (open == null || open.getContainerId() != id) return null;
        if (openContainerId > 0 && openContainerId != id) return null;
        openContainerId = id;
        return open;
    }

    private boolean matchingBox(ItemStack stack) {
        if (expectedLooseStack != null) return sameVariant(stack, expectedLooseStack);
        return stack != null && stack.getAmount() > 0
                && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack)).contains("shulker_box")
                && (expectedItemId == null
                    || expectedItemId.equals(ItemIdentifier.getItemId(stack)))
                && ItemIdentifier.readShulkerContents(stack).equals(expectedContents);
    }

    private int countMatchingPlayerBoxes() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;
        int count = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (matchingBox(stack)) count += expectedLooseStack == null ? 1 : stack.getAmount();
        }
        return count;
    }

    private int matchingInChest(Container open, int slots) {
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (matchingBox(stack)) count += expectedLooseStack == null ? 1 : stack.getAmount();
        }
        return count;
    }

    private boolean depositCountVerified(Container open, int slots) {
        int delta = matchingInChest(open, slots) - chestMatchingBefore;
        return expectedLooseStack == null ? delta > 0 : delta == expectedLooseStack.getAmount();
    }

    private static boolean sameVariant(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getAmount() > 0 && b.getAmount() > 0
                && a.getId() == b.getId()
                && java.util.Objects.equals(a.getDataComponents(), b.getDataComponents());
    }

    private static int occupiedChestSlots(Container open, int slots) {
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) count++;
        }
        return count;
    }

    private static List<StackIdentity> chestSnapshot(Container open, int slots) {
        List<StackIdentity> snapshot = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            snapshot.add(stack == null || stack.getAmount() <= 0
                    ? new StackIdentity(0, 0, null)
                    : new StackIdentity(stack.getId(), stack.getAmount(),
                        stack.getDataComponents() == null ? null
                                : stack.getDataComponents().clone()));
        }
        return List.copyOf(snapshot);
    }

    private record StackIdentity(int id, int amount, DataComponents components) { }

    private boolean isLiveReturnContainerBlock() {
        String name = World.getBlock(target[0], target[1], target[2]).name();
        return name.equals("chest") || name.equals("minecraft:chest")
                || name.equals("trapped_chest") || name.equals("minecraft:trapped_chest")
                || name.equals("barrel") || name.equals("minecraft:barrel");
    }

    private static int containerSlots(Container open) { return Math.max(0, open.getSize() - 36); }
    private String targetString() {
        return target[0] + "," + target[1] + "," + target[2];
    }
    private static int rawToWindow(int raw, int slots) {
        return raw < 36 ? slots + raw - 9 : slots + 27 + raw - 36;
    }

    private void guardNavigation() {
        if (navigationGuard) return;
        savedAllowBreak = CONFIG.client.extra.pathfinder.allowBreak;
        savedAllowPlace = CONFIG.client.extra.pathfinder.allowPlace;
        CONFIG.client.extra.pathfinder.allowBreak = false;
        CONFIG.client.extra.pathfinder.allowPlace = false;
        navigationGuard = true;
    }

    private void restoreNavigation() {
        if (!navigationGuard) return;
        CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
        CONFIG.client.extra.pathfinder.allowPlace = savedAllowPlace;
        navigationGuard = false;
    }

    private void emit(String name, Map<String, Object> fields) {
        if (events != null) events.accept(name, fields);
    }
}
