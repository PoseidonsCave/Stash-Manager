package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.network.Session;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.zenith.Globals.*;

/**
 * Plans and executes item-sorting moves across containers in a defined region.
 * State machine: PLANNING → WALKING → OPENING → TAKING → DEPOSITING (repeat).
 */
public final class StashOrganizer {

    // ── State Machine ───────────────────────────────────────────────────

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
        SHULKER_FETCH_WALK,
        SHULKER_FETCH_OPEN,
        SHULKER_FETCH_TAKE,
        SHULKER_STORE_WALK,
        SHULKER_STORE_OPEN,
        SHULKER_STORE_DEPOSIT,
        // Overflow
        OVERFLOW_WALKING,
        OVERFLOW_OPENING,
        OVERFLOW_DEPOSITING,
        DONE
    }

    private enum TargetRole { SOURCE, DESTINATION }

    private State state = State.IDLE;
    private TargetRole currentRole = TargetRole.SOURCE;

    // ── Column Detection ────────────────────────────────────────────────

    record Column(int id, List<int[]> chests) {
        int[] bottom() { return chests.get(chests.size() - 1); }
        int[] top()    { return chests.get(0); }
    }

    // ── Move Tasks ──────────────────────────────────────────────────────

    record MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter) {
        MoveTask(int[] source, int[] destination, String itemId) {
            this(source, destination, itemId, null);
        }
    }

    private record ItemLocation(int[] pos, int quantity) {}

    // ── Configuration / References ──────────────────────────────────────

    private final StashManagerConfig config;
    private final ContainerIndex index;
    private InfoCallback infoCallback;
    private BiConsumer<String, Map<String, Object>> eventCallback;

    // ── Task Queue ──────────────────────────────────────────────────────

    private final Deque<MoveTask> taskQueue = new ArrayDeque<>();
    private final Deque<MoveTask> consolidationQueue = new ArrayDeque<>();
    private MoveTask currentTask;

    private Map<String, Column> columnAssignment = new LinkedHashMap<>();
    private int depositColumnIndex;

    // ── Timing ──────────────────────────────────────────────────────────

    private static final int OPEN_TIMEOUT_TICKS = 60;
    private static final int HOTBAR_SIZE = 9;

    // ── Runtime State ───────────────────────────────────────────────────

    private int[] walkTarget;
    private int openWaitTicks;
    private int actionSlotIndex;
    private int actionCooldown;

    private int totalTasks;
    private int completedTasks;

    private boolean consolidationMode = false;

    // ── Container Interaction State ─────────────────────────────────────

    // Set by module packet handler on container open
    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile int containerStateId = 0;
    private volatile ItemStack[] containerSlots;
    private volatile Session serverSession;

    // ── Overflow ────────────────────────────────────────────────────────

    private int[] overflowChestPos;
    private final Map<String, Integer> overflowItems = new LinkedHashMap<>();

    // ── Callback Interface ──────────────────────────────────────────────

    @FunctionalInterface
    public interface InfoCallback {
        void info(String message);
    }

    // ── Constructor ─────────────────────────────────────────────────────

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

    // ── Public API ──────────────────────────────────────────────────────

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

    // ── Tick ────────────────────────────────────────────────────────────

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
            case SHULKER_FETCH_WALK  -> tickWalking();
            case SHULKER_FETCH_OPEN  -> tickShulkerFetchOpen();
            case SHULKER_FETCH_TAKE  -> tickShulkerFetchTake();
            case SHULKER_STORE_WALK  -> tickWalking();
            case SHULKER_STORE_OPEN  -> tickShulkerStoreOpen();
            case SHULKER_STORE_DEPOSIT -> tickShulkerStoreDeposit();
            case OVERFLOW_WALKING    -> tickWalking();
            case OVERFLOW_OPENING    -> tickOverflowOpening();
            case OVERFLOW_DEPOSITING -> tickOverflowDepositing();
            default -> {}
        }
    }

    // ── PLANNING ────────────────────────────────────────────────────────

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
                            "minecraft:" + sl.shulkerType(), contentType));
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

    // ── Column Detection ────────────────────────────────────────────────

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

    // ── WALKING ─────────────────────────────────────────────────────────

    private void tickWalking() {
        if (walkTarget == null) {
            advanceToNextTask();
            return;
        }

        if (!BARITONE.isActive()) {
            double dist = distanceTo(walkTarget);

            if (dist <= 5.0) {
                onArrived();
                return;
            }

            // Start pathfinding
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

    // ── OPENING ─────────────────────────────────────────────────────────

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

    // ── TAKING (container → player inventory via shift-click) ───────────

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
            if (consolidationMode) {
                advanceConsolidation();
            } else {
                transitionToDestination();
            }
        }
    }

    // ── DEPOSITING (player inventory → container via shift-click) ───────

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

    // ── SHULKER FETCH — take an empty shulker from a region container ───

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

    // ── SHULKER STORE — deposit filled shulker into destination ─────────

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

    // ── OVERFLOW ────────────────────────────────────────────────────────

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

    // ── Consolidation ───────────────────────────────────────────────────

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

    // ── Navigation ──────────────────────────────────────────────────────

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
        if (taskQueue.isEmpty()) {
            if (!consolidationMode && !consolidationQueue.isEmpty()) {
                consolidationMode = true;
                info("Starting condensing — packing loose items into shulker boxes...");
                advanceConsolidation();
                return;
            }
            consolidationMode = false;
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

    // ── Container Interaction ───────────────────────────────────────────

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

    // ── Inventory Helpers ───────────────────────────────────────────────

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

    // ── Item Helpers ────────────────────────────────────────────────────

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

    // ── Position Helpers ────────────────────────────────────────────────

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

    // ── Status ──────────────────────────────────────────────────────────

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
            case SHULKER_FETCH_WALK, SHULKER_FETCH_OPEN, SHULKER_FETCH_TAKE
                                   -> "Fetching empty shulker...";
            case SHULKER_STORE_WALK, SHULKER_STORE_OPEN, SHULKER_STORE_DEPOSIT
                                   -> "Storing filled shulker...";
            case OVERFLOW_WALKING, OVERFLOW_OPENING, OVERFLOW_DEPOSITING
                                   -> "Depositing overflow items...";
            case DONE              -> "Done";
        };
        if (totalTasks > 0) {
            detail += " [" + completedTasks + "/" + totalTasks + "]";
        }
        return detail;
    }
}
