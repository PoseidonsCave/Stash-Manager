package com.zenith.plugin.stashmanager.retriever;

import com.zenith.Proxy;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.organizer.lane.FifoLane;
import com.zenith.plugin.stashmanager.organizer.lane.LaneDetector;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.PathfinderCompat;
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

    private static final int OPEN_TIMEOUT_TICKS = 60;
    // Zenith's own InventoryManager only executes a queued action every actionDelayTicks (5
    // by default) — submitting faster than that gets silently rejected, so this must stay
    // at or above that value.
    private static final int CLICK_COOLDOWN_TICKS = 6;
    private static final int WALK_TIMEOUT_TICKS = 400;
    private static final int MAX_CONSECUTIVE_FAILURES = 4;

    private static final int SHULKER_TOTAL_TIMEOUT_TICKS = 200;
    private static final int SHULKER_PLACE_TIMEOUT_TICKS = 60;
    private static final int SHULKER_OPEN_TIMEOUT_TICKS = 60;
    private static final int SHULKER_BREAK_TIMEOUT_TICKS = 80;
    private static final int SHULKER_PICKUP_WAIT_TICKS = 16;
    private static final int SHULKER_SEARCH_SETTLE_TICKS = 4;
    private static final int TELEPORT_CALM_TICKS = 6;
    private static final int SHULKER_HOTBAR_SLOT = 6;

    private State state = State.IDLE;
    private String activeRequestName;

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
    private int consecutiveFailures;
    private int initialRequestedTotal;
    private int successfulTransfers;

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
    private int shulkerInventorySearchDelay;
    private int shulkerOpenRetries;

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
        if (kitItems == null || kitItems.isEmpty()) return false;
        if (isActive()) return false;

        var proxy = Proxy.getInstance();
        if (!proxy.isConnected() || proxy.hasActivePlayer()) {
            return false;
        }

        resetState();
        activeRequestName = requestName;
    setTargetPolicy(regionPos1, regionPos2, excludedPositions);
        kitItems.forEach((k, v) -> {
            if (v != null && v > 0) remaining.put(k, v);
        });

        if (remaining.isEmpty()) {
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
        sorted.sort(Comparator
            .comparingInt((ContainerEntry e) -> -matchScore(e))
            .thenComparingInt(e -> directMatchScore(e) > 0 ? 0 : 1)
            .thenComparingInt(e -> laneOutputKeys.contains(posKey(e.x(), e.y(), e.z())) ? 0 : 1)
            .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z())));

        for (ContainerEntry entry : sorted) {
            if (matchScore(entry) <= 0) continue;
            if (!isAllowedTarget(entry.x(), entry.y(), entry.z())) continue;
            targetQueue.add(new int[]{entry.x(), entry.y(), entry.z()});
        }

        if (targetQueue.isEmpty()) {
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
            "candidate_targets", targetQueue.size()
        ));

        BARITONE.stop();
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

        double dist = distanceTo(currentTarget[0], currentTarget[1], currentTarget[2]);
        if (dist <= 5.0) {
            BARITONE.stop();
            state = State.OPENING;
            openWaitTicks = 0;
            containerDataReceived = false;
            interactWithTarget();
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

    private void tickOpening() {
        openWaitTicks++;

        if (containerDataReceived) {
            state = State.TAKING;
            actionSlotIndex = 0;
            actionCooldown = 0;
            emit("retrieve_target_opened", Map.of());
            return;
        }

        if (openWaitTicks > OPEN_TIMEOUT_TICKS) {
            consecutiveFailures++;
            advanceToNextTarget("open_timeout");
            return;
        }

        // A single missed right-click (rotation not settled, brief lag, etc.) should not
        // doom the whole target — retry periodically like tickUnloadOpen does.
        if (openWaitTicks == 1 || openWaitTicks % 10 == 0) {
            interactWithTarget();
        }
    }

    private void tickTaking() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // Drive any in-progress partial-take split first
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
                boolean wantedDirectly = isWanted(itemId);
                boolean wantedForContents = !wantedDirectly && containsWantedContents(stack);
                Integer needed = remaining.get(itemId);

                if ((wantedDirectly || wantedForContents) && hasInventoryRoom()) {
                    
                    // If wanted directly and stack exceeds need, do partial-take split
                    if (wantedDirectly && !wantedForContents && needed != null && needed > 0 && stack.getAmount() > needed) {
                        beginSplitTake(actionSlotIndex, itemId, stack.getAmount(), needed);
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
                        // Track this shulker as owned since we're taking it for contents
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
                        remaining.put(itemId, Math.max(0, needed - stack.getAmount()));
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
        unloadPhase = 0;
        unloadTicks = 0;
        unloadTotalTicks = 0;
        unloadChestSlot = chestSlot;
        unloadShulkerSlot = -1;
        placedShulkerPos = null;
        unloadShulkerItemData = ItemRegistry.REGISTRY.get(shulkerStack.getId());
        unloadPlaceFuture = null;
        unloadBreakFuture = null;
        shulkerInventorySearchDelay = SHULKER_SEARCH_SETTLE_TICKS;
        shulkerOpenRetries = 0;
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
            emit("retrieve_shulker_unload_failed", Map.of("reason", "timeout"));
            finish(false, "shulker_unload_timeout");
            return;
        }

        // Sneak while placing so the right-click places the shulker instead of opening
        // whatever interactive block (chest, barrel, etc.) happens to be nearby.
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
                emit("retrieve_shulker_unload_failed", Map.of("reason", "no_place_spot"));
                finish(false, "no_shulker_place_spot");
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

        moveShulkerToHotbar(unloadShulkerSlot);
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
            unloadTicks = 0;
            unloadPlaceFuture = null;
            return;
        }

        if (unloadTicks > SHULKER_PLACE_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "place_timeout"));
            finish(false, "shulker_place_timeout");
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
            emit("retrieve_shulker_unload_failed", Map.of("reason", "place_rejected"));
            finish(false, "shulker_place_rejected");
        }
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
                Integer needed = remaining.get(itemId);
                if (needed != null && needed > 0 && hasInventoryRoom()) {
                    if (stack.getAmount() > needed) {
                        beginSplitTake(actionSlotIndex, itemId, stack.getAmount(), needed);
                        actionSlotIndex++;
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }

                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }
                    successfulTransfers++;
                    remaining.put(itemId, Math.max(0, needed - stack.getAmount()));
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
        shulkerInventorySearchDelay = 0;
        shulkerOpenRetries = 0;
    }

    private int matchScore(ContainerEntry entry) {
        int score = 0;
        for (var kv : remaining.entrySet()) {
            int need = kv.getValue();
            if (need <= 0) continue;
            int have = entry.items().getOrDefault(kv.getKey(), 0);
            if (have > 0) {
                score += Math.min(need, have);
            }
        }
        return score;
    }

    // Like matchScore, but only counts quantities available loose in the
    // container itself — not merged-in shulker contents, which require an
    // extra take-and-unload step. Used to prefer quick direct grabs when
    // otherwise tied (e.g. a lane's input vs. output chest).
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
            int have = direct.getOrDefault(kv.getKey(), 0);
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
            // Match by base item id too — a shulker holding an enchanted tool (e.g.
            // "diamond_pickaxe[fortune]") should still satisfy a plain "diamond_pickaxe" request.
            Integer needed = remaining.get(entry.getKey());
            if (needed == null) needed = remaining.get(ItemIdentifier.baseItemId(entry.getKey()));
            if (needed != null && needed > 0 && entry.getValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isWanted(String itemId) {
        Integer needed = remaining.get(itemId);
        return needed != null && needed > 0;
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

    private void interactWithTarget() {
        if (currentTarget == null) return;
        BARITONE.rightClickBlock(currentTarget[0], currentTarget[1], currentTarget[2]);
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

    // Goes through Zenith's own InventoryManager queue (ShiftClick action) instead of
    // hand-rolling the raw packet ourselves — that queue builds the packet fresh at actual
    // execution time (correct action/state id, no off-by-one) and verifies the container id
    // still matches what's currently open before sending, which our own raw send never did.
    // Returns false if InventoryManager rejected the submission outright (e.g. a previous
    // action from this or another owner is still pending) — callers must NOT treat the slot
    // as handled when this returns false, or progress gets reported without anything moving.
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


    private boolean hasInventoryRoom() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        // Raw player inventory container is size 46: 9-35=main inventory, 36-44=hotbar —
        // must check both ranges or a full main inventory with a free hotbar slot looks full.
        for (int i = 9; i < 45; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
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

    private boolean matchesWantedShulker(ItemStack stack) {
        return stack != null && stack.getAmount() > 0 && containsWantedContents(stack);
    }

    private void moveShulkerToHotbar(int slot) {
        try {
            var builder = InventoryActionRequest.builder()
                .owner(this)
                .priority(6000);
            if (slot >= 36 && slot <= 44) {
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.SLOT_7),
                    new SetHeldItem(SHULKER_HOTBAR_SLOT)
                );
                swapOwnedShulkerSlots(slot, 36 + SHULKER_HOTBAR_SLOT);
            }
            INVENTORY.submit(builder.build());
        } catch (Exception ignored) {
        }
    }

    private ItemStack getPlayerInventoryStack(int slot) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return null;
        return playerContainer.getItemStack(slot);
    }

    // Searches a wide area around the player rather than a small fixed ring, so a spot
    // further down a packed shelving aisle can still be found even if the immediate
    // neighbors are all chests; picks the closest valid spot rather than the first found.
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

                    var targetBlock = World.getBlock(x, y, z);
                    var aboveBlock = World.getBlock(x, y + 1, z);
                    var belowBlock = World.getBlock(x, y - 1, z);
                    // Baritone tries every neighboring face (down/south/east/north/west/up) to place
                    // against, not just the one below — any of them being a container/GUI block
                    // means a right-click there opens it instead of placing the shulker.
                    var northBlock = World.getBlock(x, y, z - 1);
                    var southBlock = World.getBlock(x, y, z + 1);
                    var eastBlock = World.getBlock(x + 1, y, z);
                    var westBlock = World.getBlock(x - 1, y, z);
                    if (!BlockCompat.canReplace(targetBlock)
                        || !BlockCompat.canReplace(aboveBlock)
                        || BlockCompat.isAir(belowBlock)
                        || BlockCompat.isInteractable(belowBlock)
                        || BlockCompat.isInteractable(northBlock)
                        || BlockCompat.isInteractable(southBlock)
                        || BlockCompat.isInteractable(eastBlock)
                        || BlockCompat.isInteractable(westBlock)
                        || BlockCompat.isInteractable(aboveBlock)
                        || !BlockCompat.isSolid(x, y - 1, z)) {
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

    // Split-Take (Partial Stack Retrieval)
    // Return true when this tick sends an inventory click.
    private boolean tickSplitTake() {
        if (serverSession == null || openContainerId < 0) {
            resetSplit();
            return false;
        }

        // Step 1: pick up the source stack to cursor
        if (!splitCursorReady) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK)) {
                return true;
            }
            splitCursorReady = true;
            return true;
        }

        // Step 2: right-click source to drop excess back, one item per tick
        if (splitPutbacksLeft > 0) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.RIGHT_CLICK)) {
                return true;
            }
            splitPutbacksLeft--;
            return true;
        }

        // Step 3: drop the kept portion into an empty player slot
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
            // No room. Put cursor back at source as fallback and bail
            submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK);
            resetSplit();
            return true;
        }

        if (!submitClickItem(dropSlot, ClickItemAction.LEFT_CLICK)) {
            return true;
        }

        // Record that we took the needed amount
        recordTaken(splitItemId, splitNeeded);
        resetSplit();
        return true;
    }

    // Submit a CLICK_ITEM action for the split-take state machine and report whether
    // InventoryManager actually accepted it — a rejected submission must not be treated
    // as having happened, or the split-take steps advance past clicks that never landed.
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

    // Begin a partial-take split for a slot whose stack exceeds what we need.
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

    // Owned Shulker Tracking
    // Track where a borrowed shulker lands in player inventory.
    private void beginTrackingOwnedShulker(ItemStack stack, int chestSlots) {
        pendingOwnedShulkerFingerprint = shulkerFingerprint(stack);
        pendingOwnedShulkerCandidateSlots.clear();
        
        // Record all empty player inventory slots as candidates
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

    // Match the borrowed shulker against its candidate slots.
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

    // Keep ownership aligned with inventory swaps.
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

    // Return the matching inventory slot, or -1 when none exists.
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

            // Check if shulker contains needed items
            // Would need NBT reading to check contents
            // Simplified: assume it has needed items if it's tracked
            for (String neededItem : remaining.keySet()) {
                if (remaining.get(neededItem) > 0) {
                    return slot;
                }
            }
        }
        
        return -1;
    }
}

