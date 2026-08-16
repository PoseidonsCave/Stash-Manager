package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.ChestType;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerPlugin;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.PathfinderCompat;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.network.Session;

import java.sql.SQLException;
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
        SHULKER_FETCH_CLOSING,
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
    record MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter, boolean alreadyInInventory) {
        MoveTask(int[] source, int[] destination, String itemId) {
            this(source, destination, itemId, null, false);
        }
        MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter) {
            this(source, destination, itemId, shulkerContentFilter, false);
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
    private static final int MIN_OPEN_TIMEOUT_TICKS = 200;
    private static final int OPEN_RETRY_INTERVAL_TICKS = 20;

    // Runtime State
    private int[] walkTarget;
    private long trackedWalkTargetKey = Long.MIN_VALUE;
    private int walkingTicks;
    private int openWaitTicks;
    private int actionSlotIndex;
    private int actionCooldown;
    private final Set<Long> shulkerFetchTriedSources = new HashSet<>();
    private boolean fetchedEmptyShulker;
    // Counts successful clicks during the current TAKING/DEPOSITING container visit. TAKING
    // resumes scanning from wherever it left off each tick rather than from slot 0, so the
    // final tick of a visit naturally finds "nothing left" once everything matching has already
    // been taken — that's normal completion, not a failure, and should only be reported as a
    // failure if nothing was ever moved during the whole visit.
    private int movedThisVisit;
    private boolean sourceVisitFailed;

    private int totalTasks;
    private int completedTasks;

    private boolean consolidationMode = false;
    private boolean savedAllowBreak = true;
    private boolean baritoneBreakingGuardActive = false;
    private boolean savedPlaceBlockSneak = false;
    private boolean placeSneakGuardActive = false;

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
        saveAndDisableBaritoneBreaking();
        saveAndGuardPlaceBlockSneak();
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
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
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
        if (packet.getContainerId() > 0 && packet.getContainerId() == openContainerId) {
            // Refresh the compatibility snapshot for the shulker/crafting states that still
            // consume it. Normal taking/depositing reads Zenith's live open container below.
            this.containerSlots = packet.getItems();
            return;
        }
        // Container clicks deliberately request a full server response. Those follow-up
        // SetContent packets must refresh Zenith's cache, but they are not new opens and must
        // not replace organizer state. A late response after an opening timeout is different:
        // it has genuinely opened a GUI after this state machine moved on. Close that orphaned
        // window immediately, otherwise the next pathing/action job runs behind a chest GUI.
        if (packet.getContainerId() <= 0) return;
        if (!isAwaitingContainerOpen()) {
            closeUnexpectedContainer(packet.getContainerId());
            return;
        }
        this.serverSession = session;
        this.openContainerId = packet.getContainerId();
        this.containerSlots = packet.getItems();
        this.containerDataReceived = true;
    }

    private boolean isAwaitingContainerOpen() {
        return switch (state) {
            case OPENING, SHULKER_FETCH_OPEN, SHULKER_STORE_OPEN, SHULKER_OPENING,
                 CRAFT_MATERIAL_OPEN, CRAFT_OPENING, OVERFLOW_OPENING -> true;
            default -> false;
        };
    }

    // Tick
    public void tick() {
        if (state == State.IDLE || state == State.DONE) return;

        setBaritoneBreakingAllowed(state == State.SHULKER_BREAKING);
        // Sneak while placing so the right-click places the shulker instead of opening
        // whatever interactive block (chest, barrel, etc.) happens to be nearby.
        setPlaceBlockSneak(state == State.SHULKER_PLACING || state == State.SHULKER_WAIT_PLACE);

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
            case SHULKER_FETCH_CLOSING -> tickShulkerFetchClosing();
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
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            return;
        }

        info("Analyzing " + regionContainers.size() + " containers in region...");

        // A double chest is one physical inventory even though both block entities can remain
        // in an incrementally refreshed index. Keep the newer half for content planning while
        // retaining the full geometry list for hopper-lane detection.
        List<ContainerEntry> planningContainers = deduplicateDoubleChestInventories(regionContainers);

        // Step 1: Detect the actual hopper-fed staircase lanes. A geometric connected-
        // component pass splits the two halves of double chests, cross-links neighboring
        // staircases, and even admitted placed shulker block entities as destination columns.
        // Each hopper step advances two blocks horizontally while dropping one Y level, so
        // hoppers with the same facing/perpendicular coordinate/diagonal invariant form one
        // physical lane. The first position is the lane's top input chest.
        List<Column> columns = detectStaircaseColumns(regionContainers);
        if (columns.isEmpty()) {
            // Compatibility fallback for a stash without hopper staircases. Only permanent
            // storage blocks are eligible destinations; placed shulkers are source contents.
            Set<int[]> positions = regionContainers.stream()
                    .filter(StashOrganizer::isPermanentStorage)
                    .map(e -> new int[]{e.x(), e.y(), e.z()})
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            columns = detectColumns(positions);
        }

        // Build lookup: posKey → column
        Map<Long, Column> posToColumn = new HashMap<>();
        Map<Long, Column> topPosToColumn = new HashMap<>();
        for (Column col : columns) {
            for (int[] p : col.chests()) {
                posToColumn.put(posKey(p[0], p[1], p[2]), col);
            }
            int[] top = col.top();
            topPosToColumn.put(posKey(top[0], top[1], top[2]), col);
        }

        // Step 2: Map items to locations (accessible items only)
        Map<String, List<ItemLocation>> itemLocations = new LinkedHashMap<>();

        for (ContainerEntry container : planningContainers) {
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
        record ShulkerLoc(int[] pos, String shulkerType, String primaryContent, int contentWeight) {}
        Map<String, List<ShulkerLoc>> shulkersByContent = new LinkedHashMap<>();

        for (ContainerEntry container : planningContainers) {
            int[] pos = {container.x(), container.y(), container.z()};
            // Defensive: aggregate by color here too in case shulkerDetails() came from stale
            // (pre-fix) index/db data with one raw entry per physical shulker slot instead of
            // one per color — otherwise a chest with many identical-colored shulkers would
            // generate that many near-duplicate relocation tasks for what should be one move.
            Map<String, Map<String, Integer>> byColor = new LinkedHashMap<>();
            for (ContainerEntry.ShulkerDetail sd : container.shulkerDetails()) {
                var colorItems = byColor.computeIfAbsent(sd.color(), k -> new LinkedHashMap<>());
                for (var item : sd.items().entrySet()) {
                    colorItems.merge(item.getKey(), item.getValue(), Integer::sum);
                }
            }
            for (var colorEntry : byColor.entrySet()) {
                String primary = getPrimaryContent(colorEntry.getValue());
                if (primary != null) {
                    int contentWeight = colorEntry.getValue().values().stream().mapToInt(Integer::intValue).sum();
                    shulkersByContent.computeIfAbsent(primary, k -> new ArrayList<>())
                            .add(new ShulkerLoc(pos, colorEntry.getKey(), primary, contentWeight));
                }
            }
        }

        // Establish the organization schema from filled shulkers first. If an item already
        // exists in filled shulkers, its lane preference and volume are derived exclusively
        // from those shulkers; loose staging items must not choose the lane ahead of them.
        Map<String, List<ItemLocation>> assignmentLocations = new LinkedHashMap<>();
        for (var entry : shulkersByContent.entrySet()) {
            List<ItemLocation> evidence = assignmentLocations.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            for (ShulkerLoc shulker : entry.getValue()) {
                evidence.add(new ItemLocation(shulker.pos(), shulker.contentWeight()));
            }
            itemLocations.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
        }
        for (var entry : itemLocations.entrySet()) {
            assignmentLocations.putIfAbsent(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        // Step 3: Assign items to columns (largest volume first)
        columnAssignment = new LinkedHashMap<>();
        Set<Integer> assignedColumnIds = new HashSet<>();
        Map<Integer, Long> columnLoads = new HashMap<>();

        List<Map.Entry<String, List<ItemLocation>>> sortedItems =
                new ArrayList<>(assignmentLocations.entrySet());
        sortedItems.sort((a, b) -> {
            int totalA = a.getValue().stream().mapToInt(ItemLocation::quantity).sum();
            int totalB = b.getValue().stream().mapToInt(ItemLocation::quantity).sum();
            return Integer.compare(totalB, totalA);
        });

        // Pass 1: honor previously-persisted column assignments first, so items keep landing
        // in the same column across organize runs instead of being reshuffled by the greedy
        // pass below. Only items with no valid persisted column fall through to that pass.
        Map<String, int[]> persistedAssignments;
        try {
            var db = StashManagerPlugin.getDatabase();
            persistedAssignments = db != null ? db.loadColumnAssignments() : Map.of();
        } catch (SQLException e) {
            persistedAssignments = Map.of();
        }
        for (var itemEntry : persistedAssignments.entrySet()) {
            if (!itemLocations.containsKey(itemEntry.getKey())) continue;
            int[] top = itemEntry.getValue();
            Column col = topPosToColumn.get(posKey(top[0], top[1], top[2]));
            if (col == null || assignedColumnIds.contains(col.id())) continue;
            columnAssignment.put(itemEntry.getKey(), col);
            assignedColumnIds.add(col.id());
            long weight = assignmentLocations.getOrDefault(itemEntry.getKey(), List.of()).stream()
                    .mapToLong(ItemLocation::quantity).sum();
            columnLoads.merge(col.id(), weight, Long::sum);
        }

        int shared = 0;
        for (var entry : sortedItems) {
            String itemId = entry.getKey();
            if (columnAssignment.containsKey(itemId)) continue; // already reserved by persisted pass
            List<ItemLocation> locations = entry.getValue();
            locations.sort(Comparator.comparingInt(ItemLocation::quantity).reversed());
            long itemWeight = locations.stream().mapToLong(ItemLocation::quantity).sum();

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
                if (assigned == null) {
                    assigned = columns.stream()
                            .min(Comparator.comparingLong(col -> columnLoads.getOrDefault(col.id(), 0L)))
                            .orElse(null);
                }
                shared++;
            }

            if (assigned != null) {
                columnAssignment.put(itemId, assigned);
                assignedColumnIds.add(assigned.id());
                columnLoads.merge(assigned.id(), itemWeight, Long::sum);
            }
        }

        // Persist the final assignment so future organize runs reuse the same columns rather
        // than recomputing (and potentially reshuffling) them from scratch. Best-effort only —
        // requires the plugin's own Postgres connection to be configured (see README).
        try {
            var db = StashManagerPlugin.getDatabase();
            if (db != null) {
                Map<String, int[]> toSave = new LinkedHashMap<>();
                for (var entry : columnAssignment.entrySet()) {
                    toSave.put(entry.getKey(), entry.getValue().top());
                }
                db.saveColumnAssignments(toSave);
            }
        } catch (SQLException ignored) {
        }

        // Step 4: Generate move tasks
        taskQueue.clear();
        consolidationQueue.clear();

        int condenseTypes = 0;
        for (var entry : columnAssignment.entrySet()) {
            String itemId = entry.getKey();
            Column col = entry.getValue();

            List<ItemLocation> locations = itemLocations.get(itemId);
            if (locations == null || locations.isEmpty()) continue;

            int totalLoose = locations.stream().mapToInt(ItemLocation::quantity).sum();
            if (totalLoose >= config.condenseMinItems) {
                // Loose reconciliation is a distinct final phase. These tasks collect the
                // exact item variant and then enter the place/fill/break/store shulker cycle;
                // they never dump loose stacks straight into a lane chest.
                for (ItemLocation loc : locations) {
                    consolidationQueue.add(new MoveTask(loc.pos(), col.top(), itemId));
                }
                condenseTypes++;
            }
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
                            shulkerItemId(sl.shulkerType()), contentType));
                    shulkerMoves++;
                }
            }
        }

        // Planning phases are deliberate: establish filled-shulker lanes first, then clear
        // filled shulkers already in the bot inventory, and only then pack loose stash items.
        // Mid-run inventory-full recovery still promotes its emergency deposit tasks.
        queueInventoryDepositTasks(false);

        totalTasks = taskQueue.size() + consolidationQueue.size();
        completedTasks = 0;

        if (taskQueue.isEmpty() && consolidationQueue.isEmpty()) {
            info("Stash is already organized! (" + regionContainers.size() + " containers in "
                    + columns.size() + " columns, " + itemLocations.size() + " item types)");
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            emit("organize_completed", Map.of(
                "completed_tasks", 0,
                "total_tasks", 0,
                "overflow_types", 0
            ));
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Planned ").append(totalTasks).append(" moves across ")
                .append(columns.size()).append(" columns (")
                .append(columnAssignment.size()).append(" types");
        if (condenseTypes > 0) summary.append(", ").append(condenseTypes).append(" to condense");
        if (shared > 0) summary.append(", ").append(shared).append(" sharing columns");
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

    // Deposits everything currently in the bot's own inventory into the stash, ahead of
    // any other task, so the inventory is freed up for other functions (kit management,
    // etc). Anything on the keep list (tools/weapons/totems/elytra) is left alone up to its
    // configured quantity cap (null = keep all) — any excess beyond the cap still gets
    // deposited, guarding against loose duplicates piling up in inventory during
    // reconciliation. Everything else is routed to whatever column already matches its item
    // type (or its shulker's primary content, for a filled shulker held in inventory). Items
    // with no matching column yet are left in inventory rather than inventing a destination.
    private void queueInventoryDepositTasks(boolean prioritize) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return;

        Map<String, Integer> keepItems;
        try {
            var db = StashManagerPlugin.getDatabase();
            keepItems = db != null ? db.loadKeepItems() : Map.of();
        } catch (SQLException e) {
            keepItems = Map.of();
        }

        int[] currentPos = {
            (int) Math.floor(CACHE.getPlayerCache().getX()),
            (int) Math.floor(CACHE.getPlayerCache().getY()),
            (int) Math.floor(CACHE.getPlayerCache().getZ())
        };

        Map<String, Integer> keptSoFar = new HashMap<>();
        // One task deposits every matching stack from the inventory. Creating a task per
        // occupied slot meant the first task emptied all matches and every remaining duplicate
        // reopened the same destination only to report nothing_to_deposit.
        Map<String, MoveTask> inventoryTasks = new LinkedHashMap<>();
        Set<String> alreadyQueued = taskQueue.stream()
                .filter(MoveTask::alreadyInInventory)
                .map(this::inventoryTaskKey)
                .collect(Collectors.toSet());
        consolidationQueue.stream()
                .filter(MoveTask::alreadyInInventory)
                .map(this::inventoryTaskKey)
                .forEach(alreadyQueued::add);
        int skippedNoColumn = 0;
        // Zenith's raw player inventory container is size 46: 0-4=crafting, 5-8=armor,
        // 9-35=main inventory, 36-44=hotbar, 45=offhand. Scan only 9-44 (main+hotbar) —
        // otherwise equipped armor gets misread as a loose item, and the real hotbar
        // (36-44) never gets scanned/emptied at all.
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;

            String itemId = itemIdFromStack(stack);
            if (keepItems.containsKey(itemId)) {
                Integer cap = keepItems.get(itemId);
                if (cap == null) continue; // keep all of this item
                int already = keptSoFar.getOrDefault(itemId, 0);
                if (already + stack.getAmount() <= cap) {
                    keptSoFar.put(itemId, already + stack.getAmount());
                    continue; // whole stack still within the cap — keep it
                }
                if (already >= cap) {
                    // cap already met by earlier slots — deposit this whole stack
                } else {
                    // ShiftClick can only move the whole stack. Keep a stack that straddles
                    // the cap rather than silently depositing items the keep rule protects.
                    keptSoFar.put(itemId, already + stack.getAmount());
                    continue;
                }
            }

            String contentFilter = null;
            String columnKey = itemId;
            if (isShulkerBoxItem(itemId)) {
                String primary = getPrimaryContent(ItemIdentifier.readShulkerContents(stack));
                if (primary != null) {
                    contentFilter = primary;
                    columnKey = primary;
                }
            }

            Column col = columnAssignment.get(columnKey);
            if (col == null) {
                // No column exists for this content yet — dump it into overflow storage
                // instead of leaving it stuck in inventory forever, which would eventually
                // fill every slot and block the organizer from taking anything else at all.
                int[] overflow = findOverflowChest();
                if (overflow == null) {
                    skippedNoColumn++;
                    continue;
                }
                MoveTask task = new MoveTask(currentPos, overflow, itemId, null, true);
                String key = inventoryTaskKey(task);
                if (!alreadyQueued.contains(key)) inventoryTasks.putIfAbsent(key, task);
                continue;
            }
            MoveTask task = new MoveTask(currentPos, col.top(), itemId, contentFilter, true);
            String key = inventoryTaskKey(task);
            if (!alreadyQueued.contains(key)) inventoryTasks.putIfAbsent(key, task);
        }

        if (!inventoryTasks.isEmpty()) {
            List<MoveTask> uniqueTasks = new ArrayList<>(inventoryTasks.values());
            if (prioritize) {
                for (int i = uniqueTasks.size() - 1; i >= 0; i--) {
                    taskQueue.addFirst(uniqueTasks.get(i));
                }
            } else {
                for (MoveTask task : uniqueTasks) {
                    if (isShulkerBoxItem(task.itemId())) {
                        taskQueue.addLast(task);
                    } else {
                        consolidationQueue.addLast(task);
                    }
                }
            }
            info("Queued " + inventoryTasks.size() + " item type(s) already in the bot inventory for "
                    + (prioritize ? "recovery deposit." : "the appropriate organization phase."));
            if (state != State.PLANNING) {
                totalTasks += inventoryTasks.size();
            }
        }
        if (skippedNoColumn > 0) {
            info(skippedNoColumn + " inventory item(s) left in inventory — no matching stash column yet.");
        }
    }

    private String inventoryTaskKey(MoveTask task) {
        int[] destination = task.destination();
        return posKey(destination[0], destination[1], destination[2]) + "\u0000"
                + task.itemId() + "\u0000" + Objects.toString(task.shulkerContentFilter(), "");
    }

    private record StaircaseKey(int dx, int dz, int perpendicular, int diagonal) {}

    /**
     * The index can retain both halves of a double chest after incremental scans.  Those
     * records are not two inventories: they are two observations of one inventory and one
     * may be older.  Plan moves from the freshest observation only.  Geometry deliberately
     * continues to use the full list, since both block positions are useful for hopper-lane
     * detection.
     */
    private static List<ContainerEntry> deduplicateDoubleChestInventories(Collection<ContainerEntry> containers) {
        Map<Long, ContainerEntry> byPos = new LinkedHashMap<>();
        for (ContainerEntry entry : containers) {
            byPos.put(posKey(entry.x(), entry.y(), entry.z()), entry);
        }

        List<ContainerEntry> result = new ArrayList<>();
        Set<Long> consumed = new HashSet<>();
        for (ContainerEntry entry : containers) {
            long key = posKey(entry.x(), entry.y(), entry.z());
            if (!consumed.add(key)) continue;

            int[] partner = findDoubleChestPartner(entry);
            if (partner == null) {
                result.add(entry);
                continue;
            }
            ContainerEntry other = byPos.get(posKey(partner[0], partner[1], partner[2]));
            if (other == null) {
                result.add(entry);
                continue;
            }
            consumed.add(posKey(other.x(), other.y(), other.z()));
            if (other.timestamp() > entry.timestamp()
                    || (other.timestamp() == entry.timestamp() && other.posKey() < entry.posKey())) {
                result.add(other);
            } else {
                result.add(entry);
            }
        }
        return result;
    }

    /** Returns the real partner only when chest state proves that these two blocks form one. */
    private static int[] findDoubleChestPartner(ContainerEntry entry) {
        if (!entry.isDouble() || !("minecraft:chest".equals(entry.blockType())
                || "minecraft:trapped_chest".equals(entry.blockType()))) {
            return null;
        }
        var state = World.getBlockState(entry.x(), entry.y(), entry.z());
        ChestType chestType = state.getProperty(BlockStateProperties.CHEST_TYPE);
        Direction facing = state.getProperty(BlockStateProperties.HORIZONTAL_FACING);
        if (chestType == null || chestType == ChestType.SINGLE || facing == null) return null;

        for (Direction direction : Direction.HORIZONTALS) {
            int x = entry.x() + direction.x();
            int z = entry.z() + direction.z();
            var candidate = World.getBlockState(x, entry.y(), z);
            if (!candidate.block().equals(state.block())) continue;
            ChestType candidateType = candidate.getProperty(BlockStateProperties.CHEST_TYPE);
            Direction candidateFacing = candidate.getProperty(BlockStateProperties.HORIZONTAL_FACING);
            if (candidateType == chestType.getOpposite() && candidateFacing == facing) {
                return new int[]{x, entry.y(), z};
            }
        }
        return null;
    }

    static List<Column> detectStaircaseColumns(Collection<ContainerEntry> containers) {
        Map<Long, ContainerEntry> byPos = new HashMap<>();
        for (ContainerEntry entry : containers) {
            byPos.put(posKey(entry.x(), entry.y(), entry.z()), entry);
        }

        Map<StaircaseKey, List<int[][]>> stepsByLane = new LinkedHashMap<>();
        for (ContainerEntry hopper : containers) {
            if (!"minecraft:hopper".equals(hopper.blockType()) || hopper.hopperFacing() == null) continue;

            int dx;
            int dz;
            switch (hopper.hopperFacing()) {
                case "NORTH" -> { dx = 0; dz = -1; }
                case "SOUTH" -> { dx = 0; dz = 1; }
                case "WEST"  -> { dx = -1; dz = 0; }
                case "EAST"  -> { dx = 1; dz = 0; }
                default -> { continue; }
            }

            ContainerEntry input = byPos.get(posKey(hopper.x(), hopper.y() + 1, hopper.z()));
            ContainerEntry output = byPos.get(posKey(hopper.x() + dx, hopper.y(), hopper.z() + dz));
            if (!isPermanentStorage(input) || !isPermanentStorage(output)) continue;

            int along = hopper.x() * dx + hopper.z() * dz;
            int perpendicular = hopper.x() * -dz + hopper.z() * dx;
            StaircaseKey key = new StaircaseKey(dx, dz, perpendicular, along + 2 * hopper.y());
            stepsByLane.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new int[][]{
                {input.x(), input.y(), input.z()},
                {output.x(), output.y(), output.z()}
            });
        }

        List<Column> columns = new ArrayList<>();
        for (var laneEntry : stepsByLane.entrySet()) {
            StaircaseKey key = laneEntry.getKey();
            Map<Long, int[]> uniqueStorage = new LinkedHashMap<>();
            for (int[][] step : laneEntry.getValue()) {
                for (int[] pos : step) {
                    uniqueStorage.putIfAbsent(posKey(pos[0], pos[1], pos[2]), pos);
                }
            }

            List<int[]> ordered = new ArrayList<>(uniqueStorage.values());
            ordered.sort(Comparator
                    .<int[]>comparingInt(pos -> pos[1]).reversed()
                    .thenComparingInt(pos -> pos[0] * key.dx() + pos[2] * key.dz()));
            if (!ordered.isEmpty()) {
                columns.add(new Column(columns.size(), ordered));
            }
        }

        columns.sort(Comparator
                .comparingInt((Column col) -> col.top()[0])
                .thenComparingInt(col -> col.top()[2])
                .thenComparingInt(col -> col.top()[1]));

        // IDs are used only as stable identities inside one plan; restore sequential IDs
        // after sorting the lanes into deterministic world-coordinate order.
        List<Column> reindexed = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            reindexed.add(new Column(i, columns.get(i).chests()));
        }
        return reindexed;
    }

    private static boolean isPermanentStorage(ContainerEntry entry) {
        return entry != null && switch (entry.blockType()) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel" -> true;
            default -> false;
        };
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
        setBaritoneBreakingAllowed(false);
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2])));
    }

    private void onArrived() {
        openWaitTicks = 0;
        containerDataReceived = false;
        switch (state) {
            case WALKING              -> {
                state = State.OPENING;
            }
            case SHULKER_FETCH_WALK   -> {
                state = State.SHULKER_FETCH_OPEN;
            }
            case SHULKER_STORE_WALK   -> {
                state = State.SHULKER_STORE_OPEN;
            }
            case OVERFLOW_WALKING     -> {
                state = State.OVERFLOW_OPENING;
            }
            default -> {
                state = State.OPENING;
            }
        }
    }

    // OPENING
    private void tickOpening() {
        openWaitTicks++;

        if (containerDataReceived) {
            BARITONE.stop();
            actionSlotIndex = 0;
            actionCooldown = 0;
            movedThisVisit = 0;
            sourceVisitFailed = false;
            state = (currentRole == TargetRole.SOURCE) ? State.TAKING : State.DEPOSITING;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening container, skipping.");
            emit("organize_failed", Map.of("reason", "open_timeout"));
            BARITONE.stop();
            advanceToNextTask();
            return;
        }

        // A single missed right-click (rotation not settled, brief lag, etc.) should not
        // doom the whole task — retry periodically like tickShulkerOpening does.
        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]);
        }
    }

    // TAKING (container → player inventory via shift-click)
    private void tickTaking() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            sourceVisitFailed = true;
            emit("organize_target_failed", Map.of("reason", "source_container_lost"));
            state = State.CLOSING_SOURCE;
            closeCurrentContainer();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);
        int idCandidates = 0;
        java.util.Set<String> actualPrimaries = new java.util.LinkedHashSet<>();

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = open.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (currentTask != null && itemId.equals(currentTask.itemId())) {
                    idCandidates++;
                    // Same-colored shulkers can hold different contents — verify
                    // this specific shulker's contents match the task before
                    // taking it, not just its color/id.
                    if (currentTask.shulkerContentFilter() != null) {
                        if (!isShulkerBoxItem(itemId)) {
                            actionSlotIndex++;
                            continue;
                        }
                        String primary = getPrimaryContent(ItemIdentifier.readShulkerContents(stack));
                        actualPrimaries.add(String.valueOf(primary));
                        if (!ItemIdentifier.contentItemIdsMatch(currentTask.shulkerContentFilter(), primary)) {
                            actionSlotIndex++;
                            continue;
                        }
                    }

                    if (!hasInventoryRoom()) {
                        if (consolidationMode && movedThisVisit > 0) {
                            // Pack the batch collected so far, then revisit this same source
                            // for any remainder instead of dumping the loose batch into a chest.
                            consolidationQueue.addFirst(currentTask);
                            state = State.CLOSING_SOURCE;
                            closeCurrentContainer();
                            return;
                        }
                        if (movedThisVisit == 0) {
                            emit("organize_target_failed", Map.of("reason", "inventory_full_cannot_take"));
                        }
                        // queueInventoryDepositTasks() only runs once at the start of planning —
                        // if inventory fills up mid-run with nothing further matching whatever's
                        // stuck inside, every remaining task would fail this same way forever.
                        // Re-queue this task for a later retry, then divert to emptying out
                        // whatever's actually in inventory right now before continuing.
                        if (currentTask != null) {
                            taskQueue.addFirst(currentTask);
                        }
                        queueInventoryDepositTasks(true);
                        advanceToNextTask();
                        return;
                    }

                    if (quickMoveSlot(actionSlotIndex)) {
                        actionSlotIndex++;
                        movedThisVisit++;
                    }
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        if (movedThisVisit == 0) {
            sourceVisitFailed = true;
            emit("organize_target_failed", Map.of(
                "reason", "item_not_found_at_source",
                "id_candidates_seen", idCandidates,
                "actual_primary_contents_seen", actualPrimaries.toString()
            ));
        }
        state = State.CLOSING_SOURCE;
        closeCurrentContainer();
    }

    private void tickClosingSource() {
        // Brief pause after close before advancing
        actionCooldown++;
        if (actionCooldown >= 3) {
            actionCooldown = 0;
            if (sourceVisitFailed) {
                advanceToNextTask();
            } else if (consolidationMode) {
                startShulkerPacking(currentTask.itemId(), currentTask.destination());
            } else {
                transitionToDestination();
            }
        }
    }

    // DEPOSITING (player inventory → container via shift-click)
    private void tickDepositing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            emit("organize_target_failed", Map.of("reason", "destination_container_lost"));
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);

        // Window layout: [chest slots][player inv 27][hotbar 9]

        int playerSlot = Math.max(HOTBAR_SIZE, actionSlotIndex); // slot 9 — skip crafting/armor
        while (playerSlot < 45) {
            int containerSlotIndex = rawPlayerSlotToWindowSlot(chestSlots, playerSlot);
            // While a non-zero container is open Zenith only updates the appended player
            // section in that window. Its raw Container(0, 46) is not refreshed until close.
            ItemStack stack = open.getItemStack(containerSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (currentTask != null && itemId.equals(currentTask.itemId())) {
                    // Same-colored shulkers can hold different contents — verify
                    // this specific shulker's contents match the task before
                    // depositing it, so a stray shulker already in inventory
                    // doesn't get shipped to the wrong column.
                    if (currentTask.shulkerContentFilter() != null) {
                        if (!isShulkerBoxItem(itemId)) {
                            playerSlot++;
                            continue;
                        }
                        String primary = getPrimaryContent(ItemIdentifier.readShulkerContents(stack));
                        if (!ItemIdentifier.contentItemIdsMatch(currentTask.shulkerContentFilter(), primary)) {
                            playerSlot++;
                            continue;
                        }
                    }

                    // Check if chest has room before depositing
                    if (!hasChestRoom()) {
                        // Chest full — try cascading to next chest in column
                        closeCurrentContainer();
                        if (cascadeToNextInColumn()) {
                            return;
                        }
                        // Filled shulkers cannot be nested inside another shulker. Packing is
                        // exclusively a loose-item reconciliation operation.
                        if (isShulkerBoxItem(currentTask.itemId())) {
                            emit("organize_target_failed", Map.of("reason", "shulker_lane_full"));
                            advanceToNextTask();
                        } else {
                            startShulkerPacking(currentTask.itemId(), currentTask.destination());
                        }
                        return;
                    }

                    if (quickMoveSlot(containerSlotIndex)) {
                        playerSlot++;
                        movedThisVisit++;
                    }
                    actionCooldown = config.organizerClickCooldownTicks;
                    // Resume from this slot next tick
                    actionSlotIndex = playerSlot;
                    return;
                }
            }
            playerSlot++;
        }

        // Done depositing
        if (movedThisVisit == 0) {
            emit("organize_target_failed", Map.of("reason", "nothing_to_deposit"));
        }
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
            BARITONE.stop();
            actionSlotIndex = 0;
            actionCooldown = 0;
            state = State.SHULKER_FETCH_TAKE;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening container for shulker fetch.");
            emit("organize_failed", Map.of("reason", "shulker_fetch_open_timeout"));
            BARITONE.stop();
            startOverflow();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]);
        }
    }

    private void tickShulkerFetchTake() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            startOverflow();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = open.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (isShulkerBoxItem(itemId) && ItemIdentifier.readShulkerContents(stack).isEmpty()) {
                    // Take only an actually empty shulker.  Index summaries cannot distinguish
                    // every physical box, so the live stack is the authority here.
                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = config.organizerClickCooldownTicks;
                        return;
                    }
                    // InventoryManager rejects a CloseContainer submitted in the same tick as
                    // a ShiftClick.  Let the click settle, then close and wait for raw
                    // Container(0) to receive the returned inventory state.
                    fetchedEmptyShulker = true;
                    actionCooldown = 0;
                    state = State.SHULKER_FETCH_CLOSING;
                    return;
                }
            }
            actionSlotIndex++;
        }

        // This was a false positive from the indexed summary (all its boxes are filled).
        // Close cleanly and try the next candidate before declaring that no packing box exists.
        fetchedEmptyShulker = false;
        actionCooldown = 0;
        state = State.SHULKER_FETCH_CLOSING;
    }

    private void tickShulkerFetchClosing() {
        actionCooldown++;
        if (actionCooldown == 3) {
            closeCurrentContainer();
            return;
        }
        if (actionCooldown >= 6) {
            actionCooldown = 0;
            if (fetchedEmptyShulker) {
                state = State.SHULKER_SELECTING;
                shulkerTicks = 0;
            } else {
                startFetchShulker();
            }
        }
    }

    // SHULKER STORE — deposit filled shulker into destination
    private void tickShulkerStoreOpen() {
        openWaitTicks++;

        if (containerDataReceived) {
            BARITONE.stop();
            state = State.SHULKER_STORE_DEPOSIT;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            movedThisVisit = 0;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening destination for shulker deposit.");
            emit("organize_failed", Map.of("reason", "shulker_store_open_timeout"));
            BARITONE.stop();
            advanceToNextTask();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]);
        }
    }

    private void tickShulkerStoreDeposit() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);

        // Deposit only the shulker produced for this exact packed item variant.
        while (actionSlotIndex < 45) {
            int containerSlotIndex = rawPlayerSlotToWindowSlot(chestSlots, actionSlotIndex);
            ItemStack stack = open.getItemStack(containerSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                String primary = isShulkerBoxItem(itemId)
                        ? getPrimaryContent(ItemIdentifier.readShulkerContents(stack))
                        : null;
                if (ItemIdentifier.contentItemIdsMatch(packItemId, primary)) {
                    if (quickMoveSlot(containerSlotIndex)) {
                        actionSlotIndex++;
                        movedThisVisit++;
                    }
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        closeCurrentContainer();
        if (movedThisVisit > 0) {
            completedTasks++;
        } else {
            emit("organize_target_failed", Map.of("reason", "packed_shulker_not_found_in_inventory"));
        }

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
        this.shulkerFetchTriedSources.clear();
        this.fetchedEmptyShulker = false;
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
            if (hasPotentialShulkerInRegion()) {
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

        if (shulkerTicks > organizerOpenTimeoutTicks()) {
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

        if (shulkerTicks > organizerOpenTimeoutTicks()) {
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
            BARITONE.stop();
            state = State.SHULKER_FILLING;
            actionSlotIndex = 9;
            actionCooldown = 0;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening placed shulker");
            emit("organize_failed", Map.of("reason", "shulker_open_timeout"));
            BARITONE.stop();
            startOverflow();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            BARITONE.rightClickBlock(shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2]);
        }
    }

    private void tickShulkerFilling() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            startOverflow();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open); // Should be 27 for shulker

        // Check if shulker is full
        boolean shulkerFull = true;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack stack = open.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) {
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

        while (actionSlotIndex < 45) {
            int containerSlotIndex = rawPlayerSlotToWindowSlot(chestSlots, actionSlotIndex);
            ItemStack stack = open.getItemStack(containerSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (itemId.equals(packItemId)) {
                    if (quickMoveSlot(containerSlotIndex)) {
                        actionSlotIndex++;
                    }
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
            // Wait for the specific box we just packed, not an unrelated filled shulker the
            // bot was already carrying for a different lane.
            if (hasPackedShulkerInInventory()) {
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
        int shellsInRegion = countItemInRegion("shulker_shell");
        int chestsInRegion = countItemInRegion("chest");
        int shellsInInv = countItemInInventory("shulker_shell");
        int chestsInInv = countItemInInventory("chest");
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
                if (container.items().containsKey("shulker_shell")
                        || container.items().containsKey("chest")) {
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
            int shellsHave = countItemInInventory("shulker_shell");
            int chestsHave = countItemInInventory("chest");
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
        // Same reasoning as advanceToNextTask() — a container (e.g. a temp placed shulker)
        // may still be open when a failure path routes here.
        closeCurrentContainer();

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
            BARITONE.stop();
            state = State.OVERFLOW_DEPOSITING;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening overflow chest.");
            emit("organize_failed", Map.of("reason", "overflow_open_timeout"));
            BARITONE.stop();
            advanceToNextTask();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]);
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
        while (actionSlotIndex < 45) {
            ItemStack stack = playerContainer.getItemStack(actionSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
                int containerSlotIndex;
                if (actionSlotIndex < 36) {
                    containerSlotIndex = chestSlots + actionSlotIndex - 9;
                } else {
                    containerSlotIndex = chestSlots + 27 + (actionSlotIndex - 36);
                }

                if (quickMoveSlot(containerSlotIndex)) {
                    actionSlotIndex++;
                }
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
        if (currentTask.alreadyInInventory()) {
            startShulkerPacking(currentTask.itemId(), currentTask.destination());
            return;
        }
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
        // A failure/timeout path may bail out here while a container is still open server-side
        // (e.g. containerDataReceived arrived just after the timeout fired) — always close first
        // so a stale window never lingers into the next task's WALKING phase.
        closeCurrentContainer();

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
        if (currentTask.alreadyInInventory()) {
            // Item is already in hand (deposited from the bot's own inventory) — no need
            // to walk to/open a source container, go straight to the destination.
            transitionToDestination();
            return;
        }
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
    }

    private void finishOrganization() {
        BARITONE.stop();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.DONE;
        emit("organize_completed", Map.of(
            "completed_tasks", completedTasks,
            "total_tasks", totalTasks,
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
        int cacheContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (cacheContainerId <= 0) {
            containerDataReceived = false;
            openContainerId = -1;
            return;
        }
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer(cacheContainerId))
                    .priority(5000)
                    .actionDelayTicks(0)
                    .build());
        } catch (Exception ignored) {}
        containerDataReceived = false;
        openContainerId = -1;
    }

    /** Closes a server window that arrived after its owning opening state was abandoned. */
    private void closeUnexpectedContainer(int containerId) {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer(containerId))
                    .priority(7000)
                    .actionDelayTicks(0)
                    .build());
        } catch (Exception ignored) {
        }
    }

    private void saveAndDisableBaritoneBreaking() {
        if (baritoneBreakingGuardActive) return;
        savedAllowBreak = CONFIG.client.extra.pathfinder.allowBreak;
        baritoneBreakingGuardActive = true;
        setBaritoneBreakingAllowed(false);
        info("Baritone block breaking disabled for organizer navigation.");
    }

    private void setBaritoneBreakingAllowed(boolean allowed) {
        if (baritoneBreakingGuardActive) {
            CONFIG.client.extra.pathfinder.allowBreak = allowed;
        }
    }

    private void restoreBaritoneBreaking() {
        if (!baritoneBreakingGuardActive) return;
        CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
        baritoneBreakingGuardActive = false;
        info("Baritone block breaking restored to " + savedAllowBreak + ".");
    }

    private void saveAndGuardPlaceBlockSneak() {
        if (placeSneakGuardActive) return;
        savedPlaceBlockSneak = PathfinderCompat.getPlaceBlockSneak();
        placeSneakGuardActive = true;
        setPlaceBlockSneak(false);
    }

    private void setPlaceBlockSneak(boolean sneak) {
        if (placeSneakGuardActive) {
            PathfinderCompat.setPlaceBlockSneak(sneak);
        }
    }

    private void restorePlaceBlockSneak() {
        if (!placeSneakGuardActive) return;
        PathfinderCompat.setPlaceBlockSneak(savedPlaceBlockSneak);
        placeSneakGuardActive = false;
    }

    private int getOpenContainerSlotCount() {
        Container open = getLiveOpenContainer();
        return open == null ? 0 : getOpenContainerSlotCount(open);
    }

    private static int getOpenContainerSlotCount(Container open) {
        return Math.max(0, open.getSize() - 36);
    }

    private Container getLiveOpenContainer() {
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        int cacheContainerId = inventoryCache.getOpenContainerId();
        if (openContainerId <= 0 || cacheContainerId != openContainerId) return null;
        Container open = inventoryCache.getOpenContainer();
        return open.getContainerId() == openContainerId ? open : null;
    }

    private static int rawPlayerSlotToWindowSlot(int chestSlots, int rawSlot) {
        return rawSlot < 36
            ? chestSlots + rawSlot - 9
            : chestSlots + 27 + rawSlot - 36;
    }

    private int organizerOpenTimeoutTicks() {
        return Math.max(MIN_OPEN_TIMEOUT_TICKS, config.organizerOpenTimeoutTicks);
    }

    private ItemStack getCurrentPlayerInventoryStack(int rawSlot) {
        Container open = getLiveOpenContainer();
        if (open != null) {
            return open.getItemStack(rawPlayerSlotToWindowSlot(getOpenContainerSlotCount(open), rawSlot));
        }
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return player == null ? null : player.getItemStack(rawSlot);
    }

    // Shift-click a slot in the open container.
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
            // submit() rejects synchronously (future already completed as not-accepted)
            // when another request is still active — don't advance past this slot then.
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            // Container may have closed
            return false;
        }
    }

    // Inventory Helpers
    private boolean hasInventoryRoom() {
        for (int i = HOTBAR_SIZE; i < 45; i++) {
            ItemStack stack = getCurrentPlayerInventoryStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
        }
        return false;
    }

    private boolean hasChestRoom() {
        Container open = getLiveOpenContainer();
        if (open == null) return false;
        int chestSlots = getOpenContainerSlotCount(open);
        for (int i = 0; i < chestSlots; i++) {
            ItemStack stack = open.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
        }
        return false;
    }

    // Return true after selecting the next chest in the column.
    private boolean cascadeToNextInColumn() {
        if (currentTask == null) return false;
        String assignmentKey = currentTask.shulkerContentFilter() != null
                ? currentTask.shulkerContentFilter()
                : currentTask.itemId();
        Column col = columnAssignment.get(assignmentKey);
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

    // ShulkerDetail.color() reports "unknown" for a plain undyed shulker box, whose real
    // item id has no color prefix at all — not "unknown_shulker_box". Item ids in this codebase
    // are unprefixed (no "minecraft:" namespace), matching ItemIdentifier.getItemId().
    private static String shulkerItemId(String color) {
        return "unknown".equals(color) ? "shulker_box" : color + "_shulker_box";
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
            case SHULKER_FETCH_WALK, SHULKER_FETCH_OPEN, SHULKER_FETCH_TAKE, SHULKER_FETCH_CLOSING
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

    private boolean hasPackedShulkerInInventory() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return false;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() <= 0 || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            String primary = getPrimaryContent(ItemIdentifier.readShulkerContents(stack));
            if (ItemIdentifier.contentItemIdsMatch(packItemId, primary)) return true;
        }
        return false;
    }

    private boolean hasPotentialShulkerInRegion() {
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
        // Index summaries are aggregate, so visit each candidate at most once and let the
        // live open window decide whether it contains an empty box.
        for (ContainerEntry container : index.getAll()) {
            if (isInRegion(container.x(), container.y(), container.z())) {
                for (String itemId : container.items().keySet()) {
                    long key = posKey(container.x(), container.y(), container.z());
                    if (isShulkerBoxItem(itemId) && shulkerFetchTriedSources.add(key)) {
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

    // Searches a wide area around the player rather than a small fixed ring, so a spot
    // further down a packed shelving aisle can still be found even if the immediate
    // neighbors are all chests; picks the closest valid spot rather than the first found.
    private int[] findShulkerPlaceSpot() {
        var player = CACHE.getPlayerCache();
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0) continue; // player's own column — never place under/on self
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    if (!World.isInWorldBounds(x, y, z)) continue;

                    var target = World.getBlock(x, y, z);
                    var above = World.getBlock(x, y + 1, z);
                    var below = World.getBlock(x, y - 1, z);
                    // Baritone tries every neighboring face (down/south/east/north/west/up) to place
                    // against, not just the one below — any of them being a container/GUI block
                    // means a right-click there opens it instead of placing the shulker.
                    var north = World.getBlock(x, y, z - 1);
                    var south = World.getBlock(x, y, z + 1);
                    var east = World.getBlock(x + 1, y, z);
                    var west = World.getBlock(x - 1, y, z);
                    if (!BlockCompat.canReplace(target)
                        || !BlockCompat.canReplace(above)
                        || BlockCompat.isAir(below)
                        || BlockCompat.isInteractable(below)
                        || BlockCompat.isInteractable(north)
                        || BlockCompat.isInteractable(south)
                        || BlockCompat.isInteractable(east)
                        || BlockCompat.isInteractable(west)
                        || BlockCompat.isInteractable(above)
                        || !BlockCompat.isSolid(x, y - 1, z)) continue;

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

        int shells = countItemInRegion("shulker_shell") + countItemInInventory("shulker_shell");
        int chests = countItemInRegion("chest") + countItemInInventory("chest");

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
        int shells = countItemInInventory("shulker_shell");
        int chests = countItemInInventory("chest");
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
