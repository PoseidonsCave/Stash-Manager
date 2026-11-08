package com.zenith.plugin.stashmanager.kit;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.mc.food.FoodRegistry;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.organizer.ShulkerPickupSweep;
import com.zenith.plugin.stashmanager.organizer.KitFulfillmentCoordinator;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiConsumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/** Recover only boxes placed at journaled worksites; never replay an uncertain cargo transfer. */
public final class KitWorksiteRecovery {
    public enum State { IDLE, WAITING_FOR_BOT, WALKING, OPENING, CLOSING,
        BREAKING, PICKUP, DONE, BLOCKED }

    private static final int WALK_TIMEOUT_TICKS = 2400;
    private static final int BREAK_TIMEOUT_TICKS = 300;
    private static final int PICKUP_TIMEOUT_TICKS = 900;
    private static final int OPEN_TIMEOUT_TICKS = 180;
    private static final int CLOSE_TIMEOUT_TICKS = 120;

    private record Target(String kind, int[] position) { }

    private final KitFulfillmentJournalStore journal;
    private final SneakReleaseGate openGate = new SneakReleaseGate();
    private final Deque<Target> targets = new ArrayDeque<>();
    private KitFulfillmentJournalStore.Snapshot snapshot;
    private Target current;
    private State state = State.IDLE;
    private int phaseTicks;
    private int closeClearTicks;
    private int inventoryBoxesBeforeBreak;
    private Set<Integer> shulkerSlotsBeforeBreak = Set.of();
    private Map<String, Integer> observedContents = Map.of();
    private String observedItemId;
    private boolean misplacedSourceAtKitWorksite;
    private boolean worldBoxSeen;
    private int sweepAttempt;
    private int lastSweepTick = -1;
    private int lastPathTick = -1;
    private int openContainerId = -1;
    private volatile int contentContainerId = -1;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private boolean navigationGuard;
    private String blocker;
    private BiConsumer<String, Map<String, Object>> events;

    public KitWorksiteRecovery(KitFulfillmentJournalStore journal) {
        this.journal = journal;
    }

    public void setEventCallback(BiConsumer<String, Map<String, Object>> events) {
        this.events = events;
    }

    public State state() { return state; }
    public void onContainerData(int containerId) {
        if ((state == State.OPENING || state == State.CLOSING) && containerId > 0) {
            contentContainerId = containerId;
        }
    }
    public String blocker() { return blocker; }
    public boolean canAcknowledgeAbsentKitBox() {
        return state == State.BLOCKED && blocker != null
                && (blocker.equals("possible_dropped_box_collected_needs_identity_review:kit_box")
                    || blocker.equals("journaled_box_absent_or_already_held:kit_box"));
    }

    public boolean canAcknowledgeAbsentBorrowedSource() {
        return state == State.BLOCKED
                && "journaled_box_absent_or_already_held:borrowed_source".equals(blocker)
                && current != null && "borrowed_source".equals(current.kind());
    }

    /** Called only after an operator-attested loss is durably written to the checkpoint. */
    public void acknowledgeAbsentKitBox() {
        if (!canAcknowledgeAbsentKitBox()) return;
        targets.clear();
        current = null;
        snapshot = null;
        blocker = null;
        state = State.DONE;
    }

    /** Called only after an operator-attested borrowed-source loss is durably journaled. */
    public void acknowledgeAbsentBorrowedSource() {
        if (!canAcknowledgeAbsentBorrowedSource()) return;
        targets.clear();
        current = null;
        snapshot = null;
        blocker = null;
        state = State.DONE;
    }
    public boolean isActive() { return state == State.WAITING_FOR_BOT
            || state == State.WALKING || state == State.OPENING
            || state == State.CLOSING || state == State.BREAKING
            || state == State.PICKUP; }

    public boolean retry() {
        if (isActive() || !journal.exists()) return false;
        targets.clear();
        snapshot = null;
        current = null;
        blocker = null;
        state = State.IDLE;
        return begin();
    }

    public boolean begin() {
        if (state != State.IDLE || !journal.exists()) return false;
        try {
            snapshot = journal.load().orElse(null);
        } catch (IOException e) {
            block("checkpoint_unreadable:" + e.getMessage());
            return false;
        }
        if (snapshot == null) return false;
        if (snapshot.borrowedSourceWorksite() != null) {
            targets.add(new Target("borrowed_source", snapshot.borrowedSourceWorksite()));
        }
        if (snapshot.kitWorksite() != null
                && (snapshot.borrowedSourceWorksite() == null
                    || !Arrays.equals(snapshot.borrowedSourceWorksite(), snapshot.kitWorksite()))) {
            targets.add(new Target("kit_box", snapshot.kitWorksite()));
        }
        if (targets.isEmpty()) {
            state = State.DONE;
            if (snapshot.resumeState() == null) {
                emit("kit_recovery_manual_review", Map.of("reason", "no_world_box_marked"));
            }
            return true;
        }
        if (!botReadyNearWorksite()) {
            state = State.WAITING_FOR_BOT;
            return true;
        }
        return nextTarget();
    }

    public void tick() {
        if (state == State.IDLE) begin();
        if (state == State.WAITING_FOR_BOT) {
            if (botReadyNearWorksite()) {
                nextTarget();
            }
            return;
        }
        if (state == State.DONE || state == State.BLOCKED || state == State.IDLE) return;
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            BARITONE.stop();
            restoreNavigation();
            block("bot_unavailable_during_recovery");
            return;
        }
        if (!near(current.position())) {
            block("bot_left_kit_recovery_area");
            return;
        }
        if (CACHE.getPlayerCache().getThePlayer().getFood() <= 8
                && !hasInventoryFood()) {
            block("food_needed_before_world_box_recovery");
            return;
        }
        phaseTicks++;
        if (navigationGuard) CONFIG.client.extra.pathfinder.allowBreak = state == State.BREAKING;
        switch (state) {
            case WALKING -> tickWalking();
            case OPENING -> tickOpening();
            case CLOSING -> tickClosing();
            case BREAKING -> tickBreaking();
            case PICKUP -> tickPickup();
            default -> { }
        }
    }

    private boolean nextTarget() {
        current = targets.pollFirst();
        if (current == null) {
            BARITONE.stop();
            restoreNavigation();
            state = State.DONE;
            emit("kit_recovery_manual_review", Map.of(
                    "reason", "world_boxes_recovered_check_cargo_before_resuming"));
            return true;
        }
        if ("kit_box".equals(current.kind())
                && KitResumePolicy.canRecoverUnplacedKitShell(snapshot)) {
            var inventory = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventory.getMouseStack();
            if (inventory.getOpenContainerId() > 0 || INVENTORY.hasActiveRequest()
                    || cursor != null && cursor.getAmount() > 0) {
                targets.addFirst(current);
                current = null;
                state = State.WAITING_FOR_BOT;
                return true;
            }
            if (recoverUnplacedKitShell()) {
                emit("kit_unplaced_shell_verified_in_inventory", Map.of(
                        "position", pos(current.position()),
                        "owned_shell_slot", snapshot.ownedShellSlot(),
                        "audited_cargo_slot", snapshot.auditedCargoSlot()));
                return nextTarget();
            }
        }
        if ("kit_box".equals(current.kind()) && recoverAlreadyHeldPackedShell()) {
            emit("kit_already_held_shell_reconciled", Map.of(
                    "position", pos(current.position()),
                    "owned_shell_slot", snapshot.ownedShellSlot(),
                    "audited_cargo_slot", snapshot.auditedCargoSlot()));
            return nextTarget();
        }
        phaseTicks = 0;
        lastPathTick = -1;
        openContainerId = -1;
        contentContainerId = -1;
        observedContents = Map.of();
        observedItemId = null;
        misplacedSourceAtKitWorksite = false;
        openGate.reset();
        worldBoxSeen = false;
        guardNavigation();
        state = State.WALKING;
        emit("kit_recovery_target", Map.of("kind", current.kind(),
                "position", pos(current.position())));
        return true;
    }

    private boolean recoverUnplacedKitShell() {
        try {
            var latest = journal.load().orElse(null);
            if (!KitResumePolicy.canRecoverUnplacedKitShell(latest)
                    || !Arrays.equals(latest.kitWorksite(), current.position())) return false;
            var inventory = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventory.getMouseStack();
            if (inventory.getOpenContainerId() > 0 || INVENTORY.hasActiveRequest()
                    || (cursor != null && cursor.getAmount() > 0)) return false;
            int[] site = current.position();
            if (!World.isChunkLoadedBlockPos(site[0], site[2])
                    || World.getChunkSection(site[0], site[1], site[2]) == null
                    || World.getBlock(site[0], site[1], site[2])
                        .name().contains("shulker_box")) return false;
            int shellSlot = uniqueOwnedShellSlot(latest.packedContents());
            if (shellSlot < 0) return false;
            var repaired = new KitFulfillmentJournalStore.Snapshot(
                    latest.schemaVersion(), latest.kitName(), "BLOCKED",
                    latest.recipe(), latest.packedContents(), latest.requestedBoxes(),
                    latest.completedBoxes(), latest.currentItem(), latest.currentQuantity(),
                    shellSlot, latest.exportPosition(), null, null, null, Map.of(),
                    "recovered_box_requires_cargo_audit", System.currentTimeMillis(),
                    null, 0, latest.sourceBoxesToReturn(), -1,
                    latest.writtenOffSources(), Map.of(), latest.incompleteBoxes(),
                    latest.missingTotals(), latest.currentBoxMissing(),
                    latest.displacedAppleSlots(), latest.retainedShell());
            int cargoSlot = KitFulfillmentCoordinator.uniqueAuditedCargoSlot(repaired);
            if (cargoSlot < 0 || !repaired.sourceBoxesToReturn().isEmpty()
                    && KitFulfillmentCoordinator.uniqueSourceReturnSlot(repaired) < 0) return false;
            repaired = new KitFulfillmentJournalStore.Snapshot(
                    repaired.schemaVersion(), repaired.kitName(), repaired.state(),
                    repaired.recipe(), repaired.packedContents(), repaired.requestedBoxes(),
                    repaired.completedBoxes(), repaired.currentItem(), repaired.currentQuantity(),
                    repaired.ownedShellSlot(), repaired.exportPosition(), null, null, null,
                    Map.of(), repaired.blocker(), System.currentTimeMillis(), null, 0,
                    repaired.sourceBoxesToReturn(), cargoSlot,
                    repaired.writtenOffSources(), Map.of(), repaired.incompleteBoxes(),
                    repaired.missingTotals(), repaired.currentBoxMissing(),
                    repaired.displacedAppleSlots(), repaired.retainedShell());
            journal.save(repaired);
            snapshot = repaired;
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** The prior recovery picked up this exact shell, but its checkpoint write failed.
     * Reconcile only the known capacity-failure boundary after proving every owned
     * item and the now-empty worksite. No inventory click or world action occurs. */
    private boolean recoverAlreadyHeldPackedShell() {
        try {
            var latest = journal.load().orElse(null);
            if (latest == null || !"BLOCKED".equals(latest.state())
                    || !"kit_pack_failed:shell_capacity_exhausted_unmergeable".equals(
                            latest.blocker())
                    || latest.resumeState() != null
                    || latest.borrowedSourceWorksite() != null
                    || latest.sourceBoxesToReturn().size() != 1
                    || latest.retainedShell() == null
                    || latest.currentItem() == null || latest.currentQuantity() != 1
                    || !Arrays.equals(latest.kitWorksite(), current.position())
                    || !Proxy.getInstance().isConnected()
                    || Proxy.getInstance().hasActivePlayer()
                    || !CACHE.getPlayerCache().getThePlayer().isAlive()
                    || !near(current.position())) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "checkpoint_or_bot"));
                return false;
            }
            var inventory = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventory.getMouseStack();
            Container player = inventory.getPlayerInventory();
            if (player == null || inventory.getOpenContainerId() > 0
                    || INVENTORY.hasActiveRequest()
                    || cursor != null && cursor.getAmount() > 0) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "inventory_busy"));
                return false;
            }
            int[] site = current.position();
            if (!World.isChunkLoadedBlockPos(site[0], site[2])
                    || World.getChunkSection(site[0], site[1], site[2]) == null
                    || World.getBlock(site[0], site[1], site[2])
                            .name().contains("shulker_box")) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "worksite_unknown_or_box"));
                return false;
            }
            int shellSlot = KitFulfillmentCoordinator.uniqueOwnedShellSlot(latest);
            int cargoSlot = KitFulfillmentCoordinator.uniqueAuditedCargoSlot(latest);
            int sourceSlot = KitFulfillmentCoordinator.uniqueSourceReturnSlot(latest);
            boolean displacedValid = KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                    latest.displacedAppleSlots());
            if (shellSlot < 9 || cargoSlot < 9 || sourceSlot < 9 || !displacedValid) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "owned_items",
                        "shell_slot", shellSlot, "cargo_slot", cargoSlot,
                        "source_slot", sourceSlot, "displaced_valid", displacedValid));
                return false;
            }
            Map<Integer, ItemStack> live = new LinkedHashMap<>();
            for (int raw = 9; raw <= 44; raw++) live.put(raw, player.getItemStack(raw));
            if (KitRetainedShell.uniqueSlot(latest.retainedShell(), live, shellSlot) < 9) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "retained_shell"));
                return false;
            }
            long now = System.currentTimeMillis();
            var repaired = new KitFulfillmentJournalStore.Snapshot(
                    latest.schemaVersion(), latest.kitName(), "BLOCKED", latest.recipe(),
                    latest.packedContents(), latest.requestedBoxes(), latest.completedBoxes(),
                    latest.currentItem(), latest.currentQuantity(), shellSlot,
                    latest.exportPosition(), null, null, latest.borrowedSourceItemId(),
                    latest.borrowedSourceContents(), "resume_audited_inventory_cargo",
                    now, "WAIT_CARGO", now + 300_000L, latest.sourceBoxesToReturn(),
                    cargoSlot, latest.writtenOffSources(), Map.of(),
                    latest.incompleteBoxes(), latest.missingTotals(),
                    latest.currentBoxMissing(), latest.displacedAppleSlots(),
                    latest.retainedShell());
            if (!KitFulfillmentCoordinator.verifiedAuditedCargo(repaired)) {
                emit("kit_already_held_recovery_rejected", Map.of("stage", "audited_cargo"));
                return false;
            }
            journal.save(repaired);
            snapshot = repaired;
            return true;
        } catch (IOException | RuntimeException ignored) {
            emit("kit_already_held_recovery_rejected", Map.of("stage", "checkpoint_write"));
            return false;
        }
    }

    private static int uniqueOwnedShellSlot(Map<String, Integer> expectedContents) {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() != 1
                    || !ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                        .contains("shulker_box")
                    || !expectedContents.equals(ItemIdentifier.readShulkerContents(stack))) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    private void tickWalking() {
        int[] position = current.position();
        // A reconnect can expose the player's old coordinates before the worksite chunk
        // and inventory are hydrated. Unknown blocks must never be treated as air.
        if (phaseTicks < 40 || !World.isChunkLoadedBlockPos(position[0], position[2])
                || World.getChunkSection(position[0], position[1], position[2]) == null
                || CACHE.getPlayerCache().getInventoryCache().getPlayerInventory() == null) {
            if (phaseTicks > WALK_TIMEOUT_TICKS) block("recovery_world_not_loaded:" + current.kind());
            return;
        }
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0) {
            block("container_open_during_recovery");
            return;
        }
        if (ContainerApproach.isAtAccessPosition(
                CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(),
                CACHE.getPlayerCache().getZ(), position[0], position[1], position[2])) {
            BARITONE.stop();
            if (!World.getBlock(position[0], position[1], position[2])
                    .name().contains("shulker_box")) {
                // Login may expose the player pose before the worksite chunk is
                // hydrated. Re-run the exact already-held proof only after the
                // loaded world confirms the old worksite has no shulker.
                if ("kit_box".equals(current.kind()) && recoverAlreadyHeldPackedShell()) {
                    emit("kit_already_held_shell_reconciled", Map.of(
                            "position", pos(current.position()),
                            "owned_shell_slot", snapshot.ownedShellSlot(),
                            "audited_cargo_slot", snapshot.auditedCargoSlot()));
                    nextTarget();
                    return;
                }
                // No placed identity has been observed in this recovery run. An inventory
                // update arriving after login cannot prove that a dropped box was picked up.
                block("journaled_box_absent_or_already_held:" + current.kind());
                return;
            }
            if (freeInventorySlots() < 1) {
                block("no_inventory_room_for_recovery:" + current.kind());
                return;
            }
            if (!BlockCompat.isSolid(position[0], position[1] - 1, position[2])) {
                block("unsafe_recovery_floor:" + current.kind());
                return;
            }
            worldBoxSeen = true;
            phaseTicks = 0;
            state = State.OPENING;
            return;
        }
        if (phaseTicks > WALK_TIMEOUT_TICKS) {
            block("recovery_walk_timeout:" + current.kind());
            return;
        }
        if (lastPathTick < 0 || phaseTicks - lastPathTick >= 40
                && !BARITONE.getCustomGoalProcess().isActive()) {
            lastPathTick = phaseTicks;
            BARITONE.pathTo(new GoalGetToBlock(new BlockPos(
                    position[0], position[1], position[2])));
        }
    }

    private void tickOpening() {
        int[] position = current.position();
        if (!openGate.tick(BOT.isSneaking())) {
            INPUTS.submit(InputRequest.builder().owner(this)
                    .input(Input.builder().sneaking(false).build())
                    .priority(SneakReleaseGate.INPUT_PRIORITY).build());
            phaseTicks = 0;
            return;
        }
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int id = cache.getOpenContainerId();
        Container open = cache.getOpenContainer();
        int slots = id > 0 && open != null && open.getContainerId() == id
                ? open.getSize() - 36 : 0;
        var decision = KitWindowOpenPolicy.evaluate(id, contentContainerId, slots,
                phaseTicks, OPEN_TIMEOUT_TICKS, 60, 27);
        if (decision == KitWindowOpenPolicy.Result.READY) {
            openContainerId = id;
            Map<String, Integer> liveContents = contents(open);
            boolean kitIdentityMatches = open.getSize() - 36 == 27
                    && identityMatches(liveContents);
            boolean misplacedSourceMatches = open.getSize() - 36 == 27
                    && !kitIdentityMatches && misplacedSourceMatches(liveContents);
            if (!kitIdentityMatches && !misplacedSourceMatches) {
                emit("kit_recovery_identity_rejected", Map.of(
                        "kind", current.kind(), "position", pos(position),
                        "observed_contents", liveContents));
                block("recovery_box_identity_mismatch:" + current.kind());
                return;
            }
            misplacedSourceAtKitWorksite = misplacedSourceMatches;
            observedContents = Map.copyOf(liveContents);
            observedItemId = World.getBlock(position[0], position[1], position[2])
                    .name().toLowerCase(java.util.Locale.ROOT);
            if (misplacedSourceAtKitWorksite) emit("kit_misplaced_source_verified", Map.of(
                    "position", pos(position), "owned_shell_slot", snapshot.ownedShellSlot(),
                    "audited_cargo_slot", snapshot.auditedCargoSlot()));
            phaseTicks = 0;
            closeClearTicks = 0;
            state = State.CLOSING;
            return;
        }
        if (decision == KitWindowOpenPolicy.Result.WRONG_TYPE) {
            block("recovery_box_window_mismatch:" + current.kind());
            return;
        }
        if (decision == KitWindowOpenPolicy.Result.TIMED_OUT) {
            block(id > 0 ? "recovery_box_contents_timeout:" + current.kind()
                    : "recovery_box_open_timeout:" + current.kind());
            return;
        }
        if (decision == KitWindowOpenPolicy.Result.RETRY_CLICK
                && (phaseTicks == 1 || phaseTicks % 12 == 0)) {
            BARITONE.rightClickBlock(position[0], position[1], position[2]);
        }
    }

    private void tickClosing() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int currentId = cache.getOpenContainerId();
        if (currentId <= 0) {
            if (++closeClearTicks < 3) return;
            inventoryBoxesBeforeBreak = inventoryShulkerCount();
            shulkerSlotsBeforeBreak = inventoryShulkerSlots();
            phaseTicks = 0;
            state = State.BREAKING;
            return;
        }
        closeClearTicks = 0;
        if (currentId != openContainerId) {
            Container reopened = cache.getOpenContainer();
            if (reopened == null || reopened.getContainerId() != currentId
                    || contentContainerId != currentId || reopened.getSize() - 36 == 0) {
                if (phaseTicks > CLOSE_TIMEOUT_TICKS) {
                    block("recovery_reopen_contents_timeout:" + current.kind());
                }
                return;
            }
            if (reopened.getSize() - 36 != 27
                    || !observedContents.equals(contents(reopened))) {
                block("recovery_reopened_identity_mismatch:" + current.kind());
                return;
            }
            openContainerId = currentId;
            emit("kit_recovery_reopened_box_verified", Map.of("container_id", currentId));
        }
        if (phaseTicks > CLOSE_TIMEOUT_TICKS) {
            block("recovery_box_close_timeout:" + current.kind());
            return;
        }
        if (phaseTicks == 1 || phaseTicks % 20 == 0) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(6000)
                    .actions(new CloseContainer(openContainerId)).build());
        }
    }

    private boolean identityMatches(Map<String, Integer> actual) {
        if ("borrowed_source".equals(current.kind())) {
            return KitRecoveryIdentity.sourceMatches(
                    World.getBlock(current.position()[0], current.position()[1], current.position()[2])
                            .name(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), actual);
        }
        return KitRecoveryIdentity.kitMatches(
                snapshot.recipe(), snapshot.packedContents(), actual);
    }

    private boolean misplacedSourceMatches(Map<String, Integer> actual) {
        if (!"kit_box".equals(current.kind()) || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().size() != 1
                || snapshot.auditedCargoSlot() < 9) return false;
        var source = snapshot.sourceBoxesToReturn().getFirst();
        int[] position = current.position();
        return KitRecoveryIdentity.misplacedSourceMatches(
                    World.getBlock(position[0], position[1], position[2]).name(),
                    source.itemId(), source.contents(), actual)
                && KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot)
                    == snapshot.ownedShellSlot()
                && KitFulfillmentCoordinator.uniqueAuditedCargoSlot(snapshot)
                    == snapshot.auditedCargoSlot()
                && countInventoryBoxes(source.itemId(), source.contents()) == 0;
    }

    private static int countInventoryBoxes(String itemId, Map<String, Integer> contents) {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        int count = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && itemId.equals(ItemIdentifier.getItemId(stack))
                    && contents.equals(ItemIdentifier.readShulkerContents(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static Map<String, Integer> contents(Container open) {
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                contents.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
            }
        }
        return contents;
    }

    private void tickBreaking() {
        int[] position = current.position();
        if (BlockCompat.isAir(World.getBlock(position[0], position[1], position[2]))) {
            phaseTicks = 0;
            sweepAttempt = 0;
            lastSweepTick = -1;
            state = State.PICKUP;
            return;
        }
        if (!World.getBlock(position[0], position[1], position[2])
                .name().contains("shulker_box")) {
            block("recovery_worksite_changed:" + current.kind());
            return;
        }
        if (phaseTicks > BREAK_TIMEOUT_TICKS) {
            block("recovery_break_timeout:" + current.kind());
            return;
        }
        if (phaseTicks == 1) {
            BaritoneCompat.breakBlock(position[0], position[1], position[2], true);
        }
    }

    private void tickPickup() {
        if (inventoryShulkerCount() > inventoryBoxesBeforeBreak) {
            BARITONE.stop();
            if (!worldBoxSeen) {
                block("possible_dropped_box_collected_needs_identity_review:" + current.kind());
                return;
            }
            if (!ownedBoxVisibleAfterPickup()) {
                block("recovered_box_identity_unverified:" + current.kind());
                return;
            }
            if (!markRecovered()) {
                block("recovery_checkpoint_write_failed");
                return;
            }
            emit("kit_recovery_box_picked_up", Map.of("kind", current.kind(),
                    "position", pos(current.position())));
            nextTarget();
            return;
        }
        if (phaseTicks > PICKUP_TIMEOUT_TICKS) {
            block((worldBoxSeen ? "recovery_pickup_unverified:"
                    : "journaled_box_absent_or_already_held:") + current.kind());
            return;
        }
        if (ShulkerPickupSweep.shouldIssuePath(phaseTicks, lastSweepTick,
                BARITONE.getCustomGoalProcess().isActive())) {
            int[] candidate = ShulkerPickupSweep.target(current.position(), sweepAttempt++);
            if (World.isInWorldBounds(candidate[0], candidate[1], candidate[2])
                    && BlockCompat.isSolid(candidate[0], candidate[1] - 1, candidate[2])) {
                lastSweepTick = phaseTicks;
                BARITONE.pathTo(new GoalBlock(new BlockPos(
                        candidate[0], candidate[1], candidate[2])));
            }
        }
    }

    private boolean markRecovered() {
        try {
            KitFulfillmentJournalStore.Snapshot latest = journal.load().orElse(null);
            if (latest == null || !Arrays.equals(latest.kitWorksite(), snapshot.kitWorksite())
                    || !Arrays.equals(latest.borrowedSourceWorksite(),
                            snapshot.borrowedSourceWorksite())) return false;
            snapshot = latest;
        } catch (IOException e) {
            return false;
        }
        if (misplacedSourceAtKitWorksite
                && (!KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot)
                    || KitFulfillmentCoordinator.uniqueAuditedCargoSlot(snapshot)
                        != snapshot.auditedCargoSlot()
                    || KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot) < 0)) {
            return false;
        }
        if ("kit_box".equals(current.kind()) && !misplacedSourceAtKitWorksite
                && snapshot.currentItem() != null && snapshot.currentQuantity() > 0
                && snapshot.sourceBoxesToReturn().isEmpty()) {
            Map<String, Integer> completedBatch = new LinkedHashMap<>(snapshot.packedContents());
            completedBatch.merge(snapshot.currentItem(), snapshot.currentQuantity(), Integer::sum);
            int recoveredSlot = recoveredOwnedBoxSlot();
            if (completedBatch.equals(observedContents) && recoveredSlot >= 9
                    && looseCurrentItemCount(snapshot.currentItem()) == 0) {
                snapshot = new KitFulfillmentJournalStore.Snapshot(
                        KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                        snapshot.recipe(), completedBatch, snapshot.requestedBoxes(),
                        snapshot.completedBoxes(), null, 0, recoveredSlot,
                        snapshot.exportPosition(), null, null, null, Map.of(),
                        "recovered_packed_batch_verified", System.currentTimeMillis(),
                        null, 0, snapshot.sourceBoxesToReturn(), -1,
                        snapshot.writtenOffSources(), Map.of(),
                        snapshot.incompleteBoxes(), snapshot.missingTotals(),
                        snapshot.currentBoxMissing(), snapshot.displacedAppleSlots(),
                        snapshot.retainedShell());
                try {
                    journal.save(snapshot);
                    emit("kit_recovered_packed_batch_verified", Map.of(
                            "item", completedBatch.toString(), "owned_shell_slot", recoveredSlot));
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }
        int recoveredShellSlot = "kit_box".equals(current.kind()) && !misplacedSourceAtKitWorksite
                && observedContents.equals(snapshot.packedContents())
                ? recoveredOwnedBoxSlot() : -1;
        int[] source = "borrowed_source".equals(current.kind())
                ? null : snapshot.borrowedSourceWorksite();
        int[] kit = "kit_box".equals(current.kind())
                ? null : snapshot.kitWorksite();
        snapshot = new KitFulfillmentJournalStore.Snapshot(
                snapshot.schemaVersion(), snapshot.kitName(), "BLOCKED", snapshot.recipe(),
                snapshot.packedContents(), snapshot.requestedBoxes(), snapshot.completedBoxes(),
                snapshot.currentItem(), snapshot.currentQuantity(),
                recoveredShellSlot >= 9 ? recoveredShellSlot : snapshot.ownedShellSlot(),
                snapshot.exportPosition(), kit, source,
                source == null ? null : snapshot.borrowedSourceItemId(),
                source == null ? Map.of() : snapshot.borrowedSourceContents(),
                "recovered_box_requires_cargo_audit",
                System.currentTimeMillis(), snapshot.resumeState(),
                snapshot.resumeNotBeforeEpochMilli(), snapshot.sourceBoxesToReturn(),
                snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                snapshot.missingTotals(), snapshot.currentBoxMissing(),
                snapshot.displacedAppleSlots(), snapshot.retainedShell());
        try {
            journal.save(snapshot);
            return true;
        } catch (IOException e) {
            return false;
        }
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

    private void block(String reason) {
        boolean ownedMovement = isActive();
        blocker = reason;
        if (ownedMovement) BARITONE.stop();
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId > 0 && openId == openContainerId && Proxy.getInstance().isConnected()
                && !Proxy.getInstance().hasActivePlayer()) {
            try {
                INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(6000)
                        .actions(new CloseContainer(openId)).build());
            } catch (RuntimeException ignored) {
                // Keep the blocker and checkpoint; a later recovery retry can close it.
            }
        }
        restoreNavigation();
        state = State.BLOCKED;
        emit("kit_recovery_blocked", Map.of("reason", reason));
    }

    private static int freeInventorySlots() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;
        int free = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) free++;
        }
        return free;
    }

    private static int inventoryShulkerCount() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;
        int boxes = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                        .contains("shulker_box")) boxes += stack.getAmount();
        }
        return boxes;
    }

    private static boolean hasInventoryFood() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return false;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            var food = FoodRegistry.REGISTRY.get(ItemIdentifier.baseItemId(
                    ItemIdentifier.getItemId(stack)));
            if (food != null && food.isSafeFood()) return true;
        }
        return false;
    }

    private static Set<Integer> inventoryShulkerSlots() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return Set.of();
        Set<Integer> slots = new HashSet<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                        .contains("shulker_box")) slots.add(raw);
        }
        return Set.copyOf(slots);
    }

    private boolean ownedBoxVisibleAfterPickup() {
        return recoveredOwnedBoxSlot() >= 9;
    }

    private int recoveredOwnedBoxSlot() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null || observedItemId == null) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            if (shulkerSlotsBeforeBreak.contains(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && observedItemId.equalsIgnoreCase(ItemIdentifier.baseItemId(
                            ItemIdentifier.getItemId(stack)))
                    && observedContents.equals(ItemIdentifier.readShulkerContents(stack))) {
                if (found >= 0) return -1;
                found = raw;
            }
        }
        return found;
    }

    private static int looseCurrentItemCount(String itemId) {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        int total = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && itemId.equals(ItemIdentifier.getItemId(stack))) total += stack.getAmount();
        }
        return total;
    }

    private static String pos(int[] position) {
        return position[0] + "," + position[1] + "," + position[2];
    }

    private boolean botReadyNearWorksite() {
        return Proxy.getInstance().isConnected()
                && !Proxy.getInstance().hasActivePlayer()
                && CACHE.getPlayerCache().getThePlayer().isAlive()
                && !targets.isEmpty()
                && near(targets.peekFirst().position());
    }

    private static boolean near(int[] position) {
        var player = CACHE.getPlayerCache();
        return Math.abs(player.getX() - position[0]) <= 128
                && Math.abs(player.getY() - position[1]) <= 32
                && Math.abs(player.getZ() - position[2]) <= 128;
    }

    private void emit(String event, Map<String, Object> fields) {
        if (events != null) events.accept(event, new LinkedHashMap<>(fields));
    }
}
