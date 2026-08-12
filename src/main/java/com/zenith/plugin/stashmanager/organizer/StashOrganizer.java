package com.zenith.plugin.stashmanager.organizer;

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
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.network.Session;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.zenith.Globals.*;

// Sorts items across containers in the configured region.
public final class StashOrganizer {

    // State Machine
    public enum State {
        IDLE,
        PLANNING,
        // Container-to-container moves
        WALKING,
        OPENING,
        TAKING,
        CLOSING_SOURCE,
        DEPOSITING,
        CLOSING_DEST,
        // Shulker packing cycle
        SHULKER_SELECTING,
        SHULKER_PLACING,
        SHULKER_WAIT_PLACE,
        SHULKER_OPENING,
        SHULKER_FILLING,
        SHULKER_CLOSING,
        SHULKER_BREAKING,
        SHULKER_PICKUP,
        SHULKER_FETCH_WALK,
        SHULKER_FETCH_OPEN,
        SHULKER_FETCH_TAKE,
        SHULKER_STORE_WALK,
        SHULKER_STORE_OPEN,
        SHULKER_STORE_DEPOSIT,
        // Crafting shulker boxes
        CRAFT_MATERIAL_WALK,
        CRAFT_MATERIAL_OPEN,
        CRAFT_MATERIAL_TAKE,
        CRAFT_WALKING,
        CRAFT_OPENING,
        CRAFT_PLACING,
        CRAFT_TAKING,
        // Overflow
        OVERFLOW_WALKING,
        OVERFLOW_OPENING,
        OVERFLOW_DEPOSITING,
        DONE
    }

    private enum TargetRole { SOURCE, DESTINATION }

    private State state = State.IDLE;
    private TargetRole currentRole = TargetRole.SOURCE;

    // Column Detection
    record Column(int id, List<int[]> chests) {
        int[] bottom() { return chests.get(chests.size() - 1); }
        int[] top()    { return chests.get(0); }
    }

    // Move Tasks
    record MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter) {
        MoveTask(int[] source, int[] destination, String itemId) {
            this(source, destination, itemId, null);
        }
    }

    private record ItemLocation(int[] pos, int quantity) {}

    // Configuration / References
    private final StashManagerConfig config;
    private final ContainerIndex index;
    private InfoCallback infoCallback;
    private BiConsumer<String, Map<String, Object>> eventCallback;

    // Task Queue
    private final Deque<MoveTask> taskQueue = new ArrayDeque<>();
    private final Deque<MoveTask> consolidationQueue = new ArrayDeque<>();
    private MoveTask currentTask;

    private Map<String, Column> columnAssignment = new LinkedHashMap<>();
    private int depositColumnIndex;

    // Timing
    private static final int OPEN_TIMEOUT_TICKS = 60;
    private static final int HOTBAR_SIZE = 9;
    private static final int CLICK_COOLDOWN_TICKS = 3;
    private static final int PICKUP_DELAY_TICKS = 20;
    private static final int BREAK_TIMEOUT_TICKS = 100;
    private static final int CONDENSE_MIN_ITEMS = 1;
    private static final int SHULKER_HOTBAR_SLOT = 6;

    // Runtime State
    private int[] walkTarget;
    private long trackedWalkTargetKey = Long.MIN_VALUE;
    private int walkingTicks;
    private int openWaitTicks;
    private int actionSlotIndex;
    private int actionCooldown;

    private int totalTasks;
    private int completedTasks;

    private boolean consolidationMode = false;

    // Shulker Packing State
    private String packItemId;
    private int[] packDestination;
    private int[] shulkerPlacePos;
    private ItemData packShulkerItemData;
    private PathingRequestFuture shulkerPlaceFuture;
    private PathingRequestFuture shulkerBreakFuture;
    private float savedYaw, savedPitch;
    private int shulkerTicks;
    private int shulkerPlaceRetries;

    // Crafting State
    private int[] craftingTablePos;
    private int shulkersToCraft;
    private int craftTicks;
    private final Deque<int[]> materialSources = new ArrayDeque<>();
    private int shellsNeeded;
    private int chestsNeeded;

    // Container Interaction State
    // Set by module packet handler on container open
    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile int containerStateId = 0;
    private volatile ItemStack[] containerSlots;
    private volatile Session serverSession;

    // Overflow
    private int[] overflowChestPos;
    private final Map<String, Integer> overflowItems = new LinkedHashMap<>();

    // Callback Interface
    @FunctionalInterface
    public interface InfoCallback {
        void info(String message);
    }

    // Constructor
    public StashOrganizer(StashManagerConfig config, ContainerIndex index) {
        this.config = config;
        this.index = index;
    }

    public void setInfoCallback(InfoCallback callback) {
        this.infoCallback = callback;
    }

    public void setEventCallback(BiConsumer<String, Map<String, Object>> callback) {
        this.eventCallback = callback;
    }

    // Public API
    public State getState() { return state; }
    public boolean isActive() { return state != State.IDLE && state != State.DONE; }
    public int getTotalTasks() { return totalTasks; }
    public int getCompletedTasks() { return completedTasks; }

    public boolean start() {
        if (config.pos1 == null || config.pos2 == null) {
            info("Cannot organize: region not defined (set pos1 and pos2 first)");
            emit("organize_start_blocked", Map.of("reason", "region_not_defined"));
            return false;
        }
        var proxy = Proxy.getInstance();
        if (!proxy.isConnected()) {
            info("Cannot organize: bot is not connected.");
            emit("organize_start_blocked", Map.of("reason", "bot_not_connected"));
            return false;
        }
        if (proxy.hasActivePlayer()) {
            info("Cannot organize: a player is currently controlling the proxy.");
            emit("organize_start_blocked", Map.of("reason", "proxy_in_use"));
            return false;
        }
        if (index.size() == 0) {
            info("Cannot organize: no scanned data. Run /stash scan first.");
            emit("organize_start_blocked", Map.of("reason", "no_scanned_data"));
            return false;
        }

        BARITONE.stop();
        taskQueue.clear();
        consolidationQueue.clear();
        overflowItems.clear();
        currentTask = null;
        walkTarget = null;
        trackedWalkTargetKey = Long.MIN_VALUE;
        walkingTicks = 0;
        consolidationMode = false;
        completedTasks = 0;
        totalTasks = 0;
        containerDataReceived = false;
        openContainerId = -1;

        state = State.PLANNING;
        emit("organize_started", Map.of(
            "region_pos1", posString(config.pos1),
            "region_pos2", posString(config.pos2)
        ));
        return true;
    }

    public void stop() {
        BARITONE.stop();
        closeCurrentContainer();
        state = State.IDLE;
        taskQueue.clear();
        consolidationQueue.clear();
        consolidationMode = false;
        currentTask = null;
        overflowItems.clear();
        columnAssignment.clear();
        emit("organize_stopped", Map.of("reason", "manual_stop"));
        info("Organizer stopped.");
    }

    // Receives container data from module packet handler.
    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        this.serverSession = session;
        this.openContainerId = packet.getContainerId();
        this.containerStateId = packet.getStateId();
        this.containerSlots = packet.getItems();
        this.containerDataReceived = true;
    }

    // Tick
    public void tick() {
        if (state == State.IDLE || state == State.DONE) return;

        switch (state) {
            case PLANNING            -> tickPlanning();
            case WALKING             -> tickWalking();
            case OPENING             -> tickOpening();
            case TAKING              -> tickTaking();
            case CLOSING_SOURCE      -> tickClosingSource();
            case DEPOSITING          -> tickDepositing();
            case CLOSING_DEST        -> tickClosingDest();
            case SHULKER_SELECTING   -> tickShulkerSelecting();
            case SHULKER_PLACING     -> tickShulkerPlacing();
            case SHULKER_WAIT_PLACE  -> tickShulkerWaitPlace();
            case SHULKER_OPENING     -> tickShulkerOpening();
            case SHULKER_FILLING     -> tickShulkerFilling();
            case SHULKER_CLOSING     -> tickShulkerClosing();
            case SHULKER_BREAKING    -> tickShulkerBreaking();
            case SHULKER_PICKUP      -> tickShulkerPickup();
            case SHULKER_FETCH_WALK  -> tickWalking();
            case SHULKER_FETCH_OPEN  -> tickShulkerFetchOpen();
            case SHULKER_FETCH_TAKE  -> tickShulkerFetchTake();
            case SHULKER_STORE_WALK  -> tickWalking();
            case SHULKER_STORE_OPEN  -> tickShulkerStoreOpen();
            case SHULKER_STORE_DEPOSIT -> tickShulkerStoreDeposit();
            case CRAFT_MATERIAL_WALK -> tickWalking();
            case CRAFT_MATERIAL_OPEN -> tickCraftMaterialOpen();
            case CRAFT_MATERIAL_TAKE -> tickCraftMaterialTake();
            case CRAFT_WALKING       -> tickWalking();
            case CRAFT_OPENING       -> tickCraftOpening();
            case CRAFT_PLACING       -> tickCraftPlacing();
            case CRAFT_TAKING        -> tickCraftTaking();
            case OVERFLOW_WALKING    -> tickWalking();
            case OVERFLOW_OPENING    -> tickOverflowOpening();
            case OVERFLOW_DEPOSITING -> tickOverflowDepositing();
            default -> {}
        }
    }

    // PLANNING
    private void tickPlanning() {
        List<ContainerEntry> regionContainers = index.getInRegion(config.pos1, config.pos2);

        if (regionContainers.isEmpty()) {
            info("No containers in region. Index has " + index.size()
                    + " containers total. Check that pos1/pos2 cover the scanned area.");
            emit("organize_failed", Map.of("reason", "no_containers_in_region"));
            state = State.DONE;
            return;
        }

        info("Analyzing " + regionContainers.size() + " containers in region...");

        // Build position map
        Map<Long, ContainerEntry> byPos = new LinkedHashMap<>();
        for (ContainerEntry entry : regionContainers) {
            byPos.put(posKey(entry.x(), entry.y(), entry.z()), entry);
        }

        // Step 1: Detect columns (connected-component grouping)
        Set<int[]> positions = regionContainers.stream()
                .map(e -> new int[]{e.x(), e.y(), e.z()})
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Column> columns = detectColumns(positions);

        // Build lookup: posKey → column
        Map<Long, Column> posToColumn = new HashMap<>();
        for (Column col : columns) {
            for (int[] p : col.chests()) {
                posToColumn.put(posKey(p[0], p[1], p[2]), col);
            }
        }

        // Step 2: Map items to locations (accessible items only)
        Map<String, List<ItemLocation>> itemLocations = new LinkedHashMap<>();

        for (ContainerEntry container : regionContainers) {
            int[] pos = {container.x(), container.y(), container.z()};
            Map<String, Integer> accessible = new HashMap<>(container.items());

            // Subtract items stored inside shulkers
            for (ContainerEntry.ShulkerDetail sd : container.shulkerDetails()) {
                for (var sdEntry : sd.items().entrySet()) {
                    accessible.computeIfPresent(sdEntry.getKey(), (k, v) -> {
                        int remaining = v - sdEntry.getValue();
                        return remaining > 0 ? remaining : null;
                    });
                }
            }

            // Shulker items handled separately
            accessible.keySet().removeIf(StashOrganizer::isShulkerBoxItem);

            for (var item : accessible.entrySet()) {
                itemLocations.computeIfAbsent(item.getKey(), k -> new ArrayList<>())
                        .add(new ItemLocation(pos, item.getValue()));
            }
        }

        // Step 2b: Map filled shulkers by primary content
        record ShulkerLoc(int[] pos, String shulkerType, String primaryContent) {}
        Map<String, List<ShulkerLoc>> shulkersByContent = new LinkedHashMap<>();

        for (ContainerEntry container : regionContainers) {
            int[] pos = {container.x(), container.y(), container.z()};
            for (ContainerEntry.ShulkerDetail sd : container.shulkerDetails()) {
                String primary = getPrimaryContent(sd.items());
                if (primary != null) {
                    shulkersByContent.computeIfAbsent(primary, k -> new ArrayList<>())
                            .add(new ShulkerLoc(pos, sd.color(), primary));
                }
            }
        }

        // Weight columns by shulker contents
        for (var entry : shulkersByContent.entrySet()) {
            itemLocations.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
        }

        // Step 3: Assign items to columns (largest volume first)
        columnAssignment = new LinkedHashMap<>();
        Set<Integer> assignedColumnIds = new HashSet<>();

        List<Map.Entry<String, List<ItemLocation>>> sortedItems =
                new ArrayList<>(itemLocations.entrySet());
        sortedItems.sort((a, b) -> {
            int totalA = a.getValue().stream().mapToInt(ItemLocation::quantity).sum();
            int totalB = b.getValue().stream().mapToInt(ItemLocation::quantity).sum();
            return Integer.compare(totalB, totalA);
        });

        int shared = 0;
        Set<String> sharedItemIds = new HashSet<>();
        for (var entry : sortedItems) {
            String itemId = entry.getKey();
            List<ItemLocation> locations = entry.getValue();
            locations.sort(Comparator.comparingInt(ItemLocation::quantity).reversed());

            // Prefer column with highest quantity of this item
            Column assigned = null;
            for (ItemLocation loc : locations) {
                Column col = posToColumn.get(posKey(loc.pos()[0], loc.pos()[1], loc.pos()[2]));
                if (col != null && !assignedColumnIds.contains(col.id())) {
                    assigned = col;
                    break;
                }
            }

            // Fallback: any unassigned column
            if (assigned == null) {
                for (Column col : columns) {
                    if (!assignedColumnIds.contains(col.id())) {
                        assigned = col;
                        break;
                    }
                }
            }

            // No free columns — share assignment
            if (assigned == null) {
                if (!locations.isEmpty()) {
                    Column col = posToColumn.get(posKey(
                            locations.get(0).pos()[0], locations.get(0).pos()[1], locations.get(0).pos()[2]));
                    if (col != null) assigned = col;
                }
                sharedItemIds.add(itemId);
                shared++;
            }

            if (assigned != null) {
                columnAssignment.put(itemId, assigned);
                assignedColumnIds.add(assigned.id());
            }
        }

        // Step 4: Generate move tasks
        taskQueue.clear();
        consolidationQueue.clear();

        int condenseTypes = 0;
        for (var entry : columnAssignment.entrySet()) {
            String itemId = entry.getKey();
            Column col = entry.getValue();
            Set<Long> columnChestKeys = new HashSet<>();
            for (int[] p : col.chests()) columnChestKeys.add(posKey(p[0], p[1], p[2]));

            List<ItemLocation> locations = itemLocations.get(itemId);
            if (locations == null || locations.isEmpty()) continue;

            int totalLoose = locations.stream().mapToInt(ItemLocation::quantity).sum();

            if (sharedItemIds.contains(itemId)) {
                // Shared: pack into mixed shulkers
                for (ItemLocation loc : locations) {
                    consolidationQueue.add(new MoveTask(loc.pos(), col.top(), itemId));
                }
            } else if (totalLoose >= config.condenseMinItems) {
                // Condense loose items into shulkers
                for (ItemLocation loc : locations) {
                    consolidationQueue.add(new MoveTask(loc.pos(), col.top(), itemId));
                }
                condenseTypes++;
            } else {
                // Move loose items to assigned column
                for (ItemLocation loc : locations) {
                    if (!columnChestKeys.contains(posKey(loc.pos()[0], loc.pos()[1], loc.pos()[2]))) {
                        taskQueue.add(new MoveTask(loc.pos(), col.top(), itemId));
                    }
                }
            }
        }

        // Batch consolidation by item type
        if (!consolidationQueue.isEmpty()) {
            List<MoveTask> sorted = new ArrayList<>(consolidationQueue);
            sorted.sort(Comparator.comparing(MoveTask::itemId));
            consolidationQueue.clear();
            consolidationQueue.addAll(sorted);
        }

        // Step 4b: Move filled shulkers to matching columns
        int shulkerMoves = 0;
        for (var entry : shulkersByContent.entrySet()) {
            String contentType = entry.getKey();
            Column col = columnAssignment.get(contentType);
            if (col == null) continue;
            Set<Long> columnChestKeys = new HashSet<>();
            for (int[] p : col.chests()) columnChestKeys.add(posKey(p[0], p[1], p[2]));

            for (ShulkerLoc sl : entry.getValue()) {
                if (!columnChestKeys.contains(posKey(sl.pos()[0], sl.pos()[1], sl.pos()[2]))) {
                    taskQueue.add(new MoveTask(sl.pos(), col.top(),
                            "minecraft:" + sl.shulkerType() + "_shulker_box", contentType));
                    shulkerMoves++;
                }
            }
        }

        totalTasks = taskQueue.size();
        completedTasks = 0;

        if (taskQueue.isEmpty() && consolidationQueue.isEmpty()) {
            info("Stash is already organized! (" + regionContainers.size() + " containers in "
                    + columns.size() + " columns, " + itemLocations.size() + " item types)");
            state = State.DONE;
            emit("organize_completed", Map.of(
                "overflow_types", 0
            ));
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Planned ").append(totalTasks).append(" moves across ")
                .append(columns.size()).append(" columns (")
                .append(columnAssignment.size()).append(" types");
        if (condenseTypes > 0) summary.append(", ").append(condenseTypes).append(" to condense");
        if (shared > 0) summary.append(", ").append(shared).append(" to consolidate");
        if (shulkerMoves > 0) summary.append(", ").append(shulkerMoves).append(" shulker sorts");
        summary.append(").");
        info(summary.toString());
        emit("organize_planned", Map.of(
            "planned_moves", totalTasks,
            "columns", columns.size(),
            "item_types", itemLocations.size(),
            "condense_types", condenseTypes,
            "shared_types", shared,
            "shulker_moves", shulkerMoves
        ));

        if (!consolidationQueue.isEmpty()) {
            info(consolidationQueue.size() + " condensing tasks (will pack loose items into shulker boxes).");
        }

        advanceToNextTask();
    }

    // Column Detection
    static List<Column> detectColumns(Set<int[]> positions) {
        // Build a list for safe iteration
        List<int[]> remaining = new ArrayList<>(positions);
        List<Column> columns = new ArrayList<>();
        int nextId = 0;

        while (!remaining.isEmpty()) {
            int[] seed = remaining.remove(0);
            List<int[]> component = new ArrayList<>();
            component.add(seed);

            Deque<int[]> frontier = new ArrayDeque<>();
            frontier.add(seed);

            while (!frontier.isEmpty()) {
                int[] current = frontier.poll();
                Iterator<int[]> it = remaining.iterator();
                while (it.hasNext()) {
                    int[] candidate = it.next();
                    int dx = Math.abs(candidate[0] - current[0]);
                    int dz = Math.abs(candidate[2] - current[2]);
                    int dy = Math.abs(candidate[1] - current[1]);
                    // Adjacent: horiz ≤ 1, vert 1–2
                    if (dx <= 1 && dz <= 1 && dy >= 1 && dy <= 2) {
                        it.remove();
                        component.add(candidate);
                        frontier.add(candidate);
                    }
                }
            }

            // Top-down order (highest Y first)
            component.sort(Comparator.<int[]>comparingInt(p -> p[1]).reversed());
            columns.add(new Column(nextId++, component));
        }

        return columns;
    }

    // WALKING
    private void tickWalking() {
        if (walkTarget == null) {
            advanceToNextTask();
            return;
        }

        long targetKey = posKey(walkTarget[0], walkTarget[1], walkTarget[2]);
        if (targetKey != trackedWalkTargetKey) {
            trackedWalkTargetKey = targetKey;
            walkingTicks = 0;
        }
        walkingTicks++;

        double dist = distanceTo(walkTarget);
        if (dist <= 5.0) {
            BARITONE.stop();
            onArrived();
            return;
        }

        if (walkingTicks > config.organizerWalkTimeoutTicks) {
            info("Timeout walking to container, skipping.");
            emit("organize_target_failed", Map.of("reason", "walk_timeout"));
            BARITONE.stop();
            trackedWalkTargetKey = Long.MIN_VALUE;
            advanceToNextTask();
            return;
        }

        if (!BARITONE.getCustomGoalProcess().isActive()) {
            pathToWalkTarget();
        }
    }

    private void pathToWalkTarget() {
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2])));
    }

    private void onArrived() {
        openWaitTicks = 0;
        containerDataReceived = false;
        switch (state) {
            case WALKING              -> {
                state = State.OPENING;
                interactWithBlock(walkTarget);
            }
            case SHULKER_FETCH_WALK   -> {
                state = State.SHULKER_FETCH_OPEN;
                interactWithBlock(walkTarget);
            }
            case SHULKER_STORE_WALK   -> {
                state = State.SHULKER_STORE_OPEN;
                interactWithBlock(walkTarget);
            }
            case OVERFLOW_WALKING     -> {
                state = State.OVERFLOW_OPENING;
                interactWithBlock(walkTarget);
            }
            default -> {
                state = State.OPENING;
                interactWithBlock(walkTarget);
            }
        }
    }

    // OPENING
    private void tickOpening() {
        openWaitTicks++;

        if (containerDataReceived) {
            actionSlotIndex = 0;
            actionCooldown = 0;
            state = (currentRole == TargetRole.SOURCE) ? State.TAKING : State.DEPOSITING;
            return;
        }

        if (openWaitTicks > config.organizerOpenTimeoutTicks) {
            info("Timeout opening container, skipping.");
            emit("organize_failed", Map.of("reason", "open_timeout"));
            advanceToNextTask();
        }
    }

    // TAKING (container → player inventory via shift-click)
    private void tickTaking() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            transitionToDestination();
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (currentTask != null && itemId.equals(currentTask.itemId())) {
                    // Skip non-shulker items when content-filtering
                    if (currentTask.shulkerContentFilter() != null
                            && !isShulkerBoxItem(itemId)) {
                        actionSlotIndex++;
                        continue;
                    }

                    if (!hasInventoryRoom()) {
                        break;
                    }

                    quickMoveSlot(actionSlotIndex);
                    actionSlotIndex++;
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        state = State.CLOSING_SOURCE;
        closeCurrentContainer();
    }

    private void tickClosingSource() {
        // Brief pause after close before advancing
        actionCooldown++;
        if (actionCooldown >= 3) {
            actionCooldown = 0;
            // Always transition to destination to deposit items
            transitionToDestination();
        }
    }

    // DEPOSITING (player inventory → container via shift-click)
    private void tickDepositing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        // Window layout: [chest slots][player inv 27][hotbar 9]

        int playerSlot = Math.max(HOTBAR_SIZE, actionSlotIndex); // slot 9 — skip hotbar
        while (playerSlot < 36) {
            // Read player inv from cache
            var invCache = CACHE.getPlayerCache().getInventoryCache();
            var playerContainer = invCache.getPlayerInventory();
            if (playerContainer == null) break;

            ItemStack stack = playerContainer.getItemStack(playerSlot);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (currentTask != null && itemId.equals(currentTask.itemId())) {
                    if (currentTask.shulkerContentFilter() != null
                            && !isShulkerBoxItem(itemId)) {
                        playerSlot++;
                        continue;
                    }

                    // Check if chest has room before depositing
                    if (!hasChestRoom()) {
                        // Chest full — try cascading to next chest in column
                        closeCurrentContainer();
                        if (cascadeToNextInColumn()) {
                            return;
                        }
                        // No more chests in column — start shulker packing
                        startShulkerPacking(currentTask.itemId(), currentTask.destination());
                        return;
                    }

                    // Map player slot → container window slot
                    int containerSlotIndex;
                    if (playerSlot < 9) {
                        containerSlotIndex = chestSlots + 27 + playerSlot; // hotbar
                    } else {
                        containerSlotIndex = chestSlots + playerSlot - 9; // main inventory
                    }

                    quickMoveSlot(containerSlotIndex);
                    playerSlot++;
                    actionCooldown = config.organizerClickCooldownTicks;
                    // Resume from this slot next tick
                    actionSlotIndex = playerSlot;
                    return;
                }
            }
            playerSlot++;
        }

        // Done depositing
        state = State.CLOSING_DEST;
        closeCurrentContainer();
        actionCooldown = 0;
    }

    private void tickClosingDest() {
        actionCooldown++;
        if (actionCooldown >= 3) {
            actionCooldown = 0;
            completedTasks++;

            if (completedTasks % 5 == 0 || completedTasks == totalTasks) {
                info("Progress: " + completedTasks + "/" + totalTasks);
                emit("organize_progress", Map.of());
            }

            advanceToNextTask();
        }
    }

    // SHULKER FETCH — take an empty shulker from a region container
    private void tickShulkerFetchOpen() {
        openWaitTicks++;

        if (containerDataReceived) {
            actionSlotIndex = 0;
            actionCooldown = 0;
            state = State.SHULKER_FETCH_TAKE;
            return;
        }

        if (openWaitTicks > config.organizerOpenTimeoutTicks) {
            info("Timeout opening container for shulker fetch.");
            emit("organize_failed", Map.of("reason", "shulker_fetch_open_timeout"));
            startOverflow();
        }
    }

    private void tickShulkerFetchTake() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            startOverflow();
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (isShulkerBoxItem(itemId)) {
                    // Take the shulker
                    quickMoveSlot(actionSlotIndex);
                    actionCooldown = config.organizerClickCooldownTicks;
                    closeCurrentContainer();

                    if (consolidationMode) {
                        advanceConsolidation();
                    } else {
                        advanceToNextTask();
                    }
                    return;
                }
            }
            actionSlotIndex++;
        }

        // No shulker found
        closeCurrentContainer();
        startOverflow();
    }

    // SHULKER STORE — deposit filled shulker into destination
    private void tickShulkerStoreOpen() {
        openWaitTicks++;

        if (containerDataReceived) {
            state = State.SHULKER_STORE_DEPOSIT;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            return;
        }

        if (openWaitTicks > config.organizerOpenTimeoutTicks) {
            info("Timeout opening destination for shulker deposit.");
            emit("organize_failed", Map.of("reason", "shulker_store_open_timeout"));
            advanceToNextTask();
        }
    }

    private void tickShulkerStoreDeposit() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        // Deposit shulkers from player inventory
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) {
            closeCurrentContainer();
            advanceToNextTask();
            return;
        }

        while (actionSlotIndex < 36) {
            ItemStack stack = playerContainer.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (isShulkerBoxItem(itemId)) {
                    int containerSlotIndex;
                    if (actionSlotIndex < 9) {
                        containerSlotIndex = chestSlots + 27 + actionSlotIndex;
                    } else {
                        containerSlotIndex = chestSlots + actionSlotIndex - 9;
                    }

                    quickMoveSlot(containerSlotIndex);
                    actionSlotIndex++;
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        closeCurrentContainer();
        completedTasks++;

        if (consolidationMode) {
            if (!consolidationQueue.isEmpty()) {
                advanceConsolidation();
            } else {
                consolidationMode = false;
                finishOrganization();
            }
        } else {
            advanceToNextTask();
        }
    }

    // SHULKER PACKING CYCLE
    private void startShulkerPacking(String itemId, int[] destination) {
        this.packItemId = itemId;
        this.packDestination = destination;
        info("Starting shulker packing for: " + itemId);
        state = State.SHULKER_SELECTING;
        shulkerTicks = 0;
    }

    private void tickShulkerSelecting() {
        shulkerTicks++;
        
        // Find empty shulker in inventory
        int shulkerSlot = findEmptyShulkerInInventory();
        if (shulkerSlot < 0) {
            // Need to fetch from region or craft
            if (hasEmptyShulkerInRegion()) {
                startFetchShulker();
                return;
            }
            if (canCraftShulkers()) {
                startCrafting();
                return;
            }
            info("No empty shulkers available - overflow");
            startOverflow();
            return;
        }

        // Find placement spot
        shulkerPlacePos = findShulkerPlaceSpot();
        if (shulkerPlacePos == null) {
            info("No suitable spot to place shulker");
            startOverflow();
            return;
        }

        // Save player rotation
        var player = CACHE.getPlayerCache();
        savedYaw = player.getYaw();
        savedPitch = player.getPitch();

        ItemStack shulkerStack = getPlayerInventoryStack(shulkerSlot);
        packShulkerItemData = shulkerStack == null ? null : ItemRegistry.REGISTRY.get(shulkerStack.getId());
        if (packShulkerItemData == null) {
            info("Could not resolve shulker item data");
            startOverflow();
            return;
        }
        moveShulkerToHotbar(shulkerSlot);

        state = State.SHULKER_PLACING;
        shulkerTicks = 0;
        shulkerPlaceRetries = 0;
        shulkerPlaceFuture = null;
    }

    private void tickShulkerPlacing() {
        shulkerTicks++;

        if (isShulkerAtPosition(shulkerPlacePos)) {
            state = State.SHULKER_OPENING;
            shulkerTicks = 0;
            openWaitTicks = 0;
            containerDataReceived = false;
            shulkerPlaceFuture = null;
            return;
        }

        if (shulkerTicks > config.organizerOpenTimeoutTicks) {
            info("Shulker placement timed out");
            emit("organize_failed", Map.of("reason", "shulker_place_timeout"));
            startOverflow();
            return;
        }

        if (shulkerPlaceFuture == null) {
            shulkerPlaceFuture = BaritoneCompat.placeBlock(
                shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], packShulkerItemData);
            state = State.SHULKER_WAIT_PLACE;
            shulkerTicks = 0;
        }
    }

    private void tickShulkerWaitPlace() {
        shulkerTicks++;
        
        // Check if shulker placed successfully
        if (isShulkerAtPosition(shulkerPlacePos)) {
            state = State.SHULKER_OPENING;
            shulkerTicks = 0;
            openWaitTicks = 0;
            containerDataReceived = false;
            return;
        }

        if (shulkerPlaceFuture != null && shulkerPlaceFuture.isDone() && !shulkerPlaceFuture.getNow()) {
            shulkerPlaceRetries++;
            shulkerPlaceFuture = null;
            if (shulkerPlaceRetries > 3) {
                info("Shulker placement rejected after 3 attempts");
                startOverflow();
            } else {
                state = State.SHULKER_PLACING;
                shulkerTicks = 0;
            }
            return;
        }

        if (shulkerTicks > config.organizerOpenTimeoutTicks) {
            shulkerPlaceRetries++;
            if (shulkerPlaceRetries > 3) {
                info("Shulker placement verification timeout");
                startOverflow();
            } else {
                shulkerPlaceFuture = null;
                state = State.SHULKER_PLACING;
                shulkerTicks = 0;
            }
        }
    }

    private void tickShulkerOpening() {
        openWaitTicks++;

        if (containerDataReceived && openContainerId >= 0) {
            state = State.SHULKER_FILLING;
            actionSlotIndex = 9;
            actionCooldown = 0;
            return;
        }

        if (openWaitTicks > config.organizerOpenTimeoutTicks) {
            info("Timeout opening placed shulker");
            emit("organize_failed", Map.of("reason", "shulker_open_timeout"));
            startOverflow();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % 10 == 0) {
            BARITONE.rightClickBlock(shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2]);
        }
    }

    private void tickShulkerFilling() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            startOverflow();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(); // Should be 27 for shulker

        // Deposit packItemId from player inventory into shulker
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) {
            closeCurrentContainer();
            state = State.SHULKER_CLOSING;
            shulkerTicks = 0;
            return;
        }

        // Check if shulker is full
        boolean shulkerFull = true;
        for (int i = 0; i < chestSlots; i++) {
            if (containerSlots[i] == null || containerSlots[i].getAmount() == 0) {
                shulkerFull = false;
                break;
            }
        }

        if (shulkerFull) {
            closeCurrentContainer();
            state = State.SHULKER_CLOSING;
            shulkerTicks = 0;
            return;
        }

        while (actionSlotIndex < 36) {
            ItemStack stack = playerContainer.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (itemId.equals(packItemId)) {
                    int containerSlotIndex;
                    if (actionSlotIndex < 9) {
                        containerSlotIndex = chestSlots + 27 + actionSlotIndex;
                    } else {
                        containerSlotIndex = chestSlots + actionSlotIndex - 9;
                    }

                    quickMoveSlot(containerSlotIndex);
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        // No more items to pack
        closeCurrentContainer();
        state = State.SHULKER_CLOSING;
        shulkerTicks = 0;
    }

    private void tickShulkerClosing() {
        shulkerTicks++;

        if (shulkerTicks < 3) return; // Wait after closing

        // Select best tool for breaking
        // (In Zenith context, we may not have tool selection; skip for now)

        state = State.SHULKER_BREAKING;
        shulkerTicks = 0;
    }

    private void tickShulkerBreaking() {
        shulkerTicks++;

        if (!isShulkerAtPosition(shulkerPlacePos)) {
            state = State.SHULKER_PICKUP;
            shulkerTicks = 0;
            shulkerBreakFuture = null;
            return;
        }

        if (shulkerTicks > BREAK_TIMEOUT_TICKS) {
            info("Shulker breaking timed out");
            emit("organize_failed", Map.of("reason", "shulker_break_timeout"));
            startOverflow();
            return;
        }

        if (shulkerBreakFuture == null) {
            shulkerBreakFuture = BaritoneCompat.breakBlock(
                shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], true);
            return;
        }

        if (shulkerBreakFuture.isDone() && !shulkerBreakFuture.getNow()) {
            info("Shulker breaking was rejected");
            emit("organize_failed", Map.of("reason", "shulker_break_rejected"));
            startOverflow();
        }
    }

    private void tickShulkerPickup() {
        shulkerTicks++;

        if (shulkerTicks >= PICKUP_DELAY_TICKS) {
            // Check if we have filled shulker in inventory
            if (hasFilledShulkerInInventory()) {
                // Store it in destination
                walkTarget = packDestination;
                state = State.SHULKER_STORE_WALK;
                openWaitTicks = 0;
                containerDataReceived = false;
            } else {
                info("Shulker pickup failed");
                startOverflow();
            }
        }
    }

    // CRAFTING SHULKER BOXES
    private void startCrafting() {
        craftingTablePos = findCraftingTable();
        if (craftingTablePos == null) {
            info("No crafting table found in region");
            startOverflow();
            return;
        }

        // Count materials
        int shellsInRegion = countItemInRegion("minecraft:shulker_shell");
        int chestsInRegion = countItemInRegion("minecraft:chest");
        int shellsInInv = countItemInInventory("minecraft:shulker_shell");
        int chestsInInv = countItemInInventory("minecraft:chest");
        int totalShells = shellsInRegion + shellsInInv;
        int totalChests = chestsInRegion + chestsInInv;
        shulkersToCraft = Math.min(totalShells / 2, totalChests);

        if (shulkersToCraft <= 0) {
            startOverflow();
            return;
        }

        info("Crafting " + shulkersToCraft + " shulker boxes");

        // Check if materials in inventory
        if (hasShulkerMaterialsInInventory()) {
            walkTarget = craftingTablePos;
            state = State.CRAFT_WALKING;
            openWaitTicks = 0;
            return;
        }

        // Need to collect materials
        shellsNeeded = shulkersToCraft * 2 - shellsInInv;
        chestsNeeded = shulkersToCraft - chestsInInv;

        materialSources.clear();
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                if (container.items().containsKey("minecraft:shulker_shell")
                        || container.items().containsKey("minecraft:chest")) {
                    materialSources.add(new int[]{container.x(), container.y(), container.z()});
                }
            }
        }

        if (materialSources.isEmpty()) {
            startOverflow();
            return;
        }

        info("Collecting crafting materials");
        walkTarget = materialSources.poll();
        state = State.CRAFT_MATERIAL_WALK;
        openWaitTicks = 0;
        containerDataReceived = false;
    }

    private void tickCraftMaterialOpen() {
        // Note: Crafting requires container interaction not fully supported yet
        info("Crafting shulker boxes not fully implemented in proxy context");
        advanceCraftMaterial();
    }

    private void tickCraftMaterialTake() {
        // Note: Crafting requires container interaction not fully supported yet
        info("Crafting shulker boxes not fully implemented in proxy context");
        advanceCraftMaterial();
    }

    private void advanceCraftMaterial() {
        if (!materialSources.isEmpty()) {
            walkTarget = materialSources.poll();
            state = State.CRAFT_MATERIAL_WALK;
            openWaitTicks = 0;
            containerDataReceived = false;
            return;
        }

        // Check if we have enough for at least one
        if (hasShulkerMaterialsInInventory()) {
            int shellsHave = countItemInInventory("minecraft:shulker_shell");
            int chestsHave = countItemInInventory("minecraft:chest");
            shulkersToCraft = Math.min(shellsHave / 2, chestsHave);
            walkTarget = craftingTablePos;
            state = State.CRAFT_WALKING;
            openWaitTicks = 0;
            containerDataReceived = false;
        } else {
            startOverflow();
        }
    }

    private void tickCraftOpening() {
        // Note: Crafting requires container interaction not fully supported yet
        info("Crafting shulker boxes not fully implemented in proxy context");
        startOverflow();
    }

    private void tickCraftPlacing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            startOverflow();
            return;
        }

        // Place materials in crafting grid (slots 1, 5, 9 for shell, chest, shell)
        // This is complex and requires specific slot manipulation
        // For now, skip the detailed implementation and move to overflow
        info("Crafting table interaction not fully implemented yet");
        closeCurrentContainer();
        startOverflow();
    }

    private void tickCraftTaking() {
        // Note: Crafting requires container interaction not fully supported yet
        info("Crafting shulker boxes not fully implemented in proxy context");
        startOverflow();
    }

    // OVERFLOW
    private void startOverflow() {
        overflowChestPos = findOverflowChest();
        if (overflowChestPos == null) {
            info("No chest available for overflow items!");
            emit("organize_failed", Map.of("reason", "overflow_chest_missing"));
            advanceToNextTask();
            return;
        }

        info("Overflow: depositing remaining items into overflow chest.");
        walkTarget = overflowChestPos;
        state = State.OVERFLOW_WALKING;
        openWaitTicks = 0;
        containerDataReceived = false;
    }

    private void tickOverflowOpening() {
        openWaitTicks++;

        if (containerDataReceived) {
            state = State.OVERFLOW_DEPOSITING;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            return;
        }

        if (openWaitTicks > config.organizerOpenTimeoutTicks) {
            info("Timeout opening overflow chest.");
            emit("organize_failed", Map.of("reason", "overflow_open_timeout"));
            advanceToNextTask();
        }
    }

    private void tickOverflowDepositing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        if (containerSlots == null || openContainerId < 0) {
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount();

        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) {
            closeCurrentContainer();
            advanceToNextTask();
            return;
        }

        // Deposit all items from inventory
        while (actionSlotIndex < 36) {
            ItemStack stack = playerContainer.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                int containerSlotIndex;
                if (actionSlotIndex < 9) {
                    containerSlotIndex = chestSlots + 27 + actionSlotIndex;
                } else {
                    containerSlotIndex = chestSlots + actionSlotIndex - 9;
                }

                quickMoveSlot(containerSlotIndex);
                actionSlotIndex++;
                actionCooldown = config.organizerClickCooldownTicks;
                return;
            }
            actionSlotIndex++;
        }

        closeCurrentContainer();
        advanceToNextTask();
    }

    // Consolidation
    private void advanceConsolidation() {
        // All collected → done
        if (consolidationQueue.isEmpty()) {
            consolidationMode = false;
            finishOrganization();
            return;
        }

        // Next batch
        currentTask = consolidationQueue.poll();
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
    }

    // Navigation
    private void transitionToDestination() {
        if (currentTask == null) {
            advanceToNextTask();
            return;
        }
        currentRole = TargetRole.DESTINATION;
        walkTarget = currentTask.destination();
        actionSlotIndex = 0;
        depositColumnIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
    }

    private void advanceToNextTask() {
        // If in consolidation mode, advance within consolidation queue
        if (consolidationMode) {
            advanceConsolidation();
            return;
        }

        if (taskQueue.isEmpty()) {
            if (!consolidationQueue.isEmpty()) {
                consolidationMode = true;
                info("Starting condensing — packing loose items into shulker boxes...");
                advanceConsolidation();
                return;
            }
            finishOrganization();
            return;
        }

        currentTask = taskQueue.poll();
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
    }

    private void finishOrganization() {
        BARITONE.stop();
        state = State.DONE;
        emit("organize_completed", Map.of(
            "overflow_types", overflowItems.size()
        ));
        info("Organization complete! " + completedTasks + " moves executed.");

        if (!overflowItems.isEmpty()) {
            info(overflowItems.size() + " item types overflowed.");
        }

        // Auto-label organized columns
        index.assignLabels();

        info("Run /stash scan to refresh the index.");
    }

    // Container Interaction
    private void interactWithBlock(int[] pos) {
        try {
            BARITONE.rightClickBlock(pos[0], pos[1], pos[2]);
        } catch (Exception e) {
            info("Failed to interact with block at " + posString(pos) + ": " + e.getMessage());
            emit("organize_failed", Map.of(
                "reason", "interact_failed",
                "walk_target", posString(pos),
                "message", e.getMessage()
            ));
            advanceToNextTask();
        }
    }

    private void closeCurrentContainer() {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer())
                    .priority(5000)
                    .build());
        } catch (Exception ignored) {}
        containerDataReceived = false;
        openContainerId = -1;
    }

    private int getOpenContainerSlotCount() {
        if (containerSlots == null) return 0;
        return Math.max(0, containerSlots.length - 36);
    }

    // Shift-click a slot in the open container.
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
            containerStateId++; // keep state ID in sync
        } catch (Exception e) {
            // Container may have closed
        }
    }

    // Inventory Helpers
    private boolean hasInventoryRoom() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        for (int i = HOTBAR_SIZE; i < 36; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
        }
        return false;
    }

    private boolean hasChestRoom() {
        if (containerSlots == null || openContainerId < 0) return false;
        int chestSlots = getOpenContainerSlotCount();
        for (int i = 0; i < chestSlots; i++) {
            ItemStack stack = containerSlots[i];
            if (stack == null || stack.getAmount() == 0) return true;
        }
        return false;
    }

    // Return true after selecting the next chest in the column.
    private boolean cascadeToNextInColumn() {
        if (currentTask == null) return false;
        Column col = columnAssignment.get(currentTask.itemId());
        if (col == null) return false;

        depositColumnIndex++;
        if (depositColumnIndex < col.chests().size()) {
            int[] next = col.chests().get(depositColumnIndex);
            walkTarget = next;
            currentRole = TargetRole.DESTINATION;
            actionSlotIndex = 0;
            containerDataReceived = false;
            state = State.WALKING;
            return true;
        }
        return false;
    }

    // Item Helpers
    private static String itemIdFromStack(ItemStack stack) {
        return ItemIdentifier.getItemId(stack);
    }

    private static boolean isShulkerBoxItem(String itemId) {
        return itemId != null && itemId.contains("shulker_box");
    }

    private static String getPrimaryContent(Map<String, Integer> contents) {
        if (contents == null || contents.isEmpty()) return null;
        return contents.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private int[] findOverflowChest() {
        List<ContainerEntry> region = index.getInRegion(config.pos1, config.pos2);
        for (ContainerEntry entry : region) {
            if (entry.totalItems() < 27 * 64) {
                return new int[]{entry.x(), entry.y(), entry.z()};
            }
        }
        return region.isEmpty() ? null : new int[]{region.get(0).x(), region.get(0).y(), region.get(0).z()};
    }

    // Position Helpers
    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private double distanceTo(int[] pos) {
        var pc = CACHE.getPlayerCache();
        double dx = pc.getX() - pos[0];
        double dy = pc.getY() - pos[1];
        double dz = pc.getZ() - pos[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String posString(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }

    private void info(String message) {
        if (infoCallback != null) infoCallback.info(message);
    }

    private void emit(String event, Map<String, Object> extraFields) {
        if (eventCallback == null) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizer_state", state.name());
        payload.put("completed_tasks", completedTasks);
        payload.put("total_tasks", totalTasks);
        if (currentTask != null) {
            payload.put("item_id", currentTask.itemId());
            payload.put("source_position", posString(currentTask.source()));
            payload.put("destination_position", posString(currentTask.destination()));
            if (currentTask.shulkerContentFilter() != null) {
                payload.put("shulker_content_filter", currentTask.shulkerContentFilter());
            }
        }
        if (walkTarget != null) payload.put("walk_target", posString(walkTarget));
        if (extraFields != null && !extraFields.isEmpty()) payload.putAll(extraFields);
        eventCallback.accept(event, payload);
    }

    // Status
    public String getStatus() {
        String detail = switch (state) {
            case IDLE              -> "Idle";
            case PLANNING          -> "Planning...";
            case WALKING           -> "Walking to "
                    + (currentRole == TargetRole.SOURCE ? "source" : "destination") + "...";
            case OPENING           -> "Opening container...";
            case TAKING            -> "Taking items...";
            case CLOSING_SOURCE    -> "Closing source...";
            case DEPOSITING        -> "Depositing items...";
            case CLOSING_DEST      -> "Closing destination...";
            case SHULKER_SELECTING, SHULKER_PLACING, SHULKER_WAIT_PLACE, SHULKER_OPENING, 
                 SHULKER_FILLING, SHULKER_CLOSING, SHULKER_BREAKING, SHULKER_PICKUP
                                   -> "Packing items into shulker...";
            case SHULKER_FETCH_WALK, SHULKER_FETCH_OPEN, SHULKER_FETCH_TAKE
                                   -> "Fetching empty shulker...";
            case SHULKER_STORE_WALK, SHULKER_STORE_OPEN, SHULKER_STORE_DEPOSIT
                                   -> "Storing filled shulker...";
            case CRAFT_MATERIAL_WALK, CRAFT_MATERIAL_OPEN, CRAFT_MATERIAL_TAKE,
                 CRAFT_WALKING, CRAFT_OPENING, CRAFT_PLACING, CRAFT_TAKING
                                   -> "Crafting shulker boxes...";
            case OVERFLOW_WALKING, OVERFLOW_OPENING, OVERFLOW_DEPOSITING
                                   -> "Depositing overflow items...";
            case DONE              -> "Done";
        };
        if (totalTasks > 0) {
            detail += " [" + completedTasks + "/" + totalTasks + "]";
        }
        return detail;
    }

    // Helper Methods
    private int findEmptyShulkerInInventory() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return -1;

        for (int i = 9; i <= 44; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (isShulkerBoxItem(itemId) && ItemIdentifier.readShulkerContents(stack).isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean hasFilledShulkerInInventory() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        for (int i = 9; i <= 44; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (isShulkerBoxItem(itemId) && !ItemIdentifier.readShulkerContents(stack).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasEmptyShulkerInRegion() {
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                for (String itemId : container.items().keySet()) {
                    if (isShulkerBoxItem(itemId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void startFetchShulker() {
        // Find container with shulker
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                for (String itemId : container.items().keySet()) {
                    if (isShulkerBoxItem(itemId)) {
                        walkTarget = new int[]{container.x(), container.y(), container.z()};
                        state = State.SHULKER_FETCH_WALK;
                        openWaitTicks = 0;
                        containerDataReceived = false;
                        return;
                    }
                }
            }
        }
        startOverflow();
    }

    private int[] findShulkerPlaceSpot() {
        var player = CACHE.getPlayerCache();
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        for (int[] offset : offsets) {
            int x = px + offset[0];
            int y = py;
            int z = pz + offset[1];
            if (!World.isInWorldBounds(x, y, z)) continue;
            var target = World.getBlock(x, y, z);
            var above = World.getBlock(x, y + 1, z);
            var below = World.getBlock(x, y - 1, z);
                if (!BlockCompat.canReplace(target)
                    || !BlockCompat.canReplace(above)
                    || BlockCompat.isAir(below)
                    || !BlockCompat.isSolid(x, y - 1, z)) continue;
            return new int[]{x, y, z};
        }
        return null;
    }

    private boolean isShulkerAtPosition(int[] pos) {
        return pos != null && World.getBlock(pos[0], pos[1], pos[2]).name().contains("shulker_box");
    }

    private ItemStack getPlayerInventoryStack(int slot) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return playerContainer == null ? null : playerContainer.getItemStack(slot);
    }

    private void moveShulkerToHotbar(int slot) {
        try {
            var builder = InventoryActionRequest.builder().owner(this).priority(6000);
            if (slot >= 36 && slot <= 44) {
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.SLOT_7),
                    new SetHeldItem(SHULKER_HOTBAR_SLOT)
                );
            }
            INVENTORY.submit(builder.build());
        } catch (Exception e) {
            info("Failed to move shulker to hotbar: " + e.getMessage());
        }
    }

    private boolean canCraftShulkers() {
        // Check if we have crafting table and materials available in region
        if (findCraftingTable() == null) return false;

        int shells = countItemInRegion("minecraft:shulker_shell") + countItemInInventory("minecraft:shulker_shell");
        int chests = countItemInRegion("minecraft:chest") + countItemInInventory("minecraft:chest");

        return shells >= 2 && chests >= 1;
    }

    private int[] findCraftingTable() {
        // Find crafting table in region
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                // Check if position is a crafting table
                // Simplified: would need block type check
                // For now, return null to skip crafting
            }
        }
        return null;
    }

    private int countItemInRegion(String itemId) {
        int count = 0;
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                Integer qty = container.items().get(itemId);
                if (qty != null) count += qty;
            }
        }
        return count;
    }

    private int countItemInInventory(String itemId) {
        int count = 0;
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack != null && stack.getAmount() > 0) {
                if (itemIdFromStack(stack).equals(itemId)) {
                    count += stack.getAmount();
                }
            }
        }
        return count;
    }

    private boolean hasShulkerMaterialsInInventory() {
        int shells = countItemInInventory("minecraft:shulker_shell");
        int chests = countItemInInventory("minecraft:chest");
        return shells >= 2 && chests >= 1;
    }

    private boolean isInRegion(int x, int y, int z) {
        int minX = Math.min(config.pos1[0], config.pos2[0]);
        int maxX = Math.max(config.pos1[0], config.pos2[0]);
        int minY = Math.min(config.pos1[1], config.pos2[1]);
        int maxY = Math.max(config.pos1[1], config.pos2[1]);
        int minZ = Math.min(config.pos1[2], config.pos2[2]);
        int maxZ = Math.max(config.pos1[2], config.pos2[2]);
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}

