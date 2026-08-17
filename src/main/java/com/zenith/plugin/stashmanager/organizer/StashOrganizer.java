package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.block.BlockPos;
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
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.BulkBatchPlanner;
import com.zenith.plugin.stashmanager.orchestration.DedicatedLaneCapacity;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.organizer.lane.IndexedStorageGeometry;
import com.zenith.plugin.stashmanager.orchestration.OrganizerOwnershipPolicy;
import com.zenith.plugin.stashmanager.orchestration.ShulkerWorksitePolicy;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
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
        SHULKER_STATION_WALK,
        SHULKER_SELECTING,
        SHULKER_PLACING,
        SHULKER_WAIT_PLACE,
        SHULKER_OPENING,
        SHULKER_FILLING,
        SHULKER_CLOSING,
        SHULKER_BREAKING,
        SHULKER_PICKUP,
        SHULKER_RECOVERY_BREAKING,
        SHULKER_RECOVERY_PICKUP,
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

    static LaneStorageCapacity.Lane toStorageLane(
            Column column,
            Map<Long, ContainerEntry> containersByPosition) {
        int[] top = column.top();
        Set<Long> countedInventories = new HashSet<>();
        long slots = 0;
        for (int[] position : column.chests()) {
            ContainerEntry entry = containersByPosition.get(
                    posKey(position[0], position[1], position[2]));
            long inventoryKey = entry != null && entry.isDouble() && entry.inventoryIdentityKnown()
                    ? entry.inventoryKey()
                    : posKey(position[0], position[1], position[2]);
            if (!countedInventories.add(inventoryKey)) continue;
            // A lane step generally indexes only the half sitting above its hopper. Persisted
            // identity proves whether that block belongs to a 54-slot inventory, so capacity
            // must not depend on whether the partner block also happened to become a step.
            slots += entry != null && entry.isDouble() ? 54L : 27L;
        }
        int boundedSlots = slots > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) slots;
        return new LaneStorageCapacity.Lane(column.id(), top[0], top[1], top[2], boundedSlots);
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
    private static final int MIN_OPEN_TIMEOUT_TICKS = 400;
    private static final int OPEN_RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_DESTINATION_OPEN_RETRIES = 3;
    private static final int MAX_SOURCE_TASK_RETRIES = 3;
    private static final int MAX_SHULKER_RECOVERY_BREAK_ATTEMPTS = 3;
    private static final int SHULKER_PICKUP_TIMEOUT_TICKS = 100;

    // Runtime State
    private int[] walkTarget;
    private long trackedWalkTargetKey = Long.MIN_VALUE;
    private int walkingTicks;
    private int openWaitTicks;
    private int actionSlotIndex;
    private int actionCooldown;
    private final Set<Long> shulkerFetchTriedSources = new HashSet<>();
    private boolean fetchedPackingShulker;
    private final Map<String, Integer> destinationOpenFailures = new HashMap<>();
    private final Map<String, Integer> sourceTaskFailures = new HashMap<>();
    private final Set<Long> managedSourceContainerKeys = new HashSet<>();
    // Counts successful clicks during the current TAKING/DEPOSITING container visit. TAKING
    // resumes scanning from wherever it left off each tick rather than from slot 0, so the
    // final tick of a visit naturally finds "nothing left" once everything matching has already
    // been taken — that's normal completion, not a failure, and should only be reported as a
    // failure if nothing was ever moved during the whole visit.
    private int movedThisVisit;
    private boolean sourceVisitFailed;
    private int consolidationSourcesInBatch;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

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
    private int[] reconciliationStation;
    private int[] reconciliationWorksite;
    private int[] shulkerPlacePos;
    private ItemData packShulkerItemData;
    private PathingRequestFuture shulkerPlaceFuture;
    private PathingRequestFuture shulkerBreakFuture;
    private float savedYaw, savedPitch;
    private int shulkerTicks;
    private int shulkerPlaceRetries;
    private int shulkerInventoryCountBeforePlacement;
    private int shulkerRecoveryBreakAttempts;
    private boolean temporaryShulkerOutstanding;
    private boolean stopAfterShulkerRecovery;
    private String shulkerRecoveryTrigger;

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

    /** Read-only capacity audit over the latest indexed scan. Safe to call while idle. */
    public LaneCapacityReport calculateLaneCapacity() {
        if (config.pos1 == null || config.pos2 == null) {
            return LaneCapacityReport.unavailable(LaneCapacityReport.Status.REGION_NOT_DEFINED);
        }

        List<ContainerEntry> regionContainers = index.getInRegion(config.pos1, config.pos2);
        if (regionContainers.isEmpty()) {
            return LaneCapacityReport.unavailable(LaneCapacityReport.Status.NO_SCANNED_CONTAINERS);
        }

        List<ContainerEntry> planningContainers = deduplicateDoubleChestInventories(regionContainers);
        Map<Long, ContainerEntry> regionByPosition = regionContainers.stream()
                .collect(Collectors.toMap(ContainerEntry::posKey, entry -> entry,
                        (current, candidate) -> candidate.timestamp() > current.timestamp()
                                ? candidate : current));
        List<Column> columns = detectStaircaseColumns(regionContainers);
        if (columns.isEmpty()) {
            Set<int[]> positions = regionContainers.stream()
                    .filter(StashOrganizer::isPermanentStorage)
                    .filter(entry -> !index.isImportChest(entry))
                    .map(e -> new int[]{e.x(), e.y(), e.z()})
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            columns = detectColumns(positions);
        } else {
            columns = excludeImportColumns(columns);
        }

        Map<Long, Column> posToColumn = new HashMap<>();
        for (Column column : columns) {
            for (int[] position : column.chests()) {
                posToColumn.put(posKey(position[0], position[1], position[2]), column);
            }
        }

        // A scan can include kit storage, personal chests, and other inventories which are
        // neither FIFO destinations nor explicitly-authorized intake. Treat those as outside
        // the organizer's ownership boundary. Import chests and detected lanes are the only
        // inventories whose contents contribute storage classes or move tasks.
        planningContainers = planningContainers.stream()
                .filter(container -> OrganizerOwnershipPolicy.isManagedSource(
                        posToColumn.containsKey(posKey(container.x(), container.y(), container.z())),
                        index.isImportChest(container)))
                .toList();

        boolean needsFreshContainerScan = planningContainers.stream().anyMatch(container ->
                container.isDouble() && !container.inventoryIdentityKnown());

        Set<String> storageClasses = new TreeSet<>();
        Map<String, Long> looseItemsByClass = new LinkedHashMap<>();
        Map<String, List<Integer>> bulkShulkerCountsByClass = new LinkedHashMap<>();
        Set<Long> protectedContainers = new HashSet<>();
        int bulkShulkers = 0;
        int emptyShulkers = 0;
        int mixedShulkers = 0;
        int unclassifiedShulkers = 0;

        for (ContainerEntry container : planningContainers) {
            long containerKey = posKey(container.x(), container.y(), container.z());
            Map<String, Integer> accessible = new HashMap<>(container.items());
            int observedDetails = 0;

            for (ContainerEntry.ShulkerDetail detail : container.shulkerDetails()) {
                observedDetails++;
                for (var item : detail.items().entrySet()) {
                    accessible.computeIfPresent(item.getKey(), (key, quantity) -> {
                        int remaining = quantity - item.getValue();
                        return remaining > 0 ? remaining : null;
                    });
                }

                if (!detail.isPhysicalInstance()) {
                    unclassifiedShulkers++;
                    protectedContainers.add(containerKey);
                    continue;
                }

                ShulkerClassification classification = ShulkerClassification.classify(detail.items());
                switch (classification.kind()) {
                    case BULK -> {
                        bulkShulkers++;
                        storageClasses.add(classification.storageKey());
                        int itemCount = classification.contents().values().stream()
                                .mapToInt(Integer::intValue).sum();
                        bulkShulkerCountsByClass
                                .computeIfAbsent(classification.storageKey(), ignored -> new ArrayList<>())
                                .add(itemCount);
                    }
                    case EMPTY -> {
                        emptyShulkers++;
                        protectedContainers.add(containerKey);
                    }
                    case MIXED -> {
                        mixedShulkers++;
                        protectedContainers.add(containerKey);
                    }
                }
            }

            int missingDetails = Math.max(0, container.shulkerCount() - observedDetails);
            if (missingDetails > 0) {
                unclassifiedShulkers += missingDetails;
                protectedContainers.add(containerKey);
            }

            accessible.forEach((itemId, quantity) -> {
                if (quantity == null || quantity <= 0 || isShulkerBoxItem(itemId)) return;
                storageClasses.add(itemId);
                looseItemsByClass.merge(itemId, quantity.longValue(), Long::sum);
            });
        }

        Set<Integer> protectedLaneIds = new HashSet<>();
        for (long containerKey : protectedContainers) {
            Column column = posToColumn.get(containerKey);
            if (column != null) protectedLaneIds.add(column.id());
        }

        List<LaneStorageCapacity.Demand> storageDemands = storageClasses.stream()
                .map(storageClass -> LaneStorageCapacity.Demand.calculate(
                        storageClass,
                        looseItemsByClass.getOrDefault(storageClass, 0L),
                        bulkShulkerCountsByClass.getOrDefault(storageClass, List.of()),
                        shulkerCapacityFor(storageClass)))
                .toList();
        List<LaneStorageCapacity.Lane> allStorageLanes = columns.stream()
                .map(column -> toStorageLane(column, regionByPosition))
                .toList();
        List<LaneStorageCapacity.Lane> storageLanes = allStorageLanes.stream()
                .filter(column -> !protectedLaneIds.contains(column.id()))
                .toList();
        LaneStorageCapacity.Report laneStorage = LaneStorageCapacity.assess(storageDemands, storageLanes);

        LaneCapacityReport report = LaneCapacityReport.assess(
                planningContainers.size(),
                columns.size(),
                protectedLaneIds.size(),
                new ArrayList<>(storageClasses),
                bulkShulkers,
                emptyShulkers,
                mixedShulkers,
                unclassifiedShulkers,
                laneStorage).withLanes(allStorageLanes);
        return needsFreshContainerScan
                ? report.withStatus(LaneCapacityReport.Status.NEEDS_FRESH_CONTAINER_SCAN)
                : report;
    }

    public boolean start() {
        if (temporaryShulkerOutstanding) {
            info("Cannot start: a temporary packing shulker still requires recovery at "
                    + posString(shulkerPlacePos) + ".");
            emit("organize_start_blocked", Map.of(
                    "reason", "temporary_shulker_recovery_required",
                    "shulker_position", posString(shulkerPlacePos)
            ));
            return false;
        }
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

        var playerCache = CACHE.getPlayerCache();
        reconciliationStation = new int[]{
                (int) Math.floor(playerCache.getX()),
                (int) Math.floor(playerCache.getY()),
                (int) Math.floor(playerCache.getZ())
        };
        reconciliationWorksite = findShulkerPlaceSpot(reconciliationStation);

        BARITONE.stop();
        saveAndDisableBaritoneBreaking();
        saveAndGuardPlaceBlockSneak();
        taskQueue.clear();
        consolidationQueue.clear();
        overflowItems.clear();
        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        managedSourceContainerKeys.clear();
        currentTask = null;
        walkTarget = null;
        trackedWalkTargetKey = Long.MIN_VALUE;
        walkingTicks = 0;
        consolidationMode = false;
        consolidationSourcesInBatch = 0;
        completedTasks = 0;
        totalTasks = 0;
        containerDataReceived = false;
        openContainerId = -1;
        resetTemporaryShulkerState();

        state = State.PLANNING;
        emit("organize_started", Map.of(
            "region_pos1", posString(config.pos1),
            "region_pos2", posString(config.pos2),
            "reconciliation_station", posString(reconciliationStation),
            "reconciliation_worksite", reconciliationWorksite == null
                    ? "unavailable"
                    : posString(reconciliationWorksite)
        ));
        return true;
    }

    public void stop() {
        if (temporaryShulkerOutstanding) {
            info("Stop requested; recovering the temporary packing shulker before stopping.");
            stopAfterShulkerRecovery = true;
            beginTemporaryShulkerRecovery("manual_stop");
            return;
        }
        finishStop();
    }

    private void finishStop() {
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
        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        managedSourceContainerKeys.clear();
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
            closeUnexpectedContainer(session, packet.getContainerId());
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

        setBaritoneBreakingAllowed(state == State.SHULKER_BREAKING
                || state == State.SHULKER_RECOVERY_BREAKING);
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
            case SHULKER_STATION_WALK -> tickWalking();
            case SHULKER_SELECTING   -> tickShulkerSelecting();
            case SHULKER_PLACING     -> tickShulkerPlacing();
            case SHULKER_WAIT_PLACE  -> tickShulkerWaitPlace();
            case SHULKER_OPENING     -> tickShulkerOpening();
            case SHULKER_FILLING     -> tickShulkerFilling();
            case SHULKER_CLOSING     -> tickShulkerClosing();
            case SHULKER_BREAKING    -> tickShulkerBreaking();
            case SHULKER_PICKUP      -> tickShulkerPickup();
            case SHULKER_RECOVERY_BREAKING -> tickShulkerRecoveryBreaking();
            case SHULKER_RECOVERY_PICKUP -> tickShulkerRecoveryPickup();
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
        Map<Long, ContainerEntry> regionByPosition = regionContainers.stream()
                .collect(Collectors.toMap(ContainerEntry::posKey, entry -> entry,
                        (current, candidate) -> candidate.timestamp() > current.timestamp()
                                ? candidate : current));

        // Step 1: Detect the actual hopper-fed staircase lanes. A geometric connected-
        // component pass splits the two halves of double chests, cross-links neighboring
        // staircases, and even admitted placed shulker block entities as destination columns.
        // Each hopper step advances two blocks horizontally while dropping one Y level, so
        // hoppers with the same facing/perpendicular coordinate/diagonal invariant form one
        // physical lane. The first position is the lane's top input chest.
        List<Column> columns = detectStaircaseColumns(regionContainers);
        if (columns.isEmpty()) {
            // Compatibility fallback for a stash without hopper staircases. Only permanent
            // non-import storage blocks are eligible destinations; placed shulkers are source
            // contents and explicit imports can never become destinations.
            Set<int[]> positions = regionContainers.stream()
                    .filter(StashOrganizer::isPermanentStorage)
                    .filter(entry -> !index.isImportChest(entry))
                    .map(e -> new int[]{e.x(), e.y(), e.z()})
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            columns = detectColumns(positions);
        } else {
            columns = excludeImportColumns(columns);
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

        planningContainers = planningContainers.stream()
                .filter(container -> OrganizerOwnershipPolicy.isManagedSource(
                        posToColumn.containsKey(posKey(container.x(), container.y(), container.z())),
                        index.isImportChest(container)))
                .toList();
        if (planningContainers.stream().anyMatch(container ->
                container.isDouble() && !container.inventoryIdentityKnown())) {
            info("Organization blocked: legacy double-chest rows do not identify their shared inventory. Run a fresh scan.");
            emit("organize_planning_blocked", Map.of("reason", "double_chest_identity_requires_fresh_scan"));
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            return;
        }
        managedSourceContainerKeys.clear();
        planningContainers.forEach(container -> managedSourceContainerKeys.add(
                posKey(container.x(), container.y(), container.z())));

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

        // Step 2b: Classify each physical shulker. Only exact single-variant boxes are bulk
        // inventory. Empty boxes are packing supplies; mixed boxes are preserved for future
        // explicit kit classification and must never be guessed from a majority item.
        record ShulkerLoc(int[] pos, int slot, String shulkerType, String storageKey,
                          String fingerprint, int contentWeight) {}
        Map<String, List<ShulkerLoc>> shulkersByContent = new LinkedHashMap<>();
        Set<Long> containersWithProtectedShulkers = new HashSet<>();
        int bulkShulkers = 0;
        int emptyShulkers = 0;
        int mixedShulkers = 0;
        int unclassifiedShulkers = 0;

        for (ContainerEntry container : planningContainers) {
            int[] pos = {container.x(), container.y(), container.z()};
            int observedDetails = 0;
            for (ContainerEntry.ShulkerDetail sd : container.shulkerDetails()) {
                observedDetails++;
                if (!sd.isPhysicalInstance()) {
                    unclassifiedShulkers++;
                    containersWithProtectedShulkers.add(posKey(pos[0], pos[1], pos[2]));
                    continue;
                }
                ShulkerClassification classification = ShulkerClassification.classify(sd.items());
                switch (classification.kind()) {
                    case EMPTY -> {
                        emptyShulkers++;
                        containersWithProtectedShulkers.add(posKey(pos[0], pos[1], pos[2]));
                    }
                    case MIXED -> {
                        mixedShulkers++;
                        containersWithProtectedShulkers.add(posKey(pos[0], pos[1], pos[2]));
                    }
                    case BULK -> {
                        int contentWeight = classification.contents().values().stream()
                                .mapToInt(Integer::intValue).sum();
                        shulkersByContent.computeIfAbsent(classification.storageKey(), k -> new ArrayList<>())
                                .add(new ShulkerLoc(pos, sd.slot(), sd.color(), classification.storageKey(),
                                        classification.fingerprint(), contentWeight));
                        bulkShulkers++;
                    }
                }
            }
            int detailsMissingFromSnapshot = Math.max(0, container.shulkerCount() - observedDetails);
            if (detailsMissingFromSnapshot > 0) {
                unclassifiedShulkers += detailsMissingFromSnapshot;
                containersWithProtectedShulkers.add(posKey(pos[0], pos[1], pos[2]));
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

        // Step 3: Assign one exact class per lane, with a hard per-lane shulker-slot check.
        Set<Integer> unavailableColumnIds = new HashSet<>();
        for (long containerKey : containersWithProtectedShulkers) {
            Column protectedColumn = posToColumn.get(containerKey);
            if (protectedColumn != null) unavailableColumnIds.add(protectedColumn.id());
        }

        List<LaneStorageCapacity.Demand> storageDemands = new ArrayList<>();
        for (String storageClass : assignmentLocations.keySet()) {
            long looseItems = itemLocations.getOrDefault(storageClass, List.of()).stream()
                    .mapToLong(ItemLocation::quantity).sum();
            List<Integer> existingCounts = shulkersByContent.getOrDefault(storageClass, List.of()).stream()
                    .map(ShulkerLoc::contentWeight)
                    .toList();
            storageDemands.add(LaneStorageCapacity.Demand.calculate(
                    storageClass, looseItems, existingCounts, shulkerCapacityFor(storageClass)));
        }

        Map<Integer, Column> columnsById = columns.stream()
                .collect(Collectors.toMap(Column::id, column -> column));
        List<LaneStorageCapacity.Lane> availableStorageLanes = columns.stream()
                .filter(column -> !unavailableColumnIds.contains(column.id()))
                .map(column -> toStorageLane(column, regionByPosition))
                .toList();

        // Persisted assignments are preferences, not permission to overflow. A preference is
        // ignored when its lane is protected or too small for the current demand.
        Map<String, int[]> persistedAssignments;
        try {
            var db = StashManagerPlugin.getDatabase();
            persistedAssignments = db != null ? db.loadColumnAssignments() : Map.of();
        } catch (SQLException e) {
            persistedAssignments = Map.of();
        }
        Map<String, Integer> preferredLaneIds = new LinkedHashMap<>();
        for (String storageClass : assignmentLocations.keySet()) {
            int[] persisted = persistedAssignments.get(storageClass);
            if (persisted != null) {
                Column preferred = topPosToColumn.get(posKey(persisted[0], persisted[1], persisted[2]));
                if (preferred != null && !unavailableColumnIds.contains(preferred.id())) {
                    preferredLaneIds.put(storageClass, preferred.id());
                    continue;
                }
            }

            // With no persisted assignment, prefer the lane already holding the most evidence
            // for this class. The capacity calculator may still reject it when too small.
            List<ItemLocation> evidence = new ArrayList<>(assignmentLocations.get(storageClass));
            evidence.sort(Comparator.comparingInt(ItemLocation::quantity).reversed());
            for (ItemLocation location : evidence) {
                Column preferred = posToColumn.get(posKey(
                        location.pos()[0], location.pos()[1], location.pos()[2]));
                if (preferred != null && !unavailableColumnIds.contains(preferred.id())) {
                    preferredLaneIds.put(storageClass, preferred.id());
                    break;
                }
            }
        }

        LaneStorageCapacity.Report storageCapacity = LaneStorageCapacity.assess(
                storageDemands, availableStorageLanes, preferredLaneIds);
        if (!storageCapacity.feasible()) {
            DedicatedLaneCapacity capacity = DedicatedLaneCapacity.assess(
                    columns.size(), unavailableColumnIds.size(), storageDemands.size());
            boolean countShortfall = !capacity.feasible();
            String reason = countShortfall
                    ? "insufficient_dedicated_lanes"
                    : "insufficient_lane_storage";
            String unassigned = storageCapacity.unassigned().stream()
                    .map(demand -> demand.storageClass() + ":" + demand.requiredShulkerSlots())
                    .collect(Collectors.joining(","));
            info("Organization blocked: " + storageCapacity.unassigned().size()
                    + " bulk class(es) cannot fit an assignable lane.");
            emit("organize_planning_blocked", Map.ofEntries(
                    Map.entry("reason", reason),
                    Map.entry("detected_lanes", capacity.detectedLanes()),
                    Map.entry("assignable_lanes", capacity.assignableLanes()),
                    Map.entry("protected_lanes", capacity.protectedLanes()),
                    Map.entry("required_storage_classes", capacity.requiredStorageClasses()),
                    Map.entry("lane_shortfall", capacity.laneShortfall()),
                    Map.entry("assignable_shulker_slots", storageCapacity.totalAssignableShulkerSlots()),
                    Map.entry("required_shulker_slots", storageCapacity.totalRequiredShulkerSlots()),
                    Map.entry("unassigned_required_shulker_slots", storageCapacity.unassignedRequiredShulkerSlots()),
                    Map.entry("unassigned_storage_classes", unassigned),
                    Map.entry("bulk_shulkers", bulkShulkers),
                    Map.entry("empty_shulkers", emptyShulkers),
                    Map.entry("mixed_shulkers", mixedShulkers),
                    Map.entry("unclassified_shulkers", unclassifiedShulkers)
            ));
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            return;
        }

        columnAssignment = new LinkedHashMap<>();
        for (LaneStorageCapacity.Allocation allocation : storageCapacity.allocations()) {
            Column column = columnsById.get(allocation.lane().id());
            if (column != null) columnAssignment.put(allocation.demand().storageClass(), column);
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
        if (shulkerMoves > 0) summary.append(", ").append(shulkerMoves).append(" shulker sorts");
        summary.append(").");
        info(summary.toString());
        emit("organize_planned", Map.of(
            "planned_moves", totalTasks,
            "columns", columns.size(),
            "item_types", itemLocations.size(),
            "condense_types", condenseTypes,
            "protected_lanes", unavailableColumnIds.size(),
            "bulk_shulkers", bulkShulkers,
            "empty_shulkers", emptyShulkers,
            "mixed_shulkers", mixedShulkers,
            "unclassified_shulkers", unclassifiedShulkers,
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
    private boolean queueInventoryDepositTasks(boolean prioritize) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return false;

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
        Map<String, MoveTask> alreadyQueued = taskQueue.stream()
                .filter(MoveTask::alreadyInInventory)
                .collect(Collectors.toMap(this::inventoryTaskKey, task -> task, (a, b) -> a,
                        LinkedHashMap::new));
        consolidationQueue.stream()
                .filter(MoveTask::alreadyInInventory)
                .forEach(task -> alreadyQueued.putIfAbsent(inventoryTaskKey(task), task));
        Set<String> newlyQueued = new HashSet<>();
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
                ShulkerClassification classification = ShulkerClassification.classify(
                        ItemIdentifier.readShulkerContents(stack));
                if (classification.kind() != ShulkerClassification.Kind.BULK) continue;
                contentFilter = classification.storageKey();
                columnKey = classification.storageKey();
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
                MoveTask existing = alreadyQueued.get(key);
                if (existing != null) {
                    if (prioritize) inventoryTasks.putIfAbsent(key, existing);
                } else if (inventoryTasks.putIfAbsent(key, task) == null) {
                    newlyQueued.add(key);
                }
                continue;
            }
            MoveTask task = new MoveTask(currentPos, col.top(), itemId, contentFilter, true);
            String key = inventoryTaskKey(task);
            MoveTask existing = alreadyQueued.get(key);
            if (existing != null) {
                if (prioritize) inventoryTasks.putIfAbsent(key, existing);
            } else if (inventoryTasks.putIfAbsent(key, task) == null) {
                newlyQueued.add(key);
            }
        }

        if (!inventoryTasks.isEmpty()) {
            List<MoveTask> uniqueTasks = new ArrayList<>(inventoryTasks.values());
            if (prioritize) {
                // Promote existing recovery tasks too. Otherwise a newly requeued blocked
                // source can remain ahead of the task that would free its inventory room.
                uniqueTasks.forEach(taskQueue::remove);
                uniqueTasks.forEach(consolidationQueue::remove);
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
                totalTasks += newlyQueued.size();
            }
        }
        if (skippedNoColumn > 0) {
            info(skippedNoColumn + " inventory item(s) left in inventory — no matching stash column yet.");
        }
        return !inventoryTasks.isEmpty();
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
    static List<ContainerEntry> deduplicateDoubleChestInventories(Collection<ContainerEntry> containers) {
        Map<Long, ContainerEntry> freshestByInventory = new LinkedHashMap<>();
        for (ContainerEntry entry : containers) {
            long key = entry.isDouble() && entry.inventoryIdentityKnown()
                    ? entry.inventoryKey()
                    : entry.posKey();
            freshestByInventory.merge(key, entry, (current, candidate) ->
                    candidate.timestamp() > current.timestamp()
                            || (candidate.timestamp() == current.timestamp()
                                && candidate.posKey() < current.posKey())
                            ? candidate
                            : current);
        }
        return new ArrayList<>(freshestByInventory.values());
    }

    static List<Column> detectStaircaseColumns(Collection<ContainerEntry> containers) {
        IndexedStorageGeometry storage = new IndexedStorageGeometry(containers);

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

            ContainerEntry input = storage.findAt(
                    hopper.x(), hopper.y() + 1, hopper.z(), dx, dz);
            ContainerEntry output = storage.findAt(
                    hopper.x() + dx, hopper.y(), hopper.z() + dz, dx, dz);
            if (input == null || output == null) continue;

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

    /** Import inventories are intake-only, even if a hopper happens to touch one. */
    private List<Column> excludeImportColumns(List<Column> columns) {
        List<Column> result = new ArrayList<>();
        for (Column column : columns) {
            boolean containsImport = column.chests().stream()
                    .anyMatch(pos -> index.isImportChest(pos[0], pos[1], pos[2]));
            if (OrganizerOwnershipPolicy.isDestination(true, containsImport)) {
                result.add(new Column(result.size(), column.chests()));
            }
        }
        return result;
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
        if (isAtWalkTargetAccessPosition()) {
            BARITONE.stop();
            onArrived();
            return;
        }

        if (walkingTicks > config.organizerWalkTimeoutTicks) {
            BARITONE.stop();
            trackedWalkTargetKey = Long.MIN_VALUE;
            if (state == State.SHULKER_STATION_WALK) {
                abortWithCargo("reconciliation_station_unreachable_with_cargo",
                        "Could not return to the starting-position reconciliation station; cargo is preserved in inventory.");
            } else if (currentRole == TargetRole.DESTINATION && currentTask != null) {
                retryOrAbortCargoDestination("destination_walk_retry");
            } else {
                retryUntouchedSourceAtTail("source_walk_timeout");
            }
            return;
        }

        if (!BARITONE.getCustomGoalProcess().isActive()) {
            pathToWalkTarget();
        }
    }

    private void pathToWalkTarget() {
        setBaritoneBreakingAllowed(false);
        if (state == State.SHULKER_STATION_WALK) {
            BARITONE.pathTo(new GoalBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2])));
        } else {
            BARITONE.pathTo(new GoalGetToBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2])));
        }
    }

    private boolean isAtWalkTargetAccessPosition() {
        if (walkTarget == null) return false;
        var playerCache = CACHE.getPlayerCache();
        if (state == State.SHULKER_STATION_WALK) {
            return (int) Math.floor(playerCache.getX()) == walkTarget[0]
                    && (int) Math.floor(playerCache.getY()) == walkTarget[1]
                    && (int) Math.floor(playerCache.getZ()) == walkTarget[2];
        }
        return ContainerApproach.isAtAccessPosition(
            playerCache.getX(), playerCache.getY(), playerCache.getZ(),
            walkTarget[0], walkTarget[1], walkTarget[2]);
    }

    private void onArrived() {
        openWaitTicks = 0;
        containerDataReceived = false;
        containerOpenGate.reset();
        switch (state) {
            case SHULKER_STATION_WALK -> {
                state = State.SHULKER_SELECTING;
            }
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
        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (containerDataReceived) {
            BARITONE.stop();
            if (currentRole == TargetRole.DESTINATION) {
                destinationOpenFailures.remove(destinationCargoKey());
            }
            actionSlotIndex = 0;
            actionCooldown = 0;
            movedThisVisit = 0;
            sourceVisitFailed = false;
            state = (currentRole == TargetRole.SOURCE) ? State.TAKING : State.DEPOSITING;
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            BARITONE.stop();
            if (currentRole == TargetRole.DESTINATION && currentTask != null) {
                retryOrAbortCargoDestination("destination_open_retry");
                return;
            }
            retryUntouchedSourceAtTail("source_open_timeout");
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
        java.util.Set<String> actualStorageKeys = new java.util.LinkedHashSet<>();

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
                        ShulkerClassification classification = ShulkerClassification.classify(
                                ItemIdentifier.readShulkerContents(stack));
                        String storageKey = classification.kind() == ShulkerClassification.Kind.BULK
                                ? classification.storageKey()
                                : classification.kind().name().toLowerCase(Locale.ROOT);
                        actualStorageKeys.add(storageKey);
                        if (classification.kind() != ShulkerClassification.Kind.BULK
                                || !ItemIdentifier.contentItemIdsMatch(
                                        currentTask.shulkerContentFilter(), classification.storageKey())) {
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
                        if (!queueInventoryDepositTasks(true)) {
                            abortWithCargo("inventory_full_no_recovery_destination",
                                    "Inventory is full and no safe recovery deposit can be scheduled.");
                            return;
                        }
                        advanceToNextTask();
                        return;
                    }

                    if (quickMoveSlot(actionSlotIndex)) {
                        sourceTaskFailures.remove(moveTaskKey(currentTask));
                        actionSlotIndex++;
                        movedThisVisit++;
                        if (currentTask.shulkerContentFilter() != null) {
                            // A relocation task represents one physical shulker. End this visit
                            // after one accepted transfer; the next physical task will reopen the
                            // source rather than one task draining every same-colored box.
                            actionSlotIndex = chestSlots;
                        }
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
                "actual_storage_classes_seen", actualStorageKeys.toString()
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
                if (consolidationMode && consolidationSourcesInBatch > 0 && currentTask != null) {
                    startShulkerPacking(currentTask.itemId(), currentTask.destination());
                } else {
                    advanceToNextTask();
                }
            } else if (consolidationMode) {
                consolidationSourcesInBatch++;
                if (!continueCollectingCurrentBulkBatch()) {
                    startShulkerPacking(currentTask.itemId(), currentTask.destination());
                }
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
                        ShulkerClassification classification = ShulkerClassification.classify(
                                ItemIdentifier.readShulkerContents(stack));
                        if (classification.kind() != ShulkerClassification.Kind.BULK
                                || !ItemIdentifier.contentItemIdsMatch(
                                        currentTask.shulkerContentFilter(), classification.storageKey())) {
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
        if (!prepareStandingContainerInteraction()) return;
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
        int partialSlot = -1;
        int emptySlot = -1;
        for (int slot = 0; slot < chestSlots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (!isShulkerBoxItem(itemId)) continue;
                ShulkerClassification classification = ShulkerClassification.classify(
                        ItemIdentifier.readShulkerContents(stack));
                if (isCompatiblePartialBulkShulker(classification)) {
                    partialSlot = slot;
                    break;
                }
                if (classification.kind() == ShulkerClassification.Kind.EMPTY && emptySlot < 0) {
                    emptySlot = slot;
                }
            }
        }

        int packingSlot = partialSlot >= 0 ? partialSlot : emptySlot;
        if (packingSlot >= 0) {
            // Prefer topping off an exact matching partial bulk box. Fall back to an empty box;
            // mixed boxes are never candidates. The live stack is authoritative over the index.
            if (!quickMoveSlot(packingSlot)) {
                actionCooldown = config.organizerClickCooldownTicks;
                return;
            }
            // InventoryManager rejects a CloseContainer submitted in the same tick as a
            // ShiftClick. Let the click settle before closing the source.
            fetchedPackingShulker = true;
            actionCooldown = 0;
            state = State.SHULKER_FETCH_CLOSING;
            return;
        }

        // This was a false positive from the indexed summary (all its boxes are filled).
        // Close cleanly and try the next candidate before declaring that no packing box exists.
        fetchedPackingShulker = false;
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
            if (fetchedPackingShulker) {
                state = State.SHULKER_SELECTING;
                shulkerTicks = 0;
            } else {
                startFetchShulker();
            }
        }
    }

    // SHULKER STORE — deposit filled shulker into destination
    private void tickShulkerStoreOpen() {
        if (!prepareStandingContainerInteraction()) return;
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
                if (!isShulkerBoxItem(itemId)) {
                    actionSlotIndex++;
                    continue;
                }
                ShulkerClassification classification = ShulkerClassification.classify(
                        ItemIdentifier.readShulkerContents(stack));
                if (classification.kind() == ShulkerClassification.Kind.BULK
                        && ItemIdentifier.contentItemIdsMatch(packItemId, classification.storageKey())) {
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
            if (consolidationMode) {
                completedTasks += Math.max(0, consolidationSourcesInBatch);
                consolidationSourcesInBatch = 0;
            } else {
                completedTasks++;
            }
        } else {
            emit("organize_target_failed", Map.of("reason", "packed_shulker_not_found_in_inventory"));
        }

        if (consolidationMode) {
            if (countItemInInventory(packItemId) > 0) {
                // A gathered batch may span more than one shulker's 27 slots. Finish the
                // remaining loose cargo before visiting another source or declaring success.
                startShulkerPacking(packItemId, packDestination);
                return;
            }
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
        this.fetchedPackingShulker = false;
        resetTemporaryShulkerState();
        info("Returning to the reconciliation station to pack: " + itemId);
        walkTarget = reconciliationStation;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.SHULKER_STATION_WALK;
        shulkerTicks = 0;
    }

    private void tickShulkerSelecting() {
        shulkerTicks++;
        
        // Find empty shulker in inventory
        int shulkerSlot = findPackingShulkerInInventory();
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

        // The worksite is fixed when organization starts. Revalidate it after returning rather
        // than choosing a convenient shelf near whichever source was visited last.
        shulkerPlacePos = reconciliationWorksite;
        if (!isShulkerWorksiteSafe(shulkerPlacePos, reconciliationStation)) {
            abortWithCargo("reconciliation_station_unsafe",
                    "The starting-position reconciliation worksite is no longer safe; items are preserved in inventory.");
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
        shulkerInventoryCountBeforePlacement = countShulkerBoxesInInventory();
        moveShulkerToHotbar(shulkerSlot);

        state = State.SHULKER_PLACING;
        shulkerTicks = 0;
        shulkerPlaceRetries = 0;
        shulkerPlaceFuture = null;
    }

    private void tickShulkerPlacing() {
        shulkerTicks++;

        if (isShulkerAtPosition(shulkerPlacePos)) {
            temporaryShulkerOutstanding = true;
            containerOpenGate.reset();
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
            temporaryShulkerOutstanding = true;
            containerOpenGate.reset();
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
            if (shulkerPlaceFuture != null && !shulkerPlaceFuture.isDone()) {
                info("Shulker placement result remained pending; holding position for safe recovery.");
                startOverflow();
                return;
            }
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
        if (!prepareStandingContainerInteraction()) return;
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
                temporaryShulkerOutstanding = false;
                // Store it in destination
                walkTarget = packDestination;
                state = State.SHULKER_STORE_WALK;
                openWaitTicks = 0;
                containerDataReceived = false;
            } else if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
                info("Shulker pickup failed");
                startOverflow();
            }
        }
    }

    /**
     * Cleanup transaction for a temporary shulker which was placed by this organizer. No
     * overflow, queue advance, or manual stop may make the bot leave until the block has been
     * broken and its item has returned to inventory.
     */
    private void beginTemporaryShulkerRecovery(String trigger) {
        closeCurrentContainer();
        BARITONE.stop();
        shulkerRecoveryTrigger = trigger;
        shulkerRecoveryBreakAttempts = 0;
        shulkerBreakFuture = null;
        shulkerTicks = 0;
        state = isShulkerAtPosition(shulkerPlacePos)
                ? State.SHULKER_RECOVERY_BREAKING
                : State.SHULKER_RECOVERY_PICKUP;
        emit("organize_target_failed", Map.of(
                "reason", "temporary_shulker_recovery_started",
                "trigger", trigger,
                "shulker_position", posString(shulkerPlacePos)
        ));
    }

    private void tickShulkerRecoveryBreaking() {
        shulkerTicks++;

        if (!isShulkerAtPosition(shulkerPlacePos)) {
            shulkerBreakFuture = null;
            shulkerTicks = 0;
            state = State.SHULKER_RECOVERY_PICKUP;
            return;
        }

        boolean rejected = shulkerBreakFuture != null
                && shulkerBreakFuture.isDone()
                && !shulkerBreakFuture.getNow();
        if (rejected || shulkerTicks > BREAK_TIMEOUT_TICKS) {
            shulkerRecoveryBreakAttempts++;
            if (shulkerRecoveryBreakAttempts >= MAX_SHULKER_RECOVERY_BREAK_ATTEMPTS) {
                abortTemporaryShulkerRecovery("temporary_shulker_break_recovery_failed");
                return;
            }
            info("Temporary shulker recovery break retry "
                    + (shulkerRecoveryBreakAttempts + 1) + "/"
                    + MAX_SHULKER_RECOVERY_BREAK_ATTEMPTS + ".");
            shulkerBreakFuture = null;
            shulkerTicks = 0;
        }

        if (shulkerBreakFuture == null) {
            shulkerBreakFuture = BaritoneCompat.breakBlock(
                    shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], true);
        }
    }

    private void tickShulkerRecoveryPickup() {
        shulkerTicks++;

        // If the server restored the block after a rejected/rolled-back break, recover it as a
        // block again rather than assuming the item entity exists.
        if (isShulkerAtPosition(shulkerPlacePos)) {
            shulkerBreakFuture = null;
            shulkerTicks = 0;
            state = State.SHULKER_RECOVERY_BREAKING;
            return;
        }

        // Do not declare cleanup complete while a place request can still succeed late.
        if (shulkerPlaceFuture != null && !shulkerPlaceFuture.isDone()) {
            if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
                abortTemporaryShulkerRecovery("temporary_shulker_placement_outcome_unknown");
            }
            return;
        }

        if (shulkerTicks >= PICKUP_DELAY_TICKS
                && countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement) {
            temporaryShulkerOutstanding = false;
            emit("organize_recovery_completed", Map.of(
                    "reason", "temporary_shulker_recovered",
                    "trigger", Objects.toString(shulkerRecoveryTrigger, "unknown"),
                    "shulker_position", posString(shulkerPlacePos)
            ));
            if (stopAfterShulkerRecovery) {
                resetTemporaryShulkerState();
                finishStop();
            } else {
                resetTemporaryShulkerState();
                startOverflowAfterShulkerCleanup();
            }
            return;
        }

        if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
            abortTemporaryShulkerRecovery("temporary_shulker_pickup_recovery_failed");
        }
    }

    private void abortTemporaryShulkerRecovery(String reason) {
        info("Temporary shulker recovery failed at " + posString(shulkerPlacePos)
                + "; stopping in place for manual recovery.");
        emit("organize_failed", Map.of(
                "reason", reason,
                "trigger", Objects.toString(shulkerRecoveryTrigger, "unknown"),
                "shulker_position", posString(shulkerPlacePos),
                "manual_intervention_required", true
        ));
        BARITONE.stop();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.DONE;
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
            if (isManagedSourceContainer(container)) {
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
        if (temporaryShulkerOutstanding
                || (shulkerPlaceFuture != null && !shulkerPlaceFuture.isDone())) {
            // An unresolved placement request can still materialize after this tick. Treat it
            // as outstanding until its future settles so we never walk away from a late box.
            temporaryShulkerOutstanding = true;
            beginTemporaryShulkerRecovery("packing_failure_from_" + state.name().toLowerCase(Locale.ROOT));
            return;
        }
        startOverflowAfterShulkerCleanup();
    }

    private void startOverflowAfterShulkerCleanup() {
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
        if (!prepareStandingContainerInteraction()) return;
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
        consolidationSourcesInBatch = 0;
        if (currentTask.alreadyInInventory()) {
            consolidationSourcesInBatch = 1;
            startShulkerPacking(currentTask.itemId(), currentTask.destination());
            return;
        }
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
    }

    /** Collect consecutive source tasks for one exact storage key before consuming a box. */
    private boolean continueCollectingCurrentBulkBatch() {
        if (currentTask == null) return false;
        boolean inventoryHasRoom = hasInventoryRoom();
        MoveTask next = consolidationQueue.peekFirst();
        if (next == null || !BulkBatchPlanner.shouldCollectNext(
                currentTask.itemId(), next.itemId(), next.alreadyInInventory(), inventoryHasRoom)) return false;

        currentTask = consolidationQueue.pollFirst();
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.WALKING;
        return true;
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
    private void closeUnexpectedContainer(Session session, int containerId) {
        // Do not enqueue this behind InventoryManager work: a late open is itself an ownership
        // violation and must be closed immediately on the same client session. Zenith's
        // post-outgoing close handler also synchronizes its inventory cache.
        try {
            session.send(new ServerboundContainerClosePacket(containerId));
        } catch (Exception ignored) {
        }
    }

    private String destinationCargoKey() {
        if (currentTask == null) return "none";
        int[] destination = currentTask.destination();
        return posKey(destination[0], destination[1], destination[2]) + "\u0000"
                + currentTask.itemId() + "\u0000"
                + Objects.toString(currentTask.shulkerContentFilter(), "");
    }

    /**
     * Once a source visit moved cargo into the player inventory, destination opening is a
     * transaction boundary. Retry that same destination without returning to source work or
     * generating more recovery tasks. Bounded failure leaves the cargo safely in inventory.
     */
    private void retryOrAbortCargoDestination(String retryReason) {
        String key = destinationCargoKey();
        int attempt = destinationOpenFailures.merge(key, 1, Integer::sum);
        if (attempt <= MAX_DESTINATION_OPEN_RETRIES) {
            info("Destination open timed out; retrying cargo deposit (" + attempt + "/"
                    + MAX_DESTINATION_OPEN_RETRIES + ").");
            emit("organize_target_failed", Map.of(
                    "reason", retryReason,
                    "attempt", attempt,
                    "max_attempts", MAX_DESTINATION_OPEN_RETRIES
            ));
            closeCurrentContainer();
            currentRole = TargetRole.DESTINATION;
            walkTarget = currentTask.destination();
            openWaitTicks = 0;
            containerDataReceived = false;
            trackedWalkTargetKey = Long.MIN_VALUE;
            state = State.WALKING;
            return;
        }

        abortWithCargo("destination_unreachable_with_cargo",
                "Destination remained unreachable after " + attempt
                        + " attempts; cargo is preserved in inventory.");
    }

    /** An untouched source failure is safe to defer so other independent work can proceed. */
    private void retryUntouchedSourceAtTail(String reason) {
        if (currentTask == null) {
            advanceToNextTask();
            return;
        }
        String key = moveTaskKey(currentTask);
        int attempt = sourceTaskFailures.merge(key, 1, Integer::sum);
        if (attempt < MAX_SOURCE_TASK_RETRIES) {
            info("Source task failed before moving cargo; requeueing at tail (" + attempt + "/"
                    + MAX_SOURCE_TASK_RETRIES + ").");
            emit("organize_target_failed", Map.of(
                    "reason", reason,
                    "retry_disposition", "queue_tail",
                    "attempt", attempt,
                    "max_attempts", MAX_SOURCE_TASK_RETRIES
            ));
            if (consolidationMode) consolidationQueue.addLast(currentTask);
            else taskQueue.addLast(currentTask);
        } else {
            info("Source task exhausted " + MAX_SOURCE_TASK_RETRIES + " attempts; quarantining it.");
            emit("organize_target_failed", Map.of(
                    "reason", "source_retry_exhausted",
                    "last_failure", reason,
                    "attempts", attempt
            ));
        }
        advanceToNextTask();
    }

    private String moveTaskKey(MoveTask task) {
        int[] source = task.source();
        return posKey(source[0], source[1], source[2]) + "\u0000" + inventoryTaskKey(task);
    }

    private void abortWithCargo(String reason, String message) {
        info(message);
        emit("organize_failed", Map.of("reason", reason));
        BARITONE.stop();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.DONE;
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

    private int[] findOverflowChest() {
        List<ContainerEntry> imports = index.getInRegion(config.pos1, config.pos2).stream()
                .filter(index::isImportChest)
                .toList();
        for (ContainerEntry entry : imports) {
            if (entry.totalItems() < 27 * 64) {
                return new int[]{entry.x(), entry.y(), entry.z()};
            }
        }
        return imports.isEmpty() ? null : new int[]{imports.get(0).x(), imports.get(0).y(), imports.get(0).z()};
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
            case SHULKER_STATION_WALK -> "Returning to reconciliation station...";
            case SHULKER_SELECTING, SHULKER_PLACING, SHULKER_WAIT_PLACE, SHULKER_OPENING, 
                 SHULKER_FILLING, SHULKER_CLOSING, SHULKER_BREAKING, SHULKER_PICKUP
                                   -> "Packing items into shulker...";
            case SHULKER_RECOVERY_BREAKING, SHULKER_RECOVERY_PICKUP
                                   -> "Recovering temporary shulker...";
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
    private void resetTemporaryShulkerState() {
        temporaryShulkerOutstanding = false;
        stopAfterShulkerRecovery = false;
        shulkerRecoveryTrigger = null;
        shulkerRecoveryBreakAttempts = 0;
        shulkerInventoryCountBeforePlacement = 0;
        shulkerPlacePos = null;
        shulkerPlaceFuture = null;
        shulkerBreakFuture = null;
    }

    private int countShulkerBoxesInInventory() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return 0;
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0
                    && isShulkerBoxItem(itemIdFromStack(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private int findPackingShulkerInInventory() {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return -1;

        int emptySlot = -1;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (!isShulkerBoxItem(itemId)) continue;
                ShulkerClassification classification = ShulkerClassification.classify(
                        ItemIdentifier.readShulkerContents(stack));
                if (isCompatiblePartialBulkShulker(classification)) return i;
                if (classification.kind() == ShulkerClassification.Kind.EMPTY && emptySlot < 0) emptySlot = i;
            }
        }
        return emptySlot;
    }

    private boolean isCompatiblePartialBulkShulker(ShulkerClassification classification) {
        if (classification.kind() != ShulkerClassification.Kind.BULK
                || !ItemIdentifier.contentItemIdsMatch(packItemId, classification.storageKey())) {
            return false;
        }
        int count = classification.contents().values().stream().mapToInt(Integer::intValue).sum();
        return count < shulkerCapacityFor(classification.storageKey());
    }

    private static int shulkerCapacityFor(String itemId) {
        String baseId = ItemIdentifier.baseItemId(itemId);
        ItemData data = baseId == null ? null : ItemRegistry.REGISTRY.get("minecraft:" + baseId);
        int stackSize = data == null ? 64 : Math.max(1, data.stackSize());
        return 27 * stackSize;
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
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() == ShulkerClassification.Kind.BULK
                    && ItemIdentifier.contentItemIdsMatch(packItemId, classification.storageKey())) return true;
        }
        return false;
    }

    private boolean hasPotentialShulkerInRegion() {
        for (ContainerEntry container : index.getAll()) {
            if (isManagedSourceContainer(container)) {
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
        // Prefer a known matching partial box globally before consuming an empty one. The live
        // window still revalidates the classification because the index is only a snapshot.
        for (ContainerEntry container : index.getAll()) {
            if (isManagedSourceContainer(container)
                    && containerHasCompatiblePartialShulker(container)) {
                long key = posKey(container.x(), container.y(), container.z());
                if (shulkerFetchTriedSources.add(key)) {
                    beginShulkerFetchWalk(container);
                    return;
                }
            }
        }

        // Visit each remaining candidate at most once and let the live window decide whether
        // it contains an empty box. Mixed boxes never qualify in tickShulkerFetchTake().
        for (ContainerEntry container : index.getAll()) {
            if (isManagedSourceContainer(container)) {
                for (String itemId : container.items().keySet()) {
                    long key = posKey(container.x(), container.y(), container.z());
                    if (isShulkerBoxItem(itemId) && shulkerFetchTriedSources.add(key)) {
                        beginShulkerFetchWalk(container);
                        return;
                    }
                }
            }
        }
        startOverflow();
    }

    private boolean containerHasCompatiblePartialShulker(ContainerEntry container) {
        for (ContainerEntry.ShulkerDetail detail : container.shulkerDetails()) {
            if (!detail.isPhysicalInstance()) continue;
            if (isCompatiblePartialBulkShulker(ShulkerClassification.classify(detail.items()))) return true;
        }
        return false;
    }

    private void beginShulkerFetchWalk(ContainerEntry container) {
        walkTarget = new int[]{container.x(), container.y(), container.z()};
        state = State.SHULKER_FETCH_WALK;
        openWaitTicks = 0;
        containerDataReceived = false;
    }

    // Select one fixed work pad around the organizer's starting position. Every loose-item
    // batch returns here; source/destination shelves are never considered packing surfaces.
    private int[] findShulkerPlaceSpot(int[] station) {
        if (station == null) return null;
        int px = station[0];
        int py = station[1];
        int pz = station[2];

        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                // A reconciliation work pad must be on the bot's current floor. Searching one
                // block up/down admitted shelf and staircase surfaces which were clickable but
                // unsafe to leave occupied during the rest of the organization job.
                int dy = 0;
                if (dx == 0 && dz == 0) continue; // player's own column — never place under/on self
                int x = px + dx;
                int y = py + dy;
                int z = pz + dz;
                if (!World.isInWorldBounds(x, y, z)) continue;

                int[] candidate = new int[]{x, y, z};
                if (!isShulkerWorksiteSafe(candidate, station)) continue;

                double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean isShulkerWorksiteSafe(int[] pos, int[] station) {
        if (pos == null || station == null || !World.isInWorldBounds(pos[0], pos[1], pos[2])) {
            return false;
        }
        int x = pos[0];
        int y = pos[1];
        int z = pos[2];
        var target = World.getBlock(x, y, z);
        var above = World.getBlock(x, y + 1, z);
        var below = World.getBlock(x, y - 1, z);
        // Baritone tries every neighboring face (down/south/east/north/west/up) to place
        // against, not just the one below — any of them being a container/GUI block means a
        // right-click there can open it instead of placing the shulker.
        boolean interactiveNeighbor = BlockCompat.isInteractable(World.getBlock(x, y, z - 1))
                || BlockCompat.isInteractable(World.getBlock(x, y, z + 1))
                || BlockCompat.isInteractable(World.getBlock(x + 1, y, z))
                || BlockCompat.isInteractable(World.getBlock(x - 1, y, z))
                || BlockCompat.isInteractable(above);
        return ShulkerWorksitePolicy.isSafe(
                y - station[1],
                BlockCompat.canReplace(target),
                BlockCompat.canReplace(above),
                BlockCompat.isAir(below),
                BlockCompat.isSolid(x, y - 1, z),
                BlockCompat.isInteractable(below),
                interactiveNeighbor,
                isIndexedContainerSupport(x, y - 1, z));
    }

    /**
     * The live world cache can briefly report stale air while chunks or block updates settle.
     * Reserve every indexed container footprint as storage infrastructure as a second guard;
     * temporary packing shulkers may only use ordinary solid floor such as glass or obsidian.
     */
    private boolean isIndexedContainerSupport(int x, int y, int z) {
        for (ContainerEntry entry : index.getAll()) {
            if (entry.x() == x && entry.y() == y && entry.z() == z) return true;
        }
        return false;
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
            if (isManagedSourceContainer(container)) {
                Integer qty = container.items().get(itemId);
                if (qty != null) count += qty;
            }
        }
        return count;
    }

    private boolean isManagedSourceContainer(ContainerEntry container) {
        return container != null && managedSourceContainerKeys.contains(
                posKey(container.x(), container.y(), container.z()));
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
