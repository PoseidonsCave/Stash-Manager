package com.zenith.plugin.stashmanager.retriever;

import com.zenith.Proxy;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/**
 * Lightweight retrieval state machine used by /stash get and /stash get kit.
 * Walks to candidate containers and shift-clicks matching items into player inventory.
 */
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
    private static final int CLICK_COOLDOWN_TICKS = 3;
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

    private int openWaitTicks;
    private int actionCooldown;
    private int actionSlotIndex;
    private int walkingTicks;
    private int consecutiveFailures;
    private int initialRequestedTotal;
    private int successfulTransfers;

    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile int containerStateId = 0;
    private volatile ItemStack[] containerSlots;
    private volatile Session serverSession;

    private int unloadPhase;
    private int unloadTicks;
    private int unloadTotalTicks;
    private int unloadShulkerSlot = -1;
    private int unloadChestSlot = -1;
    private int[] placedShulkerPos;
    private ItemData unloadShulkerItemData;
    private PathingRequestFuture unloadPlaceFuture;
    private PathingRequestFuture unloadBreakFuture;
    private int shulkerInventorySearchDelay;
    private int shulkerOpenRetries;

    private int lastTeleportQueueSize = -1;
    private int ticksSinceTeleportQueueChange;

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
        if (kitItems == null || kitItems.isEmpty()) return false;
        if (isActive()) return false;

        var proxy = Proxy.getInstance();
        if (!proxy.isConnected() || proxy.hasActivePlayer()) {
            return false;
        }

        resetState();
        activeRequestName = requestName;
        kitItems.forEach((k, v) -> {
            if (v != null && v > 0) remaining.put(k, v);
        });

        if (remaining.isEmpty()) {
            state = State.DONE;
            emit("retrieve_no_targets", Map.of("reason", "empty_request"));
            return false;
        }

        initialRequestedTotal = getRemainingTotal();

        List<ContainerEntry> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
            .comparingInt((ContainerEntry e) -> -matchScore(e))
            .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z())));

        for (ContainerEntry entry : sorted) {
            if (matchScore(entry) <= 0) continue;
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
        this.containerStateId = packet.getStateId();
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
        }
    }

    private void tickTaking() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        if (containerSlots == null || openContainerId < 0) {
            consecutiveFailures++;
            advanceToNextTarget("container_sync_failed");
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                boolean wantedDirectly = isWanted(itemId);
                boolean wantedForContents = !wantedDirectly && containsWantedContents(stack);
                Integer needed = remaining.get(itemId);

                if ((wantedDirectly || wantedForContents) && hasInventoryRoom()) {
                    quickMoveSlot(actionSlotIndex);
                    successfulTransfers++;
                    actionSlotIndex++;
                    actionCooldown = CLICK_COOLDOWN_TICKS;

                    if (wantedForContents) {
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

    private void tickUnloadLocateAndPrepare() {
        if (shulkerInventorySearchDelay > 0) {
            shulkerInventorySearchDelay--;
            return;
        }

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
            unloadPlaceFuture = BARITONE.placeBlock(
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
                    quickMoveSlot(actionSlotIndex);
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

        if (World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]).isAir()) {
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
            unloadBreakFuture = BARITONE.breakBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2], true);
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

        if (targetQueue.isEmpty()) {
            finish(false, "no_more_targets");
            return;
        }

        currentTarget = targetQueue.poll();
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
        containerStateId = 0;
        containerSlots = null;
        serverSession = null;
        activeRequestName = null;
        state = State.IDLE;
        lastTeleportQueueSize = -1;
        ticksSinceTeleportQueueChange = 0;
        resetUnloadState();
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

    private boolean containsWantedContents(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        String itemId = itemIdFromStack(stack);
        if (!isShulkerBoxItem(itemId)) return false;

        for (var entry : ItemIdentifier.readShulkerContents(stack).entrySet()) {
            Integer needed = remaining.get(entry.getKey());
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

    private void quickMoveSlot(int slot) {
        if (serverSession == null || openContainerId < 0) return;

        try {
            var packet = new ServerboundContainerClickPacket(
                openContainerId,
                containerStateId,
                slot,
                ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK,
                null,
                new Int2ObjectOpenHashMap<>()
            );
            serverSession.send(packet);
            containerStateId++;
        } catch (Exception ignored) {
        }
    }

    private boolean hasInventoryRoom() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        for (int i = 9; i < 36; i++) {
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

    private int[] findShulkerPlaceSpot() {
        int baseX = (int) Math.floor(CACHE.getPlayerCache().getX());
        int baseY = (int) Math.floor(CACHE.getPlayerCache().getY());
        int baseZ = (int) Math.floor(CACHE.getPlayerCache().getZ());

        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        for (int[] offset : offsets) {
            int x = baseX + offset[0];
            int y = baseY;
            int z = baseZ + offset[1];
            if (!World.isInWorldBounds(x, y, z)) continue;

            var targetBlock = World.getBlock(x, y, z);
            var aboveBlock = World.getBlock(x, y + 1, z);
            var belowBlock = World.getBlock(x, y - 1, z);
            if ((!targetBlock.isAir() && !targetBlock.replaceable())
                || (!aboveBlock.isAir() && !aboveBlock.replaceable())
                || belowBlock.isAir()
                || !belowBlock.solidBlock()) {
                continue;
            }
            return new int[]{x, y, z};
        }

        return null;
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
}
