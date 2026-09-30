package com.zenith.plugin.stashmanager.retriever;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.organizer.lane.FifoLane;
import com.zenith.plugin.stashmanager.organizer.lane.LaneDetector;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.PathfinderCompat;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.BiConsumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

// Retrieves requested items from candidate containers.
public final class StashRetriever {

    public enum State {
        IDLE,
        WALKING,
        OPENING,
        TAKING,
        UNLOADING_SHULKER,
        DONE
    }

    /** Explains why a retrieval could not enter its first state. */
    public enum StartFailureReason {
        NONE(false),
        EMPTY_REQUEST(false),
        ALREADY_ACTIVE(true),
        BOT_DISCONNECTED(true),
        PROXY_IN_USE(true),
        NO_MATCHES(false);

        private final boolean retryable;

        StartFailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static final int OPEN_TIMEOUT_TICKS = 60;
    // Match Zenith's default 5-tick click cadence; faster submissions are rejected.
    private static final int CLICK_COOLDOWN_TICKS = 6;
    private static final int WALK_TIMEOUT_TICKS = 400;
    private static final int MAX_CONSECUTIVE_FAILURES = 4;
    private static final int FOOD_REACH_FALLBACK_STILL_TICKS = 40;

    // Nested retrieval has to place, open, empty, break, and recover a borrowed box. A
    // ten-second budget was shorter than a single difficult Baritone approach in large stashes.
    private static final int SHULKER_TOTAL_TIMEOUT_TICKS = 2400;
    private static final int SHULKER_PLACE_TIMEOUT_TICKS = 400;
    private static final int SHULKER_OPEN_TIMEOUT_TICKS = 200;
    private static final int SHULKER_BREAK_TIMEOUT_TICKS = 300;
    private static final int MAX_SHULKER_PLACE_REPLANS = 4;
    private static final int SHULKER_PICKUP_WAIT_TICKS = 16;
    private static final int SHULKER_SEARCH_SETTLE_TICKS = 4;
    private static final int SHULKER_PLACE_RETRY_DELAY_TICKS = 10;
    private static final int SHULKER_HELD_RESELECT_TICKS = 40;
    private static final int MAX_SHULKER_HELD_RESELECTS = 5;
    private static final int TELEPORT_CALM_TICKS = 6;
    private static final int SHULKER_HOTBAR_SLOT = 6;

    private State state = State.IDLE;
    private String activeRequestName;
    private StartFailureReason lastStartFailureReason = StartFailureReason.NONE;

    private final Deque<int[]> targetQueue = new ArrayDeque<>();
    private int[] currentTarget;
    private final Map<String, Integer> remaining = new LinkedHashMap<>();
    private int[] activeRegionMin;
    private int[] activeRegionMax;
    private final Set<Long> excludedTargets = new HashSet<>();

    private int openWaitTicks;
    private int actionCooldown;
    private int actionSlotIndex;
    private int walkingTicks;
    private int stationaryWalkTicks;
    private double lastWalkX, lastWalkY, lastWalkZ;
    private boolean preferNearbyFoodTargets;
    private boolean foodPathRearmed;
    private int consecutiveFailures;
    private int initialRequestedTotal;
    private int successfulTransfers;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile ItemStack[] containerSlots;
    private volatile Session serverSession;

    private int unloadPhase;
    private int unloadTicks;
    private int unloadTotalTicks;
    private int unloadShulkerSlot = -1;
    private int unloadChestSlot = -1;
    private int[] placedShulkerPos;
    private boolean savedPlaceBlockSneak = false;
    private boolean placeSneakGuardActive = false;
    private ItemData unloadShulkerItemData;
    private PathingRequestFuture unloadPlaceFuture;
    private PathingRequestFuture unloadBreakFuture;
    private RequestFuture unloadHotbarRequest;
    private int unloadHotbarRawSlot = -1;
    private int shulkerInventorySearchDelay;
    private int shulkerOpenRetries;
    private int shulkerPlaceReplans;
    private int shulkerHeldReselects;
    private final Set<Long> rejectedShulkerPlacePositions = new HashSet<>();

    private int lastTeleportQueueSize = -1;
    private int ticksSinceTeleportQueueChange;

    // Split-take state (partial stack retrieval)
    private boolean splitInProgress;
    private int splitSrcSlot = -1;
    private int splitPutbacksLeft;
    private int splitNeeded;
    private boolean splitCursorReady;
    private String splitItemId;

    // Owned shulker tracking (shulkers taken for their contents)
    private final Set<Integer> ownedShulkerSlots = new HashSet<>();
    private String pendingOwnedShulkerFingerprint;
    private final Set<Integer> pendingOwnedShulkerCandidateSlots = new HashSet<>();

    private BiConsumer<String, Map<String, Object>> eventCallback;

    public StashRetriever() {}

    public void setEventCallback(BiConsumer<String, Map<String, Object>> eventCallback) {
        this.eventCallback = eventCallback;
    }

    public State getState() {
        return state;
    }

    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    public String getActiveRequestName() {
        return activeRequestName;
    }

    public StartFailureReason getLastStartFailureReason() {
        return lastStartFailureReason;
    }

    public int getRemainingTotal() {
        return remaining.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> getRemainingItems() {
        return Map.copyOf(remaining);
    }

    public String getStatus() {
        return switch (state) {
            case IDLE -> "Idle";
            case WALKING -> "Walking to container";
            case OPENING -> "Opening container";
            case TAKING -> "Taking matching items";
            case UNLOADING_SHULKER -> "Unloading nested shulker";
            case DONE -> "Done";
        };
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates) {
        return startKit(requestName, kitItems, candidates, null, null, Set.of());
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions) {
        return startKit(requestName, kitItems, candidates,
                regionPos1, regionPos2, excludedPositions, false);
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets) {
        lastStartFailureReason = StartFailureReason.NONE;
        if (kitItems == null || kitItems.isEmpty()) {
            lastStartFailureReason = StartFailureReason.EMPTY_REQUEST;
            return false;
        }
        if (isActive()) {
            lastStartFailureReason = StartFailureReason.ALREADY_ACTIVE;
            return false;
        }

        var proxy = Proxy.getInstance();
        if (!proxy.isConnected()) {
            lastStartFailureReason = StartFailureReason.BOT_DISCONNECTED;
            return false;
        }
        if (proxy.hasActivePlayer()) {
            lastStartFailureReason = StartFailureReason.PROXY_IN_USE;
            return false;
        }

        resetState();
        activeRequestName = requestName;
        this.preferNearbyFoodTargets = preferNearbyFoodTargets;
        setTargetPolicy(regionPos1, regionPos2, excludedPositions);
        kitItems.forEach((k, v) -> {
            if (v != null && v > 0) remaining.put(k, v);
        });

        if (remaining.isEmpty()) {
            lastStartFailureReason = StartFailureReason.EMPTY_REQUEST;
            state = State.DONE;
            emit("retrieve_no_targets", Map.of("reason", "empty_request"));
            return false;
        }

        initialRequestedTotal = getRemainingTotal();

        // Prefer a FIFO lane's output chest (oldest stock) over its input chest.
        Set<Long> laneOutputKeys = new HashSet<>();
        for (FifoLane lane : LaneDetector.detectLanes(candidates)) {
            laneOutputKeys.add(posKey(lane.outputPos()[0], lane.outputPos()[1], lane.outputPos()[2]));
        }

        List<ContainerEntry> sorted = new ArrayList<>(candidates);
        Comparator<ContainerEntry> normalOrder = Comparator
            .comparingInt((ContainerEntry e) -> -matchScore(e))
            .thenComparingInt(e -> directMatchScore(e) > 0 ? 0 : 1)
            .thenComparingInt(e -> laneOutputKeys.contains(posKey(e.x(), e.y(), e.z())) ? 0 : 1)
            .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z()));
        if (preferNearbyFoodTargets) {
            var player = CACHE.getPlayerCache();
            sorted.sort(Comparator
                    .comparingInt((ContainerEntry e) -> foodAccessBand(
                            player.getX(), player.getY(), player.getZ(), e))
                    .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z()))
                    .thenComparing(normalOrder));
        } else {
            sorted.sort(normalOrder);
        }

        for (ContainerEntry entry : sorted) {
            if (matchScore(entry) <= 0) continue;
            if (!isAllowedTarget(entry.x(), entry.y(), entry.z())) continue;
            targetQueue.add(new int[]{entry.x(), entry.y(), entry.z()});
        }

        // A restart can occur after a nested shulker was borrowed but before its contents were
        // extracted. Recover from the live inventory first instead of borrowing a duplicate.
        int inventoryShulkerSlot = findInventoryShulkerWithWantedContents();
        if (targetQueue.isEmpty() && inventoryShulkerSlot < 0) {
            lastStartFailureReason = StartFailureReason.NO_MATCHES;
            state = State.DONE;
            emit("retrieve_no_targets", Map.of(
                "reason", "no_matches",
                "total_requested", initialRequestedTotal,
                "unique_items", remaining.size()
            ));
            return false;
        }

        emit("retrieve_started", Map.of(
            "total_requested", initialRequestedTotal,
            "unique_items", remaining.size(),
            "candidate_targets", targetQueue.size(),
            "inventory_shulker_available", inventoryShulkerSlot >= 0
        ));

        BARITONE.stop();
        if (inventoryShulkerSlot >= 0) {
            ItemStack inventoryShulker = getPlayerInventoryStack(inventoryShulkerSlot);
            ownedShulkerSlots.add(inventoryShulkerSlot);
            emit("retrieve_inventory_shulker_selected", Map.of(
                "inventory_slot", inventoryShulkerSlot,
                "reason", "wanted_contents_already_in_inventory"
            ));
            beginShulkerUnload(-1, inventoryShulker);
            return true;
        }
        advanceToNextTarget(null);
        return true;
    }

    public void stop() {
        if (isActive()) {
            emit("retrieve_stopped", Map.of(
                "reason", "manual_stop",
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        }
        BARITONE.stop();
        closeCurrentContainer();
        state = State.IDLE;
        targetQueue.clear();
        currentTarget = null;
        activeRequestName = null;
    }

    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        this.serverSession = session;
        this.openContainerId = packet.getContainerId();
        this.containerSlots = packet.getItems();
        this.containerDataReceived = true;
    }

    public void tick() {
        if (state == State.IDLE || state == State.DONE) return;

        updateTeleportStability();

        switch (state) {
            case WALKING -> tickWalking();
            case OPENING -> tickOpening();
            case TAKING -> tickTaking();
            case UNLOADING_SHULKER -> tickUnloadingShulker();
            default -> {
            }
        }
    }

    private void tickWalking() {
        if (currentTarget == null) {
            finish(false, "missing_target");
            return;
        }

        walkingTicks++;
        var player = CACHE.getPlayerCache();
        double movedSquared = Math.pow(player.getX() - lastWalkX, 2)
                + Math.pow(player.getY() - lastWalkY, 2)
                + Math.pow(player.getZ() - lastWalkZ, 2);
        stationaryWalkTicks = movedSquared < 0.0025 ? stationaryWalkTicks + 1 : 0;
        lastWalkX = player.getX();
        lastWalkY = player.getY();
        lastWalkZ = player.getZ();

        double dist = distanceTo(currentTarget[0], currentTarget[1], currentTarget[2]);
        if (isAtTargetAccessPosition()) {
            beginOpeningTarget();
            return;
        }
        if (preferNearbyFoodTargets
                && stationaryWalkTicks >= FOOD_REACH_FALLBACK_STILL_TICKS
                && ContainerApproach.isWithinVanillaReach(
                        player.getX(), player.getY(), player.getZ(),
                        currentTarget[0], currentTarget[1], currentTarget[2])) {
            emit("retrieve_food_reach_fallback", Map.of(
                    "reason", "path_stalled_within_vanilla_reach",
                    "stationary_ticks", stationaryWalkTicks,
                    "distance", String.format("%.1f", dist)));
            beginOpeningTarget();
            return;
        }
        if (preferNearbyFoodTargets
                && stationaryWalkTicks >= FOOD_REACH_FALLBACK_STILL_TICKS
                && !foodPathRearmed) {
            foodPathRearmed = true;
            BARITONE.stop();
            emit("retrieve_food_path_rearmed", Map.of(
                    "reason", "no_movement_after_path_request",
                    "stationary_ticks", stationaryWalkTicks,
                    "distance", String.format("%.1f", dist),
                    "feet_block", blockNameAtPlayer(0),
                    "floor_block", blockNameAtPlayer(-1)));
            pathToTarget();
            return;
        }

        if (walkingTicks > WALK_TIMEOUT_TICKS) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                emit("retrieve_target_failed", Map.of(
                    "failure_reason", "walk_timeout",
                    "distance", String.format("%.1f", dist)
                ));
                finish(false, "too_many_failures");
                return;
            }
            advanceToNextTarget("walk_timeout");
            return;
        }

        if (!BARITONE.getCustomGoalProcess().isActive()) {
            pathToTarget();
        }
    }

    private void beginOpeningTarget() {
        BARITONE.stop();
        state = State.OPENING;
        containerOpenGate.reset();
        openWaitTicks = 0;
        containerDataReceived = false;
    }

    private String blockNameAtPlayer(int yOffset) {
        var player = CACHE.getPlayerCache();
        var block = World.getBlock(
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()) + yOffset,
                (int) Math.floor(player.getZ()));
        return block == null ? "unknown" : block.name();
    }

    private void tickOpening() {
        if (containerDataReceived) {
            state = State.TAKING;
            actionSlotIndex = 0;
            actionCooldown = 0;
            emit("retrieve_target_opened", Map.of());
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > OPEN_TIMEOUT_TICKS) {
            consecutiveFailures++;
            advanceToNextTarget("open_timeout");
            return;
        }

        // Retry missed opens instead of failing the target.
        if (openWaitTicks == 1 || openWaitTicks % 10 == 0) {
            interactWithTarget();
        }
    }

    private void tickTaking() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // Finish the current split before taking another slot.
        if (splitInProgress) {
            if (tickSplitTake()) {
                actionCooldown = CLICK_COOLDOWN_TICKS;
                return;
            }
        }

        if (containerSlots == null || openContainerId < 0) {
            consecutiveFailures++;
            advanceToNextTarget("container_sync_failed");
            return;
        }

        int chestSlots = getOpenContainerSlotCount();
        resolvePendingOwnedShulker();
        if (returnFinishedOwnedShulker(chestSlots)) {
            actionCooldown = CLICK_COOLDOWN_TICKS;
            return;
        }

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                String requestKey = requestKeyForItem(remaining, itemId);
                boolean wantedDirectly = requestKey != null;
                boolean wantedForContents = !wantedDirectly && containsWantedContents(stack);
                Integer needed = requestKey == null ? null : remaining.get(requestKey);

                if ((wantedDirectly || wantedForContents) && hasInventoryRoom(stack)) {
                    
                    // Split stacks larger than the remaining request.
                    if (wantedDirectly && !wantedForContents && needed != null && needed > 0 && stack.getAmount() > needed) {
                        beginSplitTake(actionSlotIndex, requestKey, stack.getAmount(), needed);
                        actionSlotIndex++;
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }

                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }
                    successfulTransfers++;
                    actionSlotIndex++;
                    actionCooldown = CLICK_COOLDOWN_TICKS;

                    if (wantedForContents) {
                        // Track shulkers borrowed for unloading.
                        beginTrackingOwnedShulker(stack, chestSlots);
                        
                        int[] revisitTarget = currentTarget == null ? null : currentTarget.clone();
                        closeCurrentContainer();
                        if (revisitTarget != null) {
                            targetQueue.addFirst(revisitTarget);
                        }
                        beginShulkerUnload(actionSlotIndex - 1, stack);
                        return;
                    }

                    if (needed != null && needed > 0) {
                        remaining.put(requestKey, Math.max(0, needed - stack.getAmount()));
                    }

                    if (successfulTransfers % 5 == 0) {
                        emit("retrieve_progress", Map.of(
                            "moved_stacks", successfulTransfers,
                            "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                        ));
                    }

                    if (isComplete()) {
                        finish(true, "complete");
                    }
                    return;
                }
            }
            actionSlotIndex++;
        }

        consecutiveFailures = 0;
        advanceToNextTarget("container_exhausted");
    }

    private void beginShulkerUnload(int chestSlot, ItemStack shulkerStack) {
        // The container approach may still own a path request when a within-reach open succeeds.
        // Do not let that request carry the bot away after we select a local placement cell.
        BARITONE.stop();
        unloadPhase = 0;
        unloadTicks = 0;
        unloadTotalTicks = 0;
        unloadChestSlot = chestSlot;
        unloadShulkerSlot = -1;
        placedShulkerPos = null;
        unloadShulkerItemData = ItemRegistry.REGISTRY.get(shulkerStack.getId());
        unloadPlaceFuture = null;
        unloadBreakFuture = null;
        unloadHotbarRequest = null;
        unloadHotbarRawSlot = -1;
        shulkerInventorySearchDelay = SHULKER_SEARCH_SETTLE_TICKS;
        shulkerOpenRetries = 0;
        shulkerPlaceReplans = 0;
        shulkerHeldReselects = 0;
        rejectedShulkerPlacePositions.clear();
        state = State.UNLOADING_SHULKER;
        emit("retrieve_shulker_unload_started", Map.of(
            "source_container_slot", chestSlot,
            "shulker_item_id", itemIdFromStack(shulkerStack)
        ));
    }

    private void tickUnloadingShulker() {
        unloadTicks++;
        unloadTotalTicks++;

        if (unloadTotalTicks > SHULKER_TOTAL_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", "timeout",
                "phase", unloadPhase,
                "phase_ticks", unloadTicks,
                "place_replans", shulkerPlaceReplans
            ));
            finish(false, "shulker_unload_timeout");
            return;
        }

        // Sneak so right-click places the shulker instead of opening a nearby container.
        setPlaceBlockSneak(unloadPhase == 1);

        switch (unloadPhase) {
            case 0 -> tickUnloadLocateAndPrepare();
            case 1 -> tickUnloadPlace();
            case 2 -> tickUnloadOpen();
            case 3 -> tickUnloadTakeContents();
            case 4 -> tickUnloadBreak();
            case 5 -> tickUnloadResume();
            default -> {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "invalid_phase"));
                finish(false, "invalid_shulker_phase");
            }
        }
    }

    private void setPlaceBlockSneak(boolean sneak) {
        if (!placeSneakGuardActive) {
            savedPlaceBlockSneak = PathfinderCompat.getPlaceBlockSneak();
            placeSneakGuardActive = true;
        }
        PathfinderCompat.setPlaceBlockSneak(sneak);
    }

    private void restorePlaceBlockSneak() {
        if (!placeSneakGuardActive) return;
        PathfinderCompat.setPlaceBlockSneak(savedPlaceBlockSneak);
        placeSneakGuardActive = false;
    }

    private void tickUnloadLocateAndPrepare() {
        if (shulkerInventorySearchDelay > 0) {
            shulkerInventorySearchDelay--;
            return;
        }

        resolvePendingOwnedShulker();

        if (unloadShulkerSlot < 0) {
            unloadShulkerSlot = findWantedShulkerSlot();
            if (unloadShulkerSlot < 0) {
                return;
            }
        }

        if (placedShulkerPos == null) {
            placedShulkerPos = findShulkerPlaceSpot();
            if (placedShulkerPos == null) {
                retryShulkerPlacement("no_place_spot");
                return;
            }
        }

        if (unloadShulkerItemData == null) {
            ItemStack stack = getPlayerInventoryStack(unloadShulkerSlot);
            if (stack == null || stack.getAmount() <= 0) {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_shulker_stack"));
                finish(false, "missing_shulker_stack");
                return;
            }
            unloadShulkerItemData = ItemRegistry.REGISTRY.get(stack.getId());
        }

        if (unloadShulkerItemData == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_shulker_item_data"));
            finish(false, "missing_shulker_item_data");
            return;
        }

        if (unloadHotbarRequest == null) {
            if (!moveShulkerToHotbar(unloadShulkerSlot)) {
                emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "hotbar_transfer_rejected"
                ));
                finish(false, "shulker_hotbar_transfer_rejected");
            }
            unloadTicks = 0;
            return;
        }
        if (!unloadHotbarRequest.isCompleted()) return;
        if (!unloadHotbarRequest.getNow()) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", "hotbar_transfer_rejected"
            ));
            finish(false, "shulker_hotbar_transfer_rejected");
            return;
        }
        if (!unloadShulkerReadyForPlacement()) {
            if (unloadTicks >= SHULKER_HELD_RESELECT_TICKS
                    && shulkerHeldReselects < MAX_SHULKER_HELD_RESELECTS) {
                shulkerHeldReselects++;
                // A completed SetHeldItem can still be superseded by another automation owner.
                // Re-issue selection against the live hotbar slot without moving the box again.
                unloadShulkerSlot = unloadHotbarRawSlot;
                unloadHotbarRequest = null;
                unloadTicks = 0;
                emit("retrieve_shulker_held_reselected", Map.of(
                    "attempt", shulkerHeldReselects,
                    "max_attempts", MAX_SHULKER_HELD_RESELECTS,
                    "expected_hotbar_slot", unloadHotbarRawSlot,
                    "held_hotbar_index", CACHE.getPlayerCache().getHeldItemSlot()
                ));
                return;
            }
            if (unloadTicks > SHULKER_PLACE_TIMEOUT_TICKS) {
                emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "hotbar_transfer_unverified",
                    "expected_hotbar_slot", unloadHotbarRawSlot,
                    "held_hotbar_index", CACHE.getPlayerCache().getHeldItemSlot()
                ));
                finish(false, "shulker_hotbar_transfer_unverified");
            }
            return;
        }
        unloadPhase = 1;
        unloadTicks = 0;
    }

    private void tickUnloadPlace() {
        if (placedShulkerPos == null || unloadShulkerItemData == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "place_state_missing"));
            finish(false, "place_state_missing");
            return;
        }

        if (World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]).name().contains("shulker_box")) {
            emit("retrieve_shulker_placed", Map.of(
                "placed_position", posString(placedShulkerPos)
            ));
            unloadPhase = 2;
            containerOpenGate.reset();
            unloadTicks = 0;
            unloadPlaceFuture = null;
            return;
        }

        // Revalidate immediately before placement. The bot can still receive movement/teleport
        // corrections after opening the source, making the previously local cell stale.
        if (!isSafeShulkerPlaceSpot(placedShulkerPos)
                || !isWithinShulkerPlacementReach(placedShulkerPos)) {
            retryShulkerPlacement("placement_cell_stale");
            return;
        }

        if (unloadTicks > SHULKER_PLACE_TIMEOUT_TICKS) {
            retryShulkerPlacement("place_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadPlaceFuture == null) {
            unloadPlaceFuture = BaritoneCompat.placeBlock(
                placedShulkerPos[0],
                placedShulkerPos[1],
                placedShulkerPos[2],
                unloadShulkerItemData
            );
            return;
        }

        if (unloadPlaceFuture.isDone() && !unloadPlaceFuture.getNow()) {
            retryShulkerPlacement("place_rejected");
        }
    }

    private void retryShulkerPlacement(String reason) {
        BARITONE.stop();
        if (placedShulkerPos != null) {
            // A rejected cell will still look replaceable in the world snapshot. Remember it
            // for this borrowed box so each replan actually tries a different local worksite.
            rejectedShulkerPlacePositions.add(posKey(
                    placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]));
        }
        shulkerPlaceReplans++;
        if (shulkerPlaceReplans > MAX_SHULKER_PLACE_REPLANS) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", reason,
                "phase", unloadPhase,
                "place_replans", shulkerPlaceReplans
            ));
            finish(false, "shulker_" + reason);
            return;
        }

        emit("retrieve_shulker_place_replanned", Map.of(
            "reason", reason,
            "attempt", shulkerPlaceReplans,
            "max_attempts", MAX_SHULKER_PLACE_REPLANS
        ));
        placedShulkerPos = null;
        unloadPlaceFuture = null;
        unloadPhase = 0;
        unloadTicks = 0;
        shulkerInventorySearchDelay = SHULKER_PLACE_RETRY_DELAY_TICKS;
    }

    private void tickUnloadOpen() {
        if (placedShulkerPos == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_placed_position"));
            finish(false, "missing_placed_position");
            return;
        }

        if (containerDataReceived && openContainerId >= 0) {
            emit("retrieve_shulker_opened", Map.of(
                "placed_position", posString(placedShulkerPos)
            ));
            unloadPhase = 3;
            unloadTicks = 0;
            actionCooldown = 0;
            actionSlotIndex = 0;
            return;
        }

        if (!prepareStandingContainerInteraction()) {
            unloadTicks = 0;
            return;
        }

        if (unloadTicks > SHULKER_OPEN_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "open_timeout"));
            finish(false, "shulker_open_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadTicks == 1 || unloadTicks % 10 == 0) {
            shulkerOpenRetries++;
            BARITONE.rightClickBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]);
        }
    }

    private void tickUnloadTakeContents() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        if (containerSlots == null || openContainerId < 0) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "open_container_missing"));
            finish(false, "shulker_container_missing");
            return;
        }

        int chestSlots = getOpenContainerSlotCount();
        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                String requestKey = requestKeyForItem(remaining, itemId);
                Integer needed = requestKey == null ? null : remaining.get(requestKey);
                if (needed != null && needed > 0 && hasInventoryRoom(stack)) {
                    if (stack.getAmount() > needed) {
                        beginSplitTake(actionSlotIndex, requestKey, stack.getAmount(), needed);
                        actionSlotIndex++;
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }

                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }
                    successfulTransfers++;
                    remaining.put(requestKey, Math.max(0, needed - stack.getAmount()));
                    actionSlotIndex++;
                    actionCooldown = CLICK_COOLDOWN_TICKS;

                    emit("retrieve_progress", Map.of(
                        "moved_stacks", successfulTransfers,
                        "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                    ));

                    if (isComplete()) {
                        closeCurrentContainer();
                        unloadPhase = 4;
                        unloadTicks = 0;
                    }
                    return;
                }
            }
            actionSlotIndex++;
        }

        closeCurrentContainer();
        unloadPhase = 4;
        unloadTicks = 0;
    }

    private void tickUnloadBreak() {
        if (placedShulkerPos == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_break_position"));
            finish(false, "missing_break_position");
            return;
        }

        if (BlockCompat.isAir(World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]))) {
            emit("retrieve_shulker_broken", Map.of(
                "placed_position", posString(placedShulkerPos)
            ));
            unloadPhase = 5;
            unloadTicks = 0;
            unloadBreakFuture = null;
            return;
        }

        if (unloadTicks > SHULKER_BREAK_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "break_timeout"));
            finish(false, "shulker_break_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadBreakFuture == null) {
            unloadBreakFuture = BaritoneCompat.breakBlock(
                placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2], true);
            return;
        }

        if (unloadBreakFuture.isDone() && !unloadBreakFuture.getNow()) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "break_rejected"));
            finish(false, "shulker_break_rejected");
        }
    }

    private void tickUnloadResume() {
        if (unloadTicks < SHULKER_PICKUP_WAIT_TICKS) {
            return;
        }

        resetUnloadState();
        if (isComplete()) {
            finish(true, "complete");
        } else {
            advanceToNextTarget(null);
        }
    }

    private void advanceToNextTarget(String reason) {
        if (currentTarget != null && !isComplete() && reason != null) {
            if ("container_exhausted".equals(reason)) {
                emit("retrieve_target_exhausted", Map.of(
                    "moved_stacks", successfulTransfers,
                    "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                ));
            } else {
                emit("retrieve_target_failed", Map.of("failure_reason", reason));
            }
        }

        closeCurrentContainer();

        if (isComplete()) {
            finish(true, "complete");
            return;
        }

        int[] nextTarget;
        do {
            nextTarget = targetQueue.poll();
        } while (nextTarget != null && !isAllowedTarget(nextTarget[0], nextTarget[1], nextTarget[2]));

        if (nextTarget == null) {
            finish(false, "no_more_targets");
            return;
        }

        currentTarget = nextTarget;
        state = State.WALKING;
        openWaitTicks = 0;
        actionCooldown = 0;
        actionSlotIndex = 0;
        walkingTicks = 0;
        stationaryWalkTicks = 0;
        foodPathRearmed = false;
        var player = CACHE.getPlayerCache();
        lastWalkX = player.getX();
        lastWalkY = player.getY();
        lastWalkZ = player.getZ();
        containerDataReceived = false;
        BARITONE.stop();
        emit("retrieve_target_selected", Map.of(
            "candidate_targets_remaining", targetQueue.size()
        ));
        pathToTarget();
    }

    private void finish(boolean completed, String reason) {
        BARITONE.stop();
        closeCurrentContainer();
        restorePlaceBlockSneak();
        state = State.DONE;
        resetUnloadState();
        if (completed) {
            emit("retrieve_completed", Map.of(
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        } else {
            emit("retrieve_incomplete", Map.of(
                "reason", reason,
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        }
    }

    private void resetState() {
        targetQueue.clear();
        currentTarget = null;
        remaining.clear();
        openWaitTicks = 0;
        actionCooldown = 0;
        actionSlotIndex = 0;
        walkingTicks = 0;
        stationaryWalkTicks = 0;
        foodPathRearmed = false;
        preferNearbyFoodTargets = false;
        consecutiveFailures = 0;
        initialRequestedTotal = 0;
        successfulTransfers = 0;
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
        serverSession = null;
        activeRequestName = null;
        state = State.IDLE;
        lastTeleportQueueSize = -1;
        ticksSinceTeleportQueueChange = 0;
        resetUnloadState();
        resetSplit();
        ownedShulkerSlots.clear();
        pendingOwnedShulkerFingerprint = null;
        pendingOwnedShulkerCandidateSlots.clear();
        activeRegionMin = null;
        activeRegionMax = null;
        excludedTargets.clear();
    }

    private void setTargetPolicy(int[] pos1, int[] pos2, Set<Long> excludedPositions) {
        if (pos1 != null && pos2 != null) {
            activeRegionMin = new int[]{
                Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2])
            };
            activeRegionMax = new int[]{
                Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])
            };
        }
        if (excludedPositions != null) {
            excludedTargets.addAll(excludedPositions);
        }
    }

    private boolean isAllowedTarget(int x, int y, int z) {
        if (activeRegionMin != null && (x < activeRegionMin[0] || x > activeRegionMax[0]
                || y < activeRegionMin[1] || y > activeRegionMax[1]
                || z < activeRegionMin[2] || z > activeRegionMax[2])) {
            return false;
        }
        return !excludedTargets.contains(posKey(x, y, z));
    }

    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) y & 0xFFFL) << 26
            | ((long) z & 0x3FFFFFFL);
    }

    private void resetUnloadState() {
        unloadPhase = 0;
        unloadTicks = 0;
        unloadTotalTicks = 0;
        unloadShulkerSlot = -1;
        unloadChestSlot = -1;
        placedShulkerPos = null;
        unloadShulkerItemData = null;
        unloadPlaceFuture = null;
        unloadBreakFuture = null;
        unloadHotbarRequest = null;
        unloadHotbarRawSlot = -1;
        shulkerInventorySearchDelay = 0;
        shulkerOpenRetries = 0;
        shulkerPlaceReplans = 0;
        shulkerHeldReselects = 0;
        rejectedShulkerPlacePositions.clear();
    }

    static int foodAccessBand(double playerX, double playerY, double playerZ,
                              ContainerEntry entry) {
        if (ContainerApproach.isAtAccessPosition(
                playerX, playerY, playerZ, entry.x(), entry.y(), entry.z())) return 0;
        if (ContainerApproach.isWithinVanillaReach(
                playerX, playerY, playerZ, entry.x(), entry.y(), entry.z())) return 1;
        return 2;
    }

    private int matchScore(ContainerEntry entry) {
        int score = 0;
        for (var kv : remaining.entrySet()) {
            int need = kv.getValue();
            if (need <= 0) continue;
            int have = amountMatchingRequest(entry.items(), kv.getKey());
            if (have > 0) {
                score += Math.min(need, have);
            }
        }
        return score;
    }

    // Count loose items only; prefer direct grabs when total scores tie.
    private int directMatchScore(ContainerEntry entry) {
        Map<String, Integer> direct = new HashMap<>(entry.items());
        for (ContainerEntry.ShulkerDetail sd : entry.shulkerDetails()) {
            for (var sdEntry : sd.items().entrySet()) {
                direct.computeIfPresent(sdEntry.getKey(), (k, v) -> {
                    int remainingAmount = v - sdEntry.getValue();
                    return remainingAmount > 0 ? remainingAmount : null;
                });
            }
        }

        int score = 0;
        for (var kv : remaining.entrySet()) {
            int need = kv.getValue();
            if (need <= 0) continue;
            int have = amountMatchingRequest(direct, kv.getKey());
            if (have > 0) {
                score += Math.min(need, have);
            }
        }
        return score;
    }

    private boolean containsWantedContents(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        String itemId = itemIdFromStack(stack);
        if (!isShulkerBoxItem(itemId)) return false;

        for (var entry : ItemIdentifier.readShulkerContents(stack).entrySet()) {
            // Let suffixed tool variants satisfy base-item requests.
            Integer needed = remaining.get(entry.getKey());
            if (needed == null) needed = remaining.get(ItemIdentifier.baseItemId(entry.getKey()));
            if (needed != null && needed > 0 && entry.getValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isWanted(String itemId) {
        return requestKeyForItem(remaining, itemId) != null;
    }

    /** A plain request accepts named/enchantment variants; a qualified request stays exact. */
    static String requestKeyForItem(Map<String, Integer> requests, String observedItemId) {
        if (requests == null || observedItemId == null) return null;
        Integer exact = requests.get(observedItemId);
        if (exact != null && exact > 0) return observedItemId;
        String base = ItemIdentifier.baseItemId(observedItemId);
        Integer baseNeed = requests.get(base);
        return baseNeed != null && baseNeed > 0 ? base : null;
    }

    static int amountMatchingRequest(Map<String, Integer> indexedItems, String requestItemId) {
        if (indexedItems == null || requestItemId == null) return 0;
        String requestBase = ItemIdentifier.baseItemId(requestItemId);
        if (!requestItemId.equals(requestBase)) {
            return Math.max(0, indexedItems.getOrDefault(requestItemId, 0));
        }
        int total = 0;
        for (var entry : indexedItems.entrySet()) {
            if (requestBase.equals(ItemIdentifier.baseItemId(entry.getKey()))
                    && entry.getValue() != null && entry.getValue() > 0) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private boolean isComplete() {
        return remaining.values().stream().noneMatch(v -> v != null && v > 0);
    }

    private void updateTeleportStability() {
        int queueSize = CACHE.getPlayerCache().getTeleportQueue().size();
        if (queueSize != lastTeleportQueueSize) {
            lastTeleportQueueSize = queueSize;
            ticksSinceTeleportQueueChange = 0;
        } else {
            ticksSinceTeleportQueueChange++;
        }
    }

    private boolean isTeleportCalm() {
        return CACHE.getPlayerCache().getTeleportQueue().isEmpty()
            && ticksSinceTeleportQueueChange >= TELEPORT_CALM_TICKS;
    }

    private void pathToTarget() {
        if (currentTarget == null) return;
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(currentTarget[0], currentTarget[1], currentTarget[2])));
    }

    private boolean isAtTargetAccessPosition() {
        if (currentTarget == null) return false;
        var playerCache = CACHE.getPlayerCache();
        return ContainerApproach.isAtAccessPosition(
            playerCache.getX(), playerCache.getY(), playerCache.getZ(),
            currentTarget[0], currentTarget[1], currentTarget[2]);
    }

    private void interactWithTarget() {
        if (currentTarget == null) return;
        BARITONE.rightClickBlock(currentTarget[0], currentTarget[1], currentTarget[2]);
    }

    private boolean prepareStandingContainerInteraction() {
        setPlaceBlockSneak(false);
        BARITONE.stop();
        if (containerOpenGate.tick(BOT.isSneaking())) return true;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().sneaking(false).build())
            .priority(SneakReleaseGate.INPUT_PRIORITY)
            .build());
        return false;
    }

    private void closeCurrentContainer() {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new CloseContainer())
                .priority(5000)
                .build());
        } catch (Exception ignored) {
        }
        containerDataReceived = false;
        openContainerId = -1;
    }

    private int getOpenContainerSlotCount() {
        if (containerSlots == null) return 0;
        return Math.max(0, containerSlots.length - 36);
    }

    // Use Zenith's queue for current action/container IDs; false means the click was rejected.
    private boolean quickMoveSlot(int slot) {
        if (openContainerId < 0) return false;

        try {
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(6000)
                .actions(new ShiftClick(openContainerId, slot, ShiftClickItemAction.LEFT_CLICK))
                .build());
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception ignored) {
            return false;
        }
    }


    private boolean hasInventoryRoom(ItemStack incoming) {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        return hasPlayerInventoryRoomFor(playerContainer, incoming);
    }

    static boolean hasPlayerInventoryRoomFor(Container playerContainer, ItemStack incoming) {
        if (playerContainer == null || incoming == null || incoming.getAmount() <= 0) return false;
        var incomingData = ItemRegistry.REGISTRY.get(incoming.getId());
        int maxStack = incomingData == null ? 1 : Math.max(1, incomingData.stackSize());

        // Player inventory: main 9-35, hotbar 36-44.
        for (int i = 9; i < 45; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
            if (stack.getId() == incoming.getId()
                    && stack.getAmount() < maxStack
                    && java.util.Objects.equals(
                            stack.getDataComponents(), incoming.getDataComponents())) {
                return true;
            }
        }
        return false;
    }

    private int findWantedShulkerSlot() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return -1;

        for (int slot = 36; slot <= 44; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        ItemStack offhand = playerContainer.getItemStack(45);
        if (matchesWantedShulker(offhand)) return 45;
        return -1;
    }

    private int findInventoryShulkerWithWantedContents() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return -1;

        for (int slot = 36; slot <= 44; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        return matchesWantedShulker(playerContainer.getItemStack(45)) ? 45 : -1;
    }

    private boolean matchesWantedShulker(ItemStack stack) {
        return stack != null && stack.getAmount() > 0 && containsWantedContents(stack);
    }

    private boolean moveShulkerToHotbar(int slot) {
        try {
            var builder = InventoryActionRequest.builder()
                .owner(this)
                .priority(6000);
            if (slot >= 36 && slot <= 44) {
                unloadHotbarRawSlot = slot;
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                unloadHotbarRawSlot = 36 + SHULKER_HOTBAR_SLOT;
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.SLOT_7),
                    new SetHeldItem(SHULKER_HOTBAR_SLOT)
                );
                swapOwnedShulkerSlots(slot, 36 + SHULKER_HOTBAR_SLOT);
            }
            unloadHotbarRequest = INVENTORY.submit(builder.build());
            return !(unloadHotbarRequest.isCompleted() && !unloadHotbarRequest.getNow());
        } catch (Exception ignored) {
            unloadHotbarRequest = null;
            unloadHotbarRawSlot = -1;
            return false;
        }
    }

    private boolean unloadShulkerReadyForPlacement() {
        if (unloadHotbarRawSlot < 36 || unloadHotbarRawSlot > 44) return false;
        int heldHotbarIndex = CACHE.getPlayerCache().getHeldItemSlot();
        if (36 + heldHotbarIndex != unloadHotbarRawSlot) return false;
        return matchesWantedShulker(getPlayerInventoryStack(unloadHotbarRawSlot));
    }

    private ItemStack getPlayerInventoryStack(int slot) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return null;
        return playerContainer.getItemStack(slot);
    }

    // Search broadly and use the nearest safe placement spot.
    private int[] findShulkerPlaceSpot() {
        int baseX = (int) Math.floor(CACHE.getPlayerCache().getX());
        int baseY = (int) Math.floor(CACHE.getPlayerCache().getY());
        int baseZ = (int) Math.floor(CACHE.getPlayerCache().getZ());

        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0) continue; // player's own column — never place under/on self
                    int x = baseX + dx;
                    int y = baseY + dy;
                    int z = baseZ + dz;
                    if (!World.isInWorldBounds(x, y, z)) continue;
                    if (rejectedShulkerPlacePositions.contains(posKey(x, y, z))) continue;

                    int[] candidate = new int[]{x, y, z};
                    if (!isSafeShulkerPlaceSpot(candidate)
                            || !isWithinShulkerPlacementReach(candidate)) {
                        continue;
                    }

                    double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }

        return best;
    }

    private boolean isWithinShulkerPlacementReach(int[] pos) {
        var player = CACHE.getPlayerCache();
        return ContainerApproach.isWithinVanillaReach(
            player.getX(), player.getY(), player.getZ(), pos[0], pos[1], pos[2]);
    }

    private boolean isSafeShulkerPlaceSpot(int[] pos) {
        int x = pos[0];
        int y = pos[1];
        int z = pos[2];
        if (!World.isInWorldBounds(x, y, z)) return false;
        if (playerIntersectsBlock(x, y, z)) return false;

        var targetBlock = World.getBlock(x, y, z);
        var aboveBlock = World.getBlock(x, y + 1, z);
        var belowBlock = World.getBlock(x, y - 1, z);
        // Baritone may choose any adjacent support face. Keep all of them non-interactable.
        var northBlock = World.getBlock(x, y, z - 1);
        var southBlock = World.getBlock(x, y, z + 1);
        var eastBlock = World.getBlock(x + 1, y, z);
        var westBlock = World.getBlock(x - 1, y, z);
        return BlockCompat.canReplace(targetBlock)
            && BlockCompat.canReplace(aboveBlock)
            && !BlockCompat.isAir(belowBlock)
            && !BlockCompat.isInteractable(belowBlock)
            && !BlockCompat.isInteractable(northBlock)
            && !BlockCompat.isInteractable(southBlock)
            && !BlockCompat.isInteractable(eastBlock)
            && !BlockCompat.isInteractable(westBlock)
            && !BlockCompat.isInteractable(aboveBlock)
            && BlockCompat.isSolid(x, y - 1, z);
    }

    private boolean playerIntersectsBlock(int x, int y, int z) {
        var player = CACHE.getPlayerCache();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        // Minecraft players are 0.6 blocks wide and 1.8 blocks tall. A neighbouring block can
        // overlap the bot when it is standing close to an edge even though it is not the same
        // floored X/Z column, which makes Baritone reject every placement replan.
        return playerX + 0.3 > x && playerX - 0.3 < x + 1
                && playerY + 1.8 > y && playerY < y + 1
                && playerZ + 0.3 > z && playerZ - 0.3 < z + 1;
    }

    private boolean isShulkerBoxItem(String itemId) {
        return itemId != null && itemId.contains("shulker_box");
    }

    private String itemIdFromStack(ItemStack stack) {
        return ItemIdentifier.getItemId(stack);
    }

    private String posString(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }

    private void emit(String event, Map<String, Object> extraFields) {
        if (eventCallback == null) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        if (activeRequestName != null) payload.put("request_name", activeRequestName);
        payload.put("retriever_state", state.name());
        payload.put("remaining_total", getRemainingTotal());
        payload.put("moved_stacks", successfulTransfers);
        if (currentTarget != null) payload.put("target_position", posString(currentTarget));
        if (placedShulkerPos != null) payload.put("placed_shulker_position", posString(placedShulkerPos));
        if (extraFields != null && !extraFields.isEmpty()) payload.putAll(extraFields);
        eventCallback.accept(event, payload);
    }

    private double distanceTo(int x, int y, int z) {
        var pc = CACHE.getPlayerCache();
        double dx = pc.getX() - x;
        double dy = pc.getY() - y;
        double dz = pc.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Return true when this tick submits a split-stack click.
    private boolean tickSplitTake() {
        if (serverSession == null || openContainerId < 0) {
            resetSplit();
            return false;
        }

        // Pick up the source stack.
        if (!splitCursorReady) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK)) {
                return true;
            }
            splitCursorReady = true;
            return true;
        }

        // Put excess items back one per tick.
        if (splitPutbacksLeft > 0) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.RIGHT_CLICK)) {
                return true;
            }
            splitPutbacksLeft--;
            return true;
        }

        // Move the kept portion to an empty player slot.
        int chestSlots = getOpenContainerSlotCount();
        int playerStart = chestSlots;
        int dropSlot = -1;
        for (int s = playerStart; s < playerStart + 36; s++) {
            if (containerSlots != null && s < containerSlots.length) {
                ItemStack slotStack = containerSlots[s];
                if (slotStack == null || slotStack.getAmount() == 0) {
                    dropSlot = s;
                    break;
                }
            }
        }

        if (dropSlot == -1) {
            // Restore the source stack when inventory is full.
            submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK);
            resetSplit();
            return true;
        }

        if (!submitClickItem(dropSlot, ClickItemAction.LEFT_CLICK)) {
            return true;
        }

        // Record the amount moved.
        recordTaken(splitItemId, splitNeeded);
        resetSplit();
        return true;
    }

    // Advance split state only after InventoryManager accepts the click.
    private boolean submitClickItem(int slot, ClickItemAction action) {
        try {
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(6000)
                .actions(new ClickItem(openContainerId, slot, action))
                .build());
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            resetSplit();
            return false;
        }
    }

    // Start splitting a stack larger than the remaining request.
    private void beginSplitTake(int srcSlot, String itemId, int stackCount, int needed) {
        splitInProgress = true;
        splitSrcSlot = srcSlot;
        splitItemId = itemId;
        splitNeeded = Math.max(1, Math.min(needed, stackCount));
        splitPutbacksLeft = stackCount - splitNeeded;
        splitCursorReady = false;
    }

    private void resetSplit() {
        splitInProgress = false;
        splitSrcSlot = -1;
        splitPutbacksLeft = 0;
        splitNeeded = 0;
        splitCursorReady = false;
        splitItemId = null;
    }

    private void recordTaken(String itemId, int count) {
        Integer current = remaining.get(itemId);
        if (current != null && current > 0) {
            remaining.put(itemId, Math.max(0, current - count));
        }
        consecutiveFailures = 0; // reset on successful take
    }

    // Track where a borrowed shulker lands.
    private void beginTrackingOwnedShulker(ItemStack stack, int chestSlots) {
        pendingOwnedShulkerFingerprint = shulkerFingerprint(stack);
        pendingOwnedShulkerCandidateSlots.clear();
        
        // Record possible destination slots.
        if (containerSlots != null) {
            for (int slot = chestSlots; slot < chestSlots + 36; slot++) {
                if (slot < containerSlots.length) {
                    ItemStack invStack = containerSlots[slot];
                    if (invStack == null || invStack.getAmount() == 0) {
                        pendingOwnedShulkerCandidateSlots.add(playerInventorySlotFromContainerSlot(chestSlots, slot));
                    }
                }
            }
        }
    }

    // Resolve the borrowed shulker's destination slot.
    private void resolvePendingOwnedShulker() {
        if (pendingOwnedShulkerFingerprint == null || pendingOwnedShulkerCandidateSlots.isEmpty()) return;

        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return;

        for (int slot : pendingOwnedShulkerCandidateSlots) {
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = ItemIdentifier.getItemId(stack);
                if (isShulkerBoxItem(itemId)) {
                    if (pendingOwnedShulkerFingerprint.equals(shulkerFingerprint(stack))) {
                        ownedShulkerSlots.add(slot);
                        pendingOwnedShulkerFingerprint = null;
                        pendingOwnedShulkerCandidateSlots.clear();
                        return;
                    }
                }
            }
        }
    }

    // Follow ownership through inventory swaps.
    private void swapOwnedShulkerSlots(int slotA, int slotB) {
        boolean ownedA = ownedShulkerSlots.remove(slotA);
        boolean ownedB = ownedShulkerSlots.remove(slotB);
        if (ownedA) ownedShulkerSlots.add(slotB);
        if (ownedB) ownedShulkerSlots.add(slotA);
    }

    private String shulkerFingerprint(ItemStack stack) {
        String itemId = ItemIdentifier.getItemId(stack);
        // TODO: Derive a stable fingerprint from item components.
        return itemId + "|" + stack.hashCode();
    }

    private static int playerInventorySlotFromContainerSlot(int chestSlots, int slot) {
        int relative = slot - chestSlots;
        if (relative < 27) return relative + 9; // Main inventory
        return relative + 9; // Hotbar (container slots 36-44)
    }

    private static int containerSlotFromPlayerInventorySlot(int chestSlots, int slot) {
        if (slot >= 9 && slot <= 35) return chestSlots + slot - 9;
        if (slot >= 36 && slot <= 44) return chestSlots + 27 + slot - 36;
        return -1;
    }

    private boolean returnFinishedOwnedShulker(int chestSlots) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return false;

        var iterator = ownedShulkerSlots.iterator();
        while (iterator.hasNext()) {
            int inventorySlot = iterator.next();
            ItemStack stack = playerContainer.getItemStack(inventorySlot);
            if (stack == null || stack.getAmount() <= 0 || !isShulkerBoxItem(itemIdFromStack(stack))) {
                iterator.remove();
                continue;
            }

            boolean stillNeeded = ItemIdentifier.readShulkerContents(stack).keySet().stream()
                .anyMatch(this::isWanted);
            if (stillNeeded) continue;

            int containerSlot = containerSlotFromPlayerInventorySlot(chestSlots, inventorySlot);
            if (containerSlot < 0) {
                iterator.remove();
                continue;
            }

            if (!quickMoveSlot(containerSlot)) return true;
            iterator.remove();
            emit("retrieve_owned_shulker_returned", Map.of());
            return true;
        }
        return false;
    }

    // Return a matching owned slot, or -1.
    private int findShulkerWithNeededItems() {
        resolvePendingOwnedShulker();
        
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return -1;

        var it = ownedShulkerSlots.iterator();
        while (it.hasNext()) {
            int slot = it.next();
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack == null || stack.getAmount() == 0) {
                it.remove();
                continue;
            }
            
            String itemId = ItemIdentifier.getItemId(stack);
            if (!isShulkerBoxItem(itemId)) {
                it.remove();
                continue;
            }

            // Owned shulkers were selected because they contained requested items.
            for (String neededItem : remaining.keySet()) {
                if (remaining.get(neededItem) > 0) {
                    return slot;
                }
            }
        }
        
        return -1;
    }
}
