package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.kit.KitCapacity;
import com.zenith.plugin.stashmanager.kit.KitAppleExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitInventoryAppleOwnership;
import com.zenith.plugin.stashmanager.kit.KitPackingWorksitePlanner;
import com.zenith.plugin.stashmanager.kit.KitVariantExchangeEvidence;
import com.zenith.plugin.stashmanager.kit.KitVariantExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitWindowOpenPolicy;
import com.zenith.plugin.stashmanager.kit.ShulkerPlacementPriority;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.PathfinderCompat;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/** Places one owned shulker, fills an exact cargo batch, and recovers the verified box. */
public final class KitPacker {
    public enum State { IDLE, RELOCATING, SELECTING, PLACING, OPENING, FILLING, CLOSING, BREAKING, PICKUP, DONE, FAILED }

    // Retriever borrows slot 42 for nested source boxes. Keep the kit box in a separate slot.
    private static final int BOX_SLOTS = 27;
    private static final int CLICK_SETTLE_TICKS = 6;
    private static final int ACTION_TIMEOUT_TICKS = 120;
    private static final int TRANSFER_CONFIRM_TIMEOUT_TICKS = 240;
    private static final int OPEN_CLICK_RETRY_TICKS = 80;
    private static final int MAX_PREPLACE_REPLANS = 3;
    private static final int MAX_COMPACTION_TRANSFERS = BOX_SLOTS * BOX_SLOTS;
    private static final int PICKUP_CONFIRM_TICKS = 20;
    private static final int RELOCATION_TIMEOUT_TICKS = 1200;
    private static final KitPackingWorksitePlanner.WorldView WORKSITE_WORLD =
            new KitPackingWorksitePlanner.WorldView() {
                @Override public boolean replaceable(int x, int y, int z) {
                    return World.isInWorldBounds(x, y, z)
                            && BlockCompat.canReplace(World.getBlock(x, y, z));
                }
                @Override public boolean solid(int x, int y, int z) {
                    return BlockCompat.isSolid(x, y, z);
                }
                @Override public boolean interactable(int x, int y, int z) {
                    return BlockCompat.isInteractable(World.getBlock(x, y, z));
                }
            };

    private final SneakReleaseGate openGate = new SneakReleaseGate();
    private State state = State.IDLE;
    private Map<String, Integer> expectedBefore = Map.of();
    private Map<String, Integer> expectedAfter = Map.of();
    private Set<Integer> protectedSlots = Set.of();
    private int[] worksite;
    private int shellSlot = -1;
    private int hotbarRawSlot = -1;
    private int hotbarIndex = -1;
    private int recoveredSlot = -1;
    private int openContainerId = -1;
    private volatile int contentContainerId = -1;
    private int phaseTicks;
    private int openingMismatchTicks;
    private int closeClearTicks;
    private int fillHandoffWaitTicks;
    private int clickCooldown;
    private int pickupPathAttempt;
    private int lastPickupPathTick = -1;
    private int[] lastPickupTarget;
    private Set<Integer> itemEntitiesBeforeBreak = Set.of();
    private int preplaceReplans;
    private int matchingBoxCountBefore;
    private Set<Integer> matchingBoxSlotsBefore = Set.of();
    private int pickupCandidateSlot = -1;
    private int pickupCandidateTicks;
    private boolean savedSneak;
    private boolean sneakGuard;
    private boolean savedAllowBreak;
    private boolean breakingGuard;
    private boolean savedAllowPlace;
    private boolean relocationPlaceGuard;
    private int[] relocationGoal;
    private int relocationFloorY;
    private ItemData shellItem;
    private PathingRequestFuture placeFuture;
    private PathingRequestFuture openFuture;
    private int lastOpenClickTick = -1;
    private PathingRequestFuture breakFuture;
    private RequestFuture selectionFuture;
    private RequestFuture clickFuture;
    private InventoryTransferEvidence clickEvidence;
    private int clickTicks;
    private int clickSourceSlot = -1;
    private int clickTargetSlot = -1;
    private RequestFuture compactionFuture;
    private CompactionPlan compactionPlan;
    private Map<String, Integer> compactionContentsBefore;
    private int compactionTicks;
    private int compactionTransfers;
    private AppleExchange appleExchange;
    private boolean appleExchangeRemoved;
    private int appleExchangeRemovedUnits;
    private final Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots = new LinkedHashMap<>();
    private int appleExchangeDestinationRaw = -1;
    private ItemStack appleExchangeRemovedStack;
    private InventoryTransferEvidence appleExchangeEvidence;
    private VariantExchange variantExchange;
    private Map<Integer, ItemStack> variantShellBefore = Map.of();
    private KitVariantExchangeEvidence variantEvidence;
    private RequestFuture variantFuture;
    private int variantPhase;
    private int variantTicks;
    private int variantDisplacedRaw = -1;
    private ItemStack variantDisplacedStack;
    private ItemStack variantCargoStack;
    private String failureReason;
    private BiConsumer<String, Map<String, Object>> events;

    public void setEventCallback(BiConsumer<String, Map<String, Object>> events) {
        this.events = events;
    }

    public State state() { return state; }
    public boolean isActive() { return state != State.IDLE && state != State.DONE && state != State.FAILED; }
    public int[] worksite() { return worksite == null ? null : worksite.clone(); }
    public String failureReason() { return failureReason; }
    public int recoveredSlot() { return recoveredSlot; }
    public int selectedShellSlot() { return hotbarRawSlot; }
    public Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots() {
        return Map.copyOf(displacedAppleSlots);
    }

    public int variantDisplacedRaw() { return variantDisplacedRaw; }
    public ItemStack variantDisplacedStack() { return variantDisplacedStack; }
    public boolean variantRecoveredExact() {
        return variantExchange != null && variantEvidence != null && recoveredSlot >= 9
                && variantEvidence.matchesRecoveredWithCargo(
                        playerStack(recoveredSlot), variantCargoStack);
    }

    /** One live-source-verified replacement stack and its pinned player slot. */
    public record VariantExchange(KitVariantExchangePlan.Plan plan, int incomingRawSlot) {
        public VariantExchange {
            if (plan == null || incomingRawSlot < 9 || incomingRawSlot > 35)
                throw new IllegalArgumentException("invalid variant exchange");
        }
    }

    /** Audited incompatible apple stacks may be displaced before packing replacement cargo. */
    public record AppleExchange(ItemStack replacementExemplar, int removeAmount) {
        public AppleExchange {
            if (replacementExemplar == null || !"enchanted_golden_apple".equals(
                    ItemIdentifier.getItemId(replacementExemplar)) || removeAmount < 1
                    || removeAmount > BOX_SLOTS * 64) {
                throw new IllegalArgumentException("invalid apple exchange");
            }
        }
    }

    public void onContainerData(int containerId) {
        if ((state == State.OPENING || state == State.FILLING || state == State.CLOSING)
                && containerId > 0) {
            contentContainerId = containerId;
        }
    }

    public void abortForRecovery(String reason) {
        if (isActive()) fail("interrupted:" + reason);
    }

    public boolean cancelBeforeWorldAction() {
        if ((state != State.RELOCATING && state != State.SELECTING && state != State.PLACING)
                || placeFuture != null) return false;
        BARITONE.stop();
        restoreSneak();
        restoreBreaking();
        restoreRelocationPlace();
        state = State.IDLE;
        return true;
    }

    public boolean start(Map<String, Integer> contentsBefore, Map<String, Integer> cargo,
                         Set<Integer> protectedSlots) {
        return start(contentsBefore, cargo, protectedSlots, -1);
    }

    public boolean start(Map<String, Integer> contentsBefore, Map<String, Integer> cargo,
                         Set<Integer> protectedSlots, int ownedShellSlot) {
        return start(contentsBefore, cargo, protectedSlots, ownedShellSlot, null);
    }

    public boolean start(Map<String, Integer> contentsBefore, Map<String, Integer> cargo,
                         Set<Integer> protectedSlots, int ownedShellSlot,
                         AppleExchange exchange) {
        return start(contentsBefore, cargo, protectedSlots, ownedShellSlot, exchange, null);
    }

    public boolean start(Map<String, Integer> contentsBefore, Map<String, Integer> cargo,
                         Set<Integer> protectedSlots, int ownedShellSlot,
                         AppleExchange exchange, VariantExchange variant) {
        if (isActive() || !Proxy.getInstance().isConnected()
                || Proxy.getInstance().hasActivePlayer() || cargo == null || cargo.isEmpty()) {
            return rejectStart("bot_or_cargo_unavailable");
        }
        var player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0) {
            return rejectStart("inventory_window_unavailable");
        }

        Map<String, Integer> batch = positiveCopy(cargo);
        if (batch.isEmpty()) return rejectStart("empty_cargo_batch");
        Map<String, Integer> before = positiveCopy(contentsBefore);
        Map<String, Integer> after = new LinkedHashMap<>(before);
        batch.forEach((id, amount) -> after.merge(id, amount, Integer::sum));
        if (exchange != null && variant != null) return rejectStart("multiple_exchanges_unavailable");
        if (exchange != null) {
            int apples = after.getOrDefault("enchanted_golden_apple", 0);
            if (apples < exchange.removeAmount()) return rejectStart("apple_exchange_exceeds_contents");
            after.put("enchanted_golden_apple", apples - exchange.removeAmount());
        }
        if (KitCapacity.requiredSlots(after) > BOX_SLOTS) return rejectStart("box_capacity_exceeded");

        Set<Integer> guarded = protectedSlots == null ? Set.of() : Set.copyOf(protectedSlots);
        Map<String, Integer> movable = new HashMap<>();
        List<ItemStack> physicalCargo = new ArrayList<>();
        int foundShellSlot = -1;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            String id = ItemIdentifier.getItemId(stack);
            if (!guarded.contains(raw) && isShulker(stack)
                    && ItemIdentifier.readShulkerContents(stack).equals(before)
                    && foundShellSlot < 0 && (ownedShellSlot < 0 || ownedShellSlot == raw)) {
                foundShellSlot = raw;
            }
            if (!guarded.contains(raw) && batch.containsKey(id)) {
                movable.merge(id, stack.getAmount(), Integer::sum);
                physicalCargo.add(stack);
            }
        }
        if (foundShellSlot < 0) return rejectStart("owned_shell_missing_or_protected");
        if (!movable.equals(batch)) return rejectStart("movable_cargo_mismatch");
        Map<Integer, ItemStack> variantBefore = Map.of();
        if (variant != null) {
            ItemStack ownedShell = player.getItemStack(foundShellSlot);
            variantBefore = ItemIdentifier.readShulkerPhysicalSlotStacks(ownedShell);
            var plan = variant.plan();
            ItemStack incoming = player.getItemStack(variant.incomingRawSlot());
            ItemStack outgoing = variantBefore.get(plan.outgoingSlot());
            ItemStack mergeTarget = variantBefore.get(plan.mergeTargetSlot());
            if (variantBefore.size() != BOX_SLOTS || physicalCargo.size() != 1
                    || guarded.contains(variant.incomingRawSlot())
                    || !sameStack(outgoing, plan.outgoingStack())
                    || outgoing.getAmount() != plan.amount()
                    || !sameStack(mergeTarget, plan.mergeTargetStack())
                    || mergeTarget.getAmount() != plan.mergeTargetStack().getAmount()
                    || !sameStack(incoming, plan.replacementExemplar())
                    || incoming.getAmount() != plan.amount()
                    || plan.slotsAfter() + KitCapacity.requiredSlots(batch) > BOX_SLOTS
                    || firstEmptyRawPlayerSlot(player, guarded) < 0) {
                return rejectStart("variant_exchange_preflight_mismatch");
            }
        }
        if (exchange == null && variant == null) {
            ItemStack ownedShell = player.getItemStack(foundShellSlot);
            List<ItemStack> physicalPacked = ItemIdentifier.readShulkerPhysicalStacks(ownedShell);
            if (physicalPacked.size() != ItemIdentifier.readShulkerOccupiedSlots(ownedShell))
                return rejectStart("shell_physical_contents_unavailable");
            List<ItemStack> combined = new ArrayList<>(physicalPacked);
            combined.addAll(physicalCargo);
            int slotsNeeded = physicalRequiredSlots(combined, id -> {
                ItemData data = ItemRegistry.REGISTRY.get(id);
                return data == null ? 1 : Math.max(1, data.stackSize());
            });
            if (slotsNeeded > BOX_SLOTS) {
                emit("kit_shell_physical_capacity_rejected", Map.of(
                        "occupied_slots", physicalPacked.size(),
                        "required_slots", slotsNeeded,
                        "minimum_slots", KitCapacity.requiredSlots(after)));
                return rejectStart("physical_capacity_exceeded_unmergeable");
            }
        }
        int selectedHotbar = foundShellSlot >= 36 && foundShellSlot <= 44
                ? foundShellSlot : firstFreeHotbar(player, guarded);
        if (selectedHotbar < 36 || selectedHotbar > 44) return rejectStart("hotbar_slot_unavailable");
        int[] safeSpot = findSafeSpot();
        KitPackingWorksitePlanner.Plan relocation = safeSpot == null
                ? KitPackingWorksitePlanner.discover(
                        (int) Math.floor(CACHE.getPlayerCache().getX()),
                        (int) Math.floor(CACHE.getPlayerCache().getY()),
                        (int) Math.floor(CACHE.getPlayerCache().getZ()), WORKSITE_WORLD)
                : null;
        if (safeSpot == null && relocation == null) {
            return rejectStart("no_discovered_safe_worksite");
        }

        this.expectedBefore = Map.copyOf(before);
        this.expectedAfter = Map.copyOf(after);
        this.protectedSlots = guarded;
        this.worksite = safeSpot;
        this.relocationGoal = relocation == null ? null : relocation.stance();
        this.relocationFloorY = (int) Math.floor(CACHE.getPlayerCache().getY());
        this.shellSlot = foundShellSlot;
        this.hotbarRawSlot = selectedHotbar;
        this.hotbarIndex = selectedHotbar - 36;
        this.recoveredSlot = -1;
        this.shellItem = ItemRegistry.REGISTRY.get(player.getItemStack(foundShellSlot).getId());
        if (shellItem == null) return rejectStart("shell_item_unregistered");
        this.matchingBoxCountBefore = countMatchingBoxes(after);
        this.matchingBoxSlotsBefore = matchingBoxSlots(after);
        this.pickupCandidateSlot = -1;
        this.pickupCandidateTicks = 0;
        this.openContainerId = -1;
        this.failureReason = null;
        this.phaseTicks = 0;
        this.fillHandoffWaitTicks = 0;
        this.clickCooldown = 0;
        this.pickupPathAttempt = 0;
        this.lastPickupPathTick = -1;
        this.lastPickupTarget = null;
        this.itemEntitiesBeforeBreak = Set.of();
        this.preplaceReplans = 0;
        this.placeFuture = null;
        this.openFuture = null;
        this.lastOpenClickTick = -1;
        this.breakFuture = null;
        this.selectionFuture = null;
        this.clickFuture = null;
        this.clickEvidence = null;
        this.compactionFuture = null;
        this.compactionPlan = null;
        this.compactionContentsBefore = null;
        this.compactionTicks = 0;
        this.compactionTransfers = 0;
        this.appleExchange = exchange;
        this.appleExchangeRemoved = false;
        this.appleExchangeRemovedUnits = 0;
        this.displacedAppleSlots.clear();
        this.appleExchangeDestinationRaw = -1;
        this.appleExchangeRemovedStack = null;
        this.appleExchangeEvidence = null;
        this.variantExchange = variant;
        this.variantShellBefore = variantBefore;
        this.variantEvidence = null;
        this.variantFuture = null;
        this.variantPhase = 0;
        this.variantTicks = 0;
        this.variantDisplacedRaw = -1;
        this.variantDisplacedStack = null;
        this.variantCargoStack = variant == null ? null : physicalCargo.getFirst();
        this.openGate.reset();
        this.savedAllowBreak = CONFIG.client.extra.pathfinder.allowBreak;
        this.breakingGuard = true;
        CONFIG.client.extra.pathfinder.allowBreak = false;
        if (relocation != null) {
            this.savedAllowPlace = CONFIG.client.extra.pathfinder.allowPlace;
            this.relocationPlaceGuard = true;
            CONFIG.client.extra.pathfinder.allowPlace = false;
        }
        this.state = relocation == null ? State.SELECTING : State.RELOCATING;
        emit("kit_pack_started", Map.of("batch_items", batch.size()));
        if (relocation != null) emit("kit_pack_relocation_started", Map.of(
                "stance", posString(relocation.stance()),
                "candidate_worksite", posString(relocation.worksite())));
        return true;
    }

    private boolean rejectStart(String reason) {
        failureReason = reason;
        emit("kit_pack_preflight_rejected", Map.of("reason", reason));
        return false;
    }

    public void tick() {
        if (!isActive()) return;
        if (breakingGuard) CONFIG.client.extra.pathfinder.allowBreak = state == State.BREAKING;
        if (relocationPlaceGuard) CONFIG.client.extra.pathfinder.allowPlace = false;
        phaseTicks++;
        switch (state) {
            case RELOCATING -> tickRelocating();
            case SELECTING -> tickSelecting();
            case PLACING -> tickPlacing();
            case OPENING -> tickOpening();
            case FILLING -> tickFilling();
            case CLOSING -> tickClosing();
            case BREAKING -> tickBreaking();
            case PICKUP -> tickPickup();
            default -> { }
        }
    }

    private void tickRelocating() {
        var player = CACHE.getPlayerCache();
        if ((int) Math.floor(player.getY()) < relocationFloorY) {
            fail("worksite_relocation_descended_below_cargo_floor");
            return;
        }
        int[] reachable = findSafeSpot();
        if (reachable != null) {
            BARITONE.stop();
            worksite = reachable;
            relocationGoal = null;
            restoreRelocationPlace();
            advance(State.SELECTING);
            emit("kit_pack_relocation_arrived", Map.of("worksite", posString(reachable)));
            return;
        }
        if (phaseTicks > RELOCATION_TIMEOUT_TICKS) {
            fail("worksite_relocation_timeout");
            return;
        }
        if (relocationGoal == null) {
            fail("worksite_relocation_goal_missing");
            return;
        }
        if ((int) Math.floor(player.getX()) == relocationGoal[0]
                && (int) Math.floor(player.getY()) == relocationGoal[1]
                && (int) Math.floor(player.getZ()) == relocationGoal[2]) {
            fail("worksite_unavailable_at_relocation_goal");
            return;
        }
        if (phaseTicks == 1 || (phaseTicks % 40 == 0
                && !BARITONE.getCustomGoalProcess().isActive())) {
            BARITONE.pathTo(new GoalBlock(new BlockPos(
                    relocationGoal[0], relocationGoal[1], relocationGoal[2])));
        }
    }

    private void tickSelecting() {
        if (selectionFuture == null) {
            try {
                var builder = InventoryActionRequest.builder().owner(this).priority(6000);
                selectionFuture = shellSlot == hotbarRawSlot
                        ? INVENTORY.submit(builder.actions(new SetHeldItem(hotbarIndex)).build())
                        : INVENTORY.submit(builder.actions(
                                new MoveToHotbarSlot(shellSlot, MoveToHotbarAction.from(hotbarIndex)),
                                new SetHeldItem(hotbarIndex)).build());
            } catch (RuntimeException e) {
                fail("shell_selection_rejected");
            }
            return;
        }
        if (phaseTicks > ACTION_TIMEOUT_TICKS) { fail("shell_selection_timeout"); return; }
        if (!selectionFuture.isCompleted()) return;
        if (!selectionFuture.getNow()) { fail("shell_selection_rejected"); return; }
        ItemStack held = playerStack(hotbarRawSlot);
        if (CACHE.getPlayerCache().getHeldItemSlot() != hotbarIndex || !isShulker(held)
                || !ItemIdentifier.readShulkerContents(held).equals(expectedBefore)) return;
        int first = ShulkerPlacementPriority.firstMatchingSlot(raw -> {
            ItemStack stack = playerStack(raw);
            return stack != null && stack.getAmount() > 0 && stack.getId() == held.getId();
        });
        if (first != hotbarRawSlot) {
            if (first < 36 || first > 44) { fail("shell_selection_conflict"); return; }
            int previousSlot = hotbarRawSlot;
            try {
                RequestFuture swap = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this).priority(6000)
                        .actions(new MoveToHotbarSlot(previousSlot,
                                        MoveToHotbarAction.from(first - 36)),
                                new SetHeldItem(first - 36))
                        .build());
                if (swap.isCompleted() && !swap.getNow()) {
                    fail("shell_priority_swap_rejected");
                    return;
                }
                Set<Integer> guarded = new HashSet<>(protectedSlots);
                if (guarded.remove(first)) guarded.add(previousSlot);
                protectedSlots = Set.copyOf(guarded);
                selectionFuture = swap;
                shellSlot = first;
                hotbarRawSlot = first;
                hotbarIndex = first - 36;
                phaseTicks = 0;
                emit("kit_shell_prioritized", Map.of("from_slot", previousSlot,
                        "to_slot", first));
            } catch (RuntimeException e) {
                fail("shell_priority_swap_rejected");
            }
            return;
        }
        setSneak(true);
        advance(State.PLACING);
    }

    private void tickPlacing() {
        if (isShulkerAtWorksite()) { setSneak(false); advance(State.OPENING); return; }
        if (phaseTicks > 200) { fail("shell_place_timeout"); return; }
        if (placeFuture == null) {
            if (!isSafeSpot(worksite)) {
                int[] alternate = preplaceReplans < MAX_PREPLACE_REPLANS
                        ? findSafeSpot() : null;
                if (alternate == null) { fail("worksite_changed_before_place"); return; }
                worksite = alternate;
                preplaceReplans++;
                emit("kit_pack_worksite_replanned", Map.of(
                        "position", posString(worksite), "attempt", preplaceReplans));
                return;
            }
            placeFuture = BaritoneCompat.placeBlock(worksite[0], worksite[1], worksite[2], shellItem);
            return;
        }
        if (placeFuture.isDone() && !placeFuture.getNow()) fail("shell_place_rejected");
    }

    private void tickOpening() {
        if (!isShulkerAtWorksite()) { fail("placed_shell_missing"); return; }
        setSneak(false);
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
                phaseTicks, 180, 60, BOX_SLOTS);
        if (decision == KitWindowOpenPolicy.Result.READY) {
            Map<String, Integer> observed = contents(open);
            if (!observed.equals(expectedBefore)) {
                // A server window can be announced before its slot contents finish arriving.
                // Never click while the observed shell differs, but allow that snapshot to
                // settle before treating it as a real change to the journaled kit.
                if (++openingMismatchTicks >= 60) {
                    emit("kit_shell_open_contents_mismatch", Map.of(
                            "expected", expectedBefore, "observed", observed,
                            "wait_ticks", openingMismatchTicks));
                    fail("shell_contents_changed");
                }
                return;
            }
            openingMismatchTicks = 0;
            if (variantExchange != null && !samePhysicalSlots(variantShellBefore,
                    physicalShellSlots(open))) {
                fail("variant_shell_physical_identity_changed");
                return;
            }
            advance(State.FILLING);
            return;
        }
        if (decision == KitWindowOpenPolicy.Result.WRONG_TYPE) {
            fail("wrong_container_opened"); return;
        }
        if (decision == KitWindowOpenPolicy.Result.TIMED_OUT) {
            fail(liveId > 0 ? "shell_contents_timeout" : "shell_open_timeout"); return;
        }
        boolean clickPending = openFuture != null && !openFuture.isDone();
        if (KitWindowOpenPolicy.shouldIssueClick(decision, phaseTicks,
                lastOpenClickTick, OPEN_CLICK_RETRY_TICKS, clickPending)) {
            BARITONE.stop();
            openFuture = BARITONE.rightClickBlock(worksite[0], worksite[1], worksite[2]);
            lastOpenClickTick = phaseTicks;
        }
    }

    private void tickFilling() {
        int currentId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openContainerId > 0 && currentId > 0 && currentId != openContainerId) {
            Container reopened = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            BARITONE.stop();
            if (clickEvidence != null) { fail("shell_window_lost"); return; }
            if (reopened == null || reopened.getContainerId() != currentId
                    || contentContainerId != currentId || containerSlots(reopened) == 0) {
                if (++fillHandoffWaitTicks > 60) fail("shell_reopen_contents_timeout");
                return;
            }
            if (containerSlots(reopened) != BOX_SLOTS
                    || !isShulkerAtWorksite()
                    || !contents(reopened).equals(expectedBefore)) {
                fail("shell_window_lost"); return;
            }
            openContainerId = currentId;
            fillHandoffWaitTicks = 0;
            emit("kit_pack_reopened_shell_verified", Map.of("container_id", currentId));
        }
        Container open = liveOpen();
        if (open == null || containerSlots(open) != BOX_SLOTS || !isShulkerAtWorksite()) {
            fail("shell_window_lost"); return;
        }
        if (compactionPlan != null) {
            var cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
            if (compactionPlan.confirmed(open)
                    && (cursor == null || cursor.getAmount() <= 0)
                    && contents(open).equals(compactionContentsBefore)) {
                emit("kit_shell_compaction_verified", Map.of(
                        "source_slot", compactionPlan.sourceSlot(),
                        "target_slot", compactionPlan.targetSlot(),
                        "empty_slots", emptyBoxSlots(open)));
                compactionPlan = null;
                compactionFuture = null;
                compactionContentsBefore = null;
                compactionTicks = 0;
                clickCooldown = CLICK_SETTLE_TICKS;
                return;
            }
            if (++compactionTicks > TRANSFER_CONFIRM_TIMEOUT_TICKS
                    || (compactionFuture.isCompleted() && !compactionFuture.getNow())) {
                emit("kit_shell_compaction_unconfirmed", Map.of(
                        "source_slot", compactionPlan.sourceSlot(),
                        "target_slot", compactionPlan.targetSlot(),
                        "occupied_slots", BOX_SLOTS - emptyBoxSlots(open),
                        "cursor_count", cursor == null ? 0 : cursor.getAmount()));
                fail("shell_compaction_unconfirmed");
            }
            return;
        }
        if (appleExchangeEvidence != null) {
            var cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
            ItemStack displaced = open.getItemStack(rawToWindow(appleExchangeDestinationRaw));
            int movedAmount = appleExchangeRemovedStack.getAmount();
            boolean verified = appleExchangeEvidence.moved(open) == movedAmount
                    && appleExchangeEvidence.received(open) == movedAmount
                    && sameStack(displaced, appleExchangeRemovedStack)
                    && displaced.getAmount() == movedAmount
                    && (open.getItemStack(clickSourceSlot) == null
                        || open.getItemStack(clickSourceSlot).getAmount() <= 0)
                    && (cursor == null || cursor.getAmount() <= 0);
            if (verified) {
                var evidence = KitInventoryAppleOwnership.evidence(displaced);
                if (evidence == null || !evidence.customPresentation()
                        || displacedAppleSlots.putIfAbsent(appleExchangeDestinationRaw,
                                evidence) != null) {
                    fail("apple_exchange_displacement_identity_ambiguous");
                    return;
                }
                Set<Integer> guarded = new HashSet<>(protectedSlots);
                guarded.add(appleExchangeDestinationRaw);
                protectedSlots = Set.copyOf(guarded);
                appleExchangeRemovedUnits += movedAmount;
                appleExchangeRemoved = appleExchangeRemovedUnits == appleExchange.removeAmount();
                appleExchangeEvidence = null;
                clickFuture = null;
                clickTicks = 0;
                clickCooldown = CLICK_SETTLE_TICKS;
                emit("kit_apple_exchange_displacement_verified", Map.of(
                        "amount", movedAmount,
                        "total_removed", appleExchangeRemovedUnits,
                        "inventory_slot", appleExchangeDestinationRaw));
                return;
            }
            if (++clickTicks > TRANSFER_CONFIRM_TIMEOUT_TICKS
                    || (clickFuture.isCompleted() && !clickFuture.getNow())) {
                fail("apple_exchange_displacement_unconfirmed");
            }
            return;
        }
        if (clickEvidence != null) {
            int moved = clickEvidence.moved(open);
            if (moved > 0 && clickEvidence.received(open) > 0) {
                clickEvidence = null;
                clickFuture = null;
                clickTicks = 0;
                clickCooldown = CLICK_SETTLE_TICKS;
                return;
            }
            if (++clickTicks > TRANSFER_CONFIRM_TIMEOUT_TICKS
                    || (clickFuture.isCompleted() && !clickFuture.getNow())) {
                emit("kit_pack_transfer_unconfirmed", Map.of(
                        "wait_ticks", clickTicks,
                        "request_completed", clickFuture.isCompleted(),
                        "request_success", clickFuture.isCompleted() && clickFuture.getNow(),
                        "moved", moved,
                        "received", clickEvidence.received(open),
                        "source_slot", clickSourceSlot,
                        "target_slot", clickTargetSlot,
                        "container_id", currentId,
                        "expected_container_id", openContainerId));
                fail("shell_transfer_unconfirmed");
            }
            return;
        }
        if (clickCooldown-- > 0) return;
        if (variantExchange != null && variantPhase < 4) {
            tickVariantExchange(open);
            return;
        }
        if (appleExchange != null && !appleExchangeRemoved) {
            int source = firstIncompatibleAppleSlot(open, appleExchange.replacementExemplar(),
                    appleExchange.removeAmount() - appleExchangeRemovedUnits);
            int destination = firstEmptyPlayerRawSlot(open, protectedSlots);
            if (source < 0 || destination < 0) {
                fail("apple_exchange_source_or_headroom_unverified");
                return;
            }
            var cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
            if (cursor != null && cursor.getAmount() > 0) {
                fail("shell_cursor_not_empty");
                return;
            }
            try {
                appleExchangeEvidence = InventoryTransferEvidence.capture(open, BOX_SLOTS, source);
                appleExchangeDestinationRaw = destination;
                appleExchangeRemovedStack = open.getItemStack(source);
                clickSourceSlot = source;
                clickFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .priority(6000).actions(
                                new ClickItem(openContainerId, source, ClickItemAction.LEFT_CLICK),
                                new PlaceCursorStackInEmptySlot(openContainerId,
                                        rawToWindow(destination)))
                        .build());
                clickTicks = 0;
            } catch (RuntimeException e) {
                fail("apple_exchange_displacement_rejected");
            }
            return;
        }
        Map<String, Integer> actual = contents(open);
        for (var entry : actual.entrySet()) {
            if (entry.getValue() > expectedAfter.getOrDefault(entry.getKey(), 0)) {
                fail("shell_contains_unexpected_cargo"); return;
            }
        }
        if (actual.equals(expectedAfter)) {
            advance(State.CLOSING);
            return;
        }
        for (int raw = 9; raw <= 44; raw++) {
            if (protectedSlots.contains(raw)) continue;
            int windowSlot = rawToWindow(raw);
            ItemStack stack = open.getItemStack(windowSlot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String id = ItemIdentifier.getItemId(stack);
            int missing = expectedAfter.getOrDefault(id, 0) - actual.getOrDefault(id, 0);
            if (missing < stack.getAmount()) continue;
            var cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
            if (cursor != null && cursor.getAmount() > 0) {
                fail("shell_cursor_not_empty"); return;
            }
            int emptySlot = firstEmptyBoxSlot(open);
            if (emptySlot < 0 && exactStackHeadroom(open, stack) < stack.getAmount()) {
                if (compactionTransfers >= MAX_COMPACTION_TRANSFERS) {
                    fail("shell_compaction_limit"); return;
                }
                CompactionPlan plan = findCompactionPlan(open);
                if (plan == null) {
                    emit("kit_shell_capacity_exhausted", Map.of(
                            "occupied_slots", BOX_SLOTS,
                            "minimum_slots", KitCapacity.requiredSlots(expectedAfter),
                            "missing_item", id));
                    fail("shell_capacity_exhausted_unmergeable");
                    return;
                }
                try {
                    compactionContentsBefore = actual;
                    compactionPlan = plan;
                    List<InventoryAction> actions = new ArrayList<>(3);
                    actions.add(new GuardedClickItem(openContainerId, plan.sourceSlot(),
                            ClickItemAction.LEFT_CLICK));
                    actions.add(new GuardedClickItem(openContainerId, plan.targetSlot(),
                            ClickItemAction.LEFT_CLICK));
                    if (plan.sourceAfter() > 0) {
                        actions.add(new GuardedClickItem(openContainerId, plan.sourceSlot(),
                                ClickItemAction.LEFT_CLICK));
                    }
                    compactionFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                            .priority(6000).actions(actions).build());
                    compactionTicks = 0;
                    compactionTransfers++;
                    emit("kit_shell_compaction_started", Map.of(
                            "source_slot", plan.sourceSlot(),
                            "target_slot", plan.targetSlot(),
                            "source_before", plan.sourceBefore(),
                            "target_before", plan.targetBefore()));
                } catch (RuntimeException e) {
                    fail("shell_compaction_rejected");
                }
                return;
            }
            try {
                clickEvidence = InventoryTransferEvidence.capture(open, BOX_SLOTS, windowSlot);
                clickSourceSlot = windowSlot;
                clickTargetSlot = stack.getAmount() == 1 ? emptySlot : -1;
                if (clickTargetSlot >= 0) {
                    // A successful quick-move request can still leave a one-item cargo
                    // stack untouched. Use a verified empty box slot when one exists.
                    clickFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                            .priority(6000).actions(
                                    new ClickItem(openContainerId, windowSlot, ClickItemAction.LEFT_CLICK),
                                    new PlaceCursorStackInEmptySlot(openContainerId, clickTargetSlot))
                            .build());
                } else {
                    clickFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                            .priority(6000).actions(new ShiftClick(openContainerId, windowSlot,
                                    ShiftClickItemAction.LEFT_CLICK)).build());
                }
                clickTicks = 0;
            } catch (RuntimeException e) {
                fail("shell_transfer_rejected");
            }
            return;
        }
        fail("missing_inventory_cargo_for_shell");
    }

    /** Every click is followed by exact server-window evidence; an uncertain click is never retried. */
    private void tickVariantExchange(Container open) {
        var cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        boolean clearCursor = cursor == null || cursor.getAmount() <= 0;
        var plan = variantExchange.plan();
        if (variantPhase == 0) {
            if (!clearCursor) { fail("variant_cursor_not_empty"); return; }
            int destination = firstEmptyPlayerRawSlot(open, protectedSlots);
            if (destination < 0) { fail("variant_displacement_headroom_lost"); return; }
            var evidence = KitVariantExchangeEvidence.capture(plan, variantShellBefore,
                    physicalShellSlots(open), physicalPlayerSlots(open),
                    variantExchange.incomingRawSlot(), destination);
            if (evidence == null) { fail("variant_exchange_live_preflight_mismatch"); return; }
            try {
                variantEvidence = evidence;
                variantDisplacedRaw = destination;
                variantDisplacedStack = plan.outgoingStack();
                variantFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .priority(6000).actions(
                                new GuardedClickItem(openContainerId, plan.outgoingSlot(),
                                        ClickItemAction.LEFT_CLICK),
                                new PlaceCursorStackInEmptySlot(openContainerId,
                                        rawToWindow(destination)))
                        .build());
                variantPhase = 1;
                variantTicks = 0;
            } catch (RuntimeException e) {
                fail("variant_displacement_rejected");
            }
            return;
        }
        if (variantPhase == 1) {
            if (clearCursor && variantEvidence.matchesAfterRemoval(
                    physicalShellSlots(open), physicalPlayerSlots(open))) {
                variantPhase = 2;
                variantFuture = null;
                variantTicks = 0;
                clickCooldown = CLICK_SETTLE_TICKS;
                emit("kit_variant_displacement_verified", Map.of(
                        "amount", plan.amount(), "player_slot", variantDisplacedRaw));
            } else if (++variantTicks > TRANSFER_CONFIRM_TIMEOUT_TICKS
                    || (variantFuture.isCompleted() && !variantFuture.getNow())) {
                fail("variant_displacement_unconfirmed");
            }
            return;
        }
        if (variantPhase == 2) {
            if (!clearCursor || !variantEvidence.matchesAfterRemoval(
                    physicalShellSlots(open), physicalPlayerSlots(open))) {
                fail("variant_addition_preflight_changed");
                return;
            }
            try {
                variantFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .priority(6000).actions(
                                new GuardedClickItem(openContainerId,
                                        rawToWindow(variantExchange.incomingRawSlot()),
                                        ClickItemAction.LEFT_CLICK),
                                new GuardedClickItem(openContainerId, plan.mergeTargetSlot(),
                                        ClickItemAction.LEFT_CLICK))
                        .build());
                variantPhase = 3;
                variantTicks = 0;
            } catch (RuntimeException e) {
                fail("variant_addition_rejected");
            }
            return;
        }
        if (clearCursor && variantEvidence.matchesAfterAddition(
                physicalShellSlots(open), physicalPlayerSlots(open))) {
            Set<Integer> guarded = new HashSet<>(protectedSlots);
            guarded.add(variantDisplacedRaw);
            protectedSlots = Set.copyOf(guarded);
            variantPhase = 4;
            variantFuture = null;
            variantTicks = 0;
            clickCooldown = CLICK_SETTLE_TICKS;
            emit("kit_variant_exchange_verified", Map.of(
                    "amount", plan.amount(), "displaced_slot", variantDisplacedRaw));
        } else if (++variantTicks > TRANSFER_CONFIRM_TIMEOUT_TICKS
                || (variantFuture.isCompleted() && !variantFuture.getNow())) {
            fail("variant_addition_unconfirmed");
        }
    }

    private static Map<Integer, ItemStack> physicalShellSlots(Container open) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) result.put(slot, stack);
        }
        return result;
    }

    private static Map<Integer, ItemStack> physicalPlayerSlots(Container open) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = open.getItemStack(rawToWindow(raw));
            if (stack != null && stack.getAmount() > 0) result.put(raw, stack);
        }
        return result;
    }

    private static boolean samePhysicalSlots(Map<Integer, ItemStack> a,
                                             Map<Integer, ItemStack> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (var entry : a.entrySet()) {
            ItemStack other = b.get(entry.getKey());
            if (!sameStack(entry.getValue(), other)
                    || entry.getValue().getAmount() != other.getAmount()) return false;
        }
        return true;
    }

    private static int firstEmptyRawPlayerSlot(Container player,
                                               Set<Integer> protectedSlots) {
        for (int raw = 9; raw <= 35; raw++) {
            if (protectedSlots.contains(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) return raw;
        }
        return -1;
    }

    private void tickClosing() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int currentId = cache.getOpenContainerId();
        if (currentId <= 0) {
            if (++closeClearTicks >= 3) advance(State.BREAKING);
            return;
        }
        closeClearTicks = 0;
        if (currentId != openContainerId) {
            Container reopened = cache.getOpenContainer();
            if (reopened == null || reopened.getContainerId() != currentId
                    || contentContainerId != currentId || containerSlots(reopened) == 0) {
                if (phaseTicks > ACTION_TIMEOUT_TICKS) fail("shell_reopen_contents_timeout");
                return;
            }
            if (containerSlots(reopened) != BOX_SLOTS
                    || !isShulkerAtWorksite()
                    || !contents(reopened).equals(expectedAfter)) {
                fail("shell_reopened_window_mismatch");
                return;
            }
            openContainerId = currentId;
            emit("kit_pack_reopened_shell_verified", Map.of("container_id", currentId));
        }
        if (phaseTicks > ACTION_TIMEOUT_TICKS) { fail("shell_close_timeout"); return; }
        if (phaseTicks == 1 || phaseTicks % 20 == 0) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(5000)
                    .actions(new CloseContainer(openContainerId)).build());
        }
    }

    private void tickBreaking() {
        if (!isShulkerAtWorksite()) {
            if (BlockCompat.isAir(World.getBlock(worksite[0], worksite[1], worksite[2]))) {
                advance(State.PICKUP);
            } else fail("worksite_block_changed");
            return;
        }
        if (phaseTicks > 300) { fail("shell_break_timeout"); return; }
        if (breakFuture == null) {
            breakFuture = BaritoneCompat.breakBlock(worksite[0], worksite[1], worksite[2], true);
            return;
        }
        if (breakFuture.isDone() && !breakFuture.getNow()) fail("shell_break_rejected");
    }

    private void tickPickup() {
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = inventoryCache.getMouseStack();
        Set<Integer> matching = matchingBoxSlots(expectedAfter);
        Set<Integer> newlyMatching = new HashSet<>(matching);
        newlyMatching.removeAll(matchingBoxSlotsBefore);
        int candidate = matching.size() == matchingBoxCountBefore + 1
                && newlyMatching.size() == 1
                && inventoryCache.getOpenContainerId() == 0
                && (cursor == null || cursor.getAmount() <= 0)
                && !isShulkerAtWorksite()
                ? newlyMatching.iterator().next() : -1;
        if (candidate >= 0) {
            if (candidate != pickupCandidateSlot) {
                pickupCandidateSlot = candidate;
                pickupCandidateTicks = 1;
            } else {
                pickupCandidateTicks++;
            }
            if (pickupCandidateTicks >= PICKUP_CONFIRM_TICKS) {
                recoveredSlot = candidate;
                BARITONE.stop();
                restoreSneak();
                restoreBreaking();
                advance(State.DONE);
                emit("kit_pack_verified", Map.of("item_types", expectedAfter.size(),
                        "recovered_slot", recoveredSlot,
                        "stable_ticks", pickupCandidateTicks));
                return;
            }
        } else {
            pickupCandidateSlot = -1;
            pickupCandidateTicks = 0;
        }
        if (phaseTicks > 900) { fail("packed_shell_pickup_timeout"); return; }
        boolean pathActive = BARITONE.getCustomGoalProcess().isActive();
        int[] observed = observedDropGoal();
        if (observed != null) {
            boolean moved = !java.util.Arrays.equals(observed, lastPickupTarget);
            if (!moved && !ShulkerPickupSweep.shouldIssuePath(
                    phaseTicks, lastPickupPathTick, pathActive)) return;
            if (moved && pathActive) BARITONE.stop();
            lastPickupPathTick = phaseTicks;
            lastPickupTarget = observed;
            BARITONE.pathTo(new GoalBlock(new BlockPos(
                    observed[0], observed[1], observed[2])));
            if (moved) emit("kit_pack_drop_targeted", Map.of(
                    "position", posString(observed)));
            return;
        }
        if (!ShulkerPickupSweep.shouldIssuePath(
                phaseTicks, lastPickupPathTick, pathActive)) return;
        for (int checked = 0; checked < ShulkerPickupSweep.targetCount(); checked++) {
            int[] target = ShulkerPickupSweep.target(worksite, pickupPathAttempt++);
            if (!safePickupStandingCell(target)) continue;
            lastPickupPathTick = phaseTicks;
            lastPickupTarget = target;
            BARITONE.pathTo(new GoalBlock(new BlockPos(
                    target[0], target[1], target[2])));
            return;
        }
        lastPickupPathTick = phaseTicks;
    }

    private int[] observedDropGoal() {
        if (shellItem == null || worksite == null) return null;
        List<KitDropGoalPolicy.Drop> drops = new ArrayList<>();
        for (var entry : CACHE.getEntityCache().getEntities().entrySet()) {
            var entity = entry.getValue();
            if (entity == null || entity.isRemoved()
                    || entity.getEntityType() != EntityType.ITEM) continue;
            var metadata = entity.getMetadata().get(8);
            if (metadata == null || !(metadata.getValue() instanceof ItemStack stack)
                    || stack.getAmount() != 1) continue;
            drops.add(new KitDropGoalPolicy.Drop(entry.getKey(), stack.getId(),
                    ItemIdentifier.readShulkerContents(stack),
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return KitDropGoalPolicy.target(drops, itemEntitiesBeforeBreak,
                shellItem.id(), expectedAfter, worksite, KitPacker::safePickupStandingCell);
    }

    private static boolean safePickupStandingCell(int[] target) {
        return target != null && World.isInWorldBounds(target[0], target[1], target[2])
                && World.isChunkLoadedBlockPos(target[0], target[2])
                && World.getChunkSection(target[0], target[1], target[2]) != null
                && BlockCompat.canReplace(World.getBlock(target[0], target[1], target[2]))
                && BlockCompat.canReplace(World.getBlock(target[0], target[1] + 1, target[2]))
                && BlockCompat.isSolid(target[0], target[1] - 1, target[2]);
    }

    private void fail(String reason) {
        failureReason = reason;
        BARITONE.stop();
        var cache = CACHE.getPlayerCache().getInventoryCache();
        int openId = cache.getOpenContainerId();
        Container currentOpen = cache.getOpenContainer();
        boolean recognizedShell = openId > 0 && openId == contentContainerId
                && currentOpen != null && currentOpen.getContainerId() == openId
                && containerSlots(currentOpen) == BOX_SLOTS && isShulkerAtWorksite();
        if (openId > 0 && (openId == openContainerId || recognizedShell)
                && Proxy.getInstance().isConnected()
                && !Proxy.getInstance().hasActivePlayer()) {
            try {
                INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(6000)
                        .actions(new CloseContainer(openId)).build());
            } catch (RuntimeException ignored) {
                // Recovery owns the checkpoint even if the close request was rejected.
            }
        }
        restoreSneak();
        restoreBreaking();
        restoreRelocationPlace();
        state = State.FAILED;
        emit("kit_pack_failed", Map.of("reason", reason,
                "worksite", worksite == null ? "none" : posString(worksite),
                "box_in_world", worksite != null && isShulkerAtWorksite()));
    }

    private void advance(State next) {
        state = next;
        phaseTicks = 0;
        if (next == State.BREAKING) {
            itemEntitiesBeforeBreak = Set.copyOf(CACHE.getEntityCache().getEntities().keySet());
            lastPickupTarget = null;
        }
        if (next == State.OPENING) {
            BARITONE.stop();
            openGate.reset();
            openingMismatchTicks = 0;
            contentContainerId = -1;
            openFuture = null;
            lastOpenClickTick = -1;
        }
        if (next == State.CLOSING) {
            BARITONE.stop();
            closeClearTicks = 0;
        }
        emit("kit_pack_phase", Map.of("phase", next.name()));
    }

    private void setSneak(boolean sneak) {
        if (!sneakGuard) {
            savedSneak = PathfinderCompat.getPlaceBlockSneak();
            sneakGuard = true;
        }
        PathfinderCompat.setPlaceBlockSneak(sneak);
    }

    private void restoreSneak() {
        if (!sneakGuard) return;
        PathfinderCompat.setPlaceBlockSneak(savedSneak);
        sneakGuard = false;
    }

    private void restoreBreaking() {
        if (!breakingGuard) return;
        CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
        breakingGuard = false;
    }

    private void restoreRelocationPlace() {
        if (!relocationPlaceGuard) return;
        CONFIG.client.extra.pathfinder.allowPlace = savedAllowPlace;
        relocationPlaceGuard = false;
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

    private static int containerSlots(Container open) { return Math.max(0, open.getSize() - 36); }
    private static int firstEmptyBoxSlot(Container open) {
        return firstEmptyBoxSlot(BOX_SLOTS, slot -> {
            ItemStack stack = open.getItemStack(slot);
            return stack != null && stack.getAmount() > 0;
        });
    }

    static int firstEmptyBoxSlot(int boxSlots, IntPredicate occupied) {
        for (int slot = 0; slot < boxSlots; slot++) {
            if (!occupied.test(slot)) return slot;
        }
        return -1;
    }

    private static int emptyBoxSlots(Container open) {
        int empty = 0;
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) empty++;
        }
        return empty;
    }

    private static int exactStackHeadroom(Container open, ItemStack incoming) {
        ItemData data = ItemRegistry.REGISTRY.get(incoming.getId());
        int max = data == null ? 1 : Math.max(1, data.stackSize());
        int available = 0;
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (sameStack(stack, incoming)) available += Math.max(0, max - stack.getAmount());
        }
        return available;
    }

    /** Stack components, not just recipe item IDs, determine physical box capacity. */
    static int physicalRequiredSlots(List<ItemStack> stacks, IntUnaryOperator maxStackSize) {
        record Key(int id, Object components) { }
        Map<Key, Integer> totals = new HashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getAmount() <= 0) continue;
            totals.merge(new Key(stack.getId(), stack.getDataComponents()),
                    stack.getAmount(), Integer::sum);
        }
        int slots = 0;
        for (var entry : totals.entrySet()) {
            int max = Math.max(1, maxStackSize.applyAsInt(entry.getKey().id()));
            slots += (entry.getValue() + max - 1) / max;
        }
        return slots;
    }

    static CompactionPlan findCompactionPlan(Container open) {
        CompactionPlan partial = null;
        for (int source = 0; source < BOX_SLOTS; source++) {
            ItemStack sourceStack = open.getItemStack(source);
            if (sourceStack == null || sourceStack.getAmount() <= 0) continue;
            ItemData data = ItemRegistry.REGISTRY.get(sourceStack.getId());
            int max = data == null ? 1 : Math.max(1, data.stackSize());
            if (max <= 1 || sourceStack.getAmount() >= max) continue;
            for (int target = 0; target < BOX_SLOTS; target++) {
                if (target == source) continue;
                ItemStack targetStack = open.getItemStack(target);
                if (!sameStack(sourceStack, targetStack) || targetStack.getAmount() >= max) continue;
                if (sourceStack.getAmount() > targetStack.getAmount()) continue;
                CompactionPlan plan = new CompactionPlan(source, target,
                        sourceStack.getId(), sourceStack.getDataComponents(),
                        sourceStack.getAmount(), targetStack.getAmount(), max);
                if (plan.sourceAfter() == 0) return plan;
                if (partial == null) partial = plan;
            }
        }
        return partial;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return first != null && second != null && first.getAmount() > 0 && second.getAmount() > 0
                && first.getId() == second.getId()
                && Objects.equals(first.getDataComponents(), second.getDataComponents());
    }

    static int uniqueIncompatibleAppleSlot(Container open, ItemStack compatible,
                                            int requiredAmount) {
        if (open == null || compatible == null || requiredAmount < 1) return -1;
        int found = -1;
        int total = 0;
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || !"enchanted_golden_apple".equals(
                    ItemIdentifier.getItemId(stack))
                    || KitAppleExchangePlan.samePhysicalStack(stack, compatible)) continue;
            if (found >= 0) return -1;
            found = slot;
            total += stack.getAmount();
        }
        return total == requiredAmount ? found : -1;
    }

    static int firstIncompatibleAppleSlot(Container open, ItemStack compatible,
                                                  int remainingAmount) {
        if (open == null || compatible == null || remainingAmount < 1) return -1;
        int total = 0;
        int first = -1;
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || !"enchanted_golden_apple".equals(
                    ItemIdentifier.getItemId(stack))
                    || KitAppleExchangePlan.samePhysicalStack(stack, compatible)) continue;
            if (first < 0) first = slot;
            total += stack.getAmount();
        }
        return total == remainingAmount ? first : -1;
    }

    private static int firstEmptyPlayerRawSlot(Container open, Set<Integer> protectedSlots) {
        for (int raw = 9; raw <= 35; raw++) {
            if (protectedSlots.contains(raw)) continue;
            ItemStack stack = open.getItemStack(rawToWindow(raw));
            if (stack == null || stack.getAmount() <= 0) return raw;
        }
        return -1;
    }

    record CompactionPlan(int sourceSlot, int targetSlot, int itemId,
                          org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents components,
                          int sourceBefore, int targetBefore, int maxStack) {
        int sourceAfter() { return Math.max(0, sourceBefore - (maxStack - targetBefore)); }
        int targetAfter() { return Math.min(maxStack, sourceBefore + targetBefore); }

        boolean confirmed(Container open) {
            ItemStack source = open.getItemStack(sourceSlot);
            ItemStack target = open.getItemStack(targetSlot);
            boolean sourceMatches = sourceAfter() == 0
                    ? source == null || source.getAmount() <= 0
                    : source != null && source.getId() == itemId
                            && Objects.equals(source.getDataComponents(), components)
                            && source.getAmount() == sourceAfter();
            return sourceMatches && target != null && target.getId() == itemId
                    && Objects.equals(target.getDataComponents(), components)
                    && target.getAmount() == targetAfter();
        }
    }
    private static int rawToWindow(int raw) {
        return raw < 36 ? BOX_SLOTS + raw - 9 : BOX_SLOTS + 27 + raw - 36;
    }

    private static Map<String, Integer> contents(Container open) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                items.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
            }
        }
        return items;
    }

    private static Map<String, Integer> positiveCopy(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source != null) source.forEach((id, count) -> {
            if (id != null && count != null && count > 0) result.put(id, count);
        });
        return result;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack != null && stack.getAmount() > 0
                && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack)).contains("shulker_box");
    }

    private static ItemStack playerStack(int raw) {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return player == null ? null : player.getItemStack(raw);
    }

    private static int firstFreeHotbar(Container player, Set<Integer> protectedSlots) {
        for (int raw = 36; raw <= 44; raw++) {
            if (protectedSlots.contains(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) return raw;
        }
        return -1;
    }

    private static int countMatchingBoxes(Map<String, Integer> contents) {
        int count = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = playerStack(raw);
            if (isShulker(stack) && ItemIdentifier.readShulkerContents(stack).equals(contents)) count++;
        }
        return count;
    }

    private static Set<Integer> matchingBoxSlots(Map<String, Integer> contents) {
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = playerStack(raw);
            if (isShulker(stack) && ItemIdentifier.readShulkerContents(stack).equals(contents)) {
                slots.add(raw);
            }
        }
        return Set.copyOf(slots);
    }

    private boolean isShulkerAtWorksite() {
        return worksite != null && World.getBlock(worksite[0], worksite[1], worksite[2])
                .name().contains("shulker_box");
    }

    private static int[] findSafeSpot() {
        var player = CACHE.getPlayerCache();
        int baseX = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int baseZ = (int) Math.floor(player.getZ());
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int[] candidate = {baseX + dx, y, baseZ + dz};
                    if (isSafeSpot(candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isSafeSpot(int[] pos) {
        if (pos == null || !World.isInWorldBounds(pos[0], pos[1], pos[2])) return false;
        var player = CACHE.getPlayerCache();
        if (!ContainerApproach.isWithinVanillaReach(
                player.getX(), player.getY(), player.getZ(), pos[0], pos[1], pos[2])) return false;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        if (px + 0.3 > pos[0] && px - 0.3 < pos[0] + 1
                && py + 1.8 > pos[1] && py < pos[1] + 1
                && pz + 0.3 > pos[2] && pz - 0.3 < pos[2] + 1) return false;
        if (!BlockCompat.canReplace(World.getBlock(pos[0], pos[1], pos[2]))
                || !BlockCompat.canReplace(World.getBlock(pos[0], pos[1] + 1, pos[2]))
                || !BlockCompat.isSolid(pos[0], pos[1] - 1, pos[2])) return false;
        int[][] neighbors = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
        for (int[] n : neighbors) {
            if (BlockCompat.isInteractable(World.getBlock(
                    pos[0] + n[0], pos[1] + n[1], pos[2] + n[2]))) return false;
        }
        return true;
    }

    private static String posString(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }

    private void emit(String name, Map<String, Object> fields) {
        if (events != null) events.accept(name, fields);
    }
}
