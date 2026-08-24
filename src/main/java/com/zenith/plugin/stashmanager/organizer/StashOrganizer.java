package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.ClickItem;
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
import com.zenith.plugin.stashmanager.orchestration.ImportStagingPolicy;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.orchestration.MixedShulkerPlaybook;
import com.zenith.plugin.stashmanager.orchestration.ProgressMilestones;
import com.zenith.plugin.stashmanager.organizer.lane.IndexedStorageGeometry;
import com.zenith.plugin.stashmanager.orchestration.OrganizerOwnershipPolicy;
import com.zenith.plugin.stashmanager.orchestration.ShulkerWorksitePolicy;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.network.Session;

import java.io.IOException;
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
        SHULKER_EMPTYING,
        MIXED_SOURCE_CLOSING,
        SHULKER_FILLING,
        SHULKER_CLOSING,
        SHULKER_BREAKING,
        SHULKER_PICKUP,
        SHULKER_RESUME_WALK,
        SHULKER_RECOVERY_BREAKING,
        SHULKER_RECOVERY_PICKUP,
        SHULKER_FETCH_WALK,
        SHULKER_FETCH_OPEN,
        SHULKER_FETCH_TAKE,
        SHULKER_FETCH_CLOSING,
        SHULKER_STORE_WALK,
        SHULKER_STORE_OPEN,
        SHULKER_STORE_DEPOSIT,
        // Mixed-shulker decomposition staging
        MIXED_STAGE_WALK,
        MIXED_STAGE_OPEN,
        MIXED_STAGE_DEPOSIT,
        MIXED_STAGE_CLOSING,
        MIXED_RETURN_WALK,
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
        YIELDED,
        DONE
    }

    private enum TargetRole { SOURCE, DESTINATION }

    private enum OwnedBaritoneProcess {
        NONE,
        CUSTOM_GOAL,
        INTERACTION
    }

    private volatile State state = State.IDLE;
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
    record MoveTask(
            int[] source,
            int[] destination,
            String itemId,
            String shulkerContentFilter,
            boolean alreadyInInventory,
            boolean mixedDecomposition,
            boolean mixedBatchConsolidation,
            Map<String, Integer> mixedContents) {
        MoveTask {
            mixedContents = mixedContents == null ? Map.of() : Map.copyOf(mixedContents);
        }
        MoveTask(int[] source, int[] destination, String itemId) {
            this(source, destination, itemId, null, false, false, false, Map.of());
        }
        MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter) {
            this(source, destination, itemId, shulkerContentFilter, false, false, false, Map.of());
        }
        MoveTask(int[] source, int[] destination, String itemId,
                 String shulkerContentFilter, boolean alreadyInInventory) {
            this(source, destination, itemId, shulkerContentFilter, alreadyInInventory,
                    false, false, Map.of());
        }
        static MoveTask mixed(int[] source, int[] stagingDestination, String shulkerItemId,
                              String fingerprint, Map<String, Integer> contents) {
            return new MoveTask(source, stagingDestination, shulkerItemId, fingerprint,
                    false, true, false, contents);
        }
        static MoveTask mixedBatch(int[] source, int[] destination, String itemId) {
            return new MoveTask(source, destination, itemId, null,
                    false, false, true, Map.of());
        }
    }

    private record ItemLocation(int[] pos, int quantity) {}

    // Configuration / References
    private final StashManagerConfig config;
    private final ContainerIndex index;
    private final OrganizerJournalStore journalStore;
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
    private static final int MIN_OPEN_TIMEOUT_TICKS = 400;
    private static final int OPEN_RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_DESTINATION_OPEN_RETRIES = 3;
    private static final int MAX_SOURCE_TASK_RETRIES = 3;
    private static final int MAX_SHULKER_RECOVERY_BREAK_ATTEMPTS = 3;
    private static final int SHULKER_PICKUP_TIMEOUT_TICKS = 100;
    private static final int LATE_OPEN_QUARANTINE_TICKS = 100;

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
    // The keep list protects the exact main-inventory/hotbar slots present when the job
    // starts. Stash cargo of the same item type may still pass through other slots.
    private final Set<Integer> protectedInventorySlots = new TreeSet<>();
    private boolean keepProtectionNeedsRefresh;
    private int consolidationSourcesInBatch;
    private final List<int[]> stagingImportDestinations = new ArrayList<>();
    private final Set<Long> packStoreTriedDestinations = new HashSet<>();
    private final Set<String> stagingStorageClassesPlanned = new TreeSet<>();
    private final Set<String> stagedStorageClasses = new TreeSet<>();
    private int stagedShulkers;
    private int permanentLaneGaps;
    private int packDestinationOpenFailures;
    private int packStoreMatchingShulkersBefore;
    private int packStoreVerificationTicks;
    private String stagingReason;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    private int totalTasks;
    private int completedTasks;
    private int nextProgressMilestone = ProgressMilestones.FIRST;

    // The plan is written only when tasks are added. Small checkpoints then refer to stable
    // task ids, so multi-hour jobs do not rewrite thousands of task definitions per move.
    private final IdentityHashMap<MoveTask, Integer> journalTaskIds = new IdentityHashMap<>();
    private final Map<Integer, MoveTask> journalTasks = new LinkedHashMap<>();
    private String journalJobId;
    private long journalCreatedAtEpochMilli;
    private String journalDimension = "";
    private int nextJournalTaskId = 1;
    private boolean journalPlanDirty;
    private boolean journalPersistenceFailed;
    private boolean durableRecoveryLoaded;
    private String durableRecoveryError;
    private long durableCheckpointUpdatedAtEpochMilli;

    private boolean consolidationMode = false;
    private boolean mixedBatchConsolidationMode;
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
    private int compatibleShulkerCountBeforePlacement;
    private int shulkerRecoveryBreakAttempts;
    private volatile boolean temporaryShulkerOutstanding;
    private boolean stopAfterShulkerRecovery;
    private String shulkerRecoveryTrigger;

    // A mixed box is unloaded into explicitly registered import storage in bounded batches.
    // Cargo slots are exact empty slots chosen by the organizer, so keep-list items are never
    // mistaken for returned-kit cargo even when they share the same item id.
    private boolean mixedDecompositionMode;
    private boolean mixedBoxDrained;
    private int decomposedMixedShulkers;
    private int mixedPendingSourceSlot = -1;
    private int mixedPendingCargoSlot = -1;
    private final Set<Integer> mixedCargoSlots = new TreeSet<>();
    private final List<int[]> mixedStagingUsedDestinations = new ArrayList<>();
    private final Set<Long> mixedUnavailableStagingDestinations = new HashSet<>();

    // Cooperative task handoff state. The organizer keeps its queues and cargo checkpoint but
    // releases Zenith's shared automation until the module's continuance gate allows a resume.
    private volatile State yieldedFromState;
    private volatile String yieldReason;
    private PathingRequestFuture ownedBaritoneRequest;
    private OwnedBaritoneProcess ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
    private RequestFuture ownedInventoryRequest;
    private int lateOpenQuarantineTicks;

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
        this(config, index, OrganizerJournalStore.defaultStore());
    }

    StashOrganizer(StashManagerConfig config, ContainerIndex index, OrganizerJournalStore journalStore) {
        this.config = config;
        this.index = index;
        this.journalStore = journalStore;
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
    public int getStagedShulkers() { return stagedShulkers; }
    public int getStagingStorageClassCount() { return stagingStorageClassesPlanned.size(); }
    public int getPermanentLaneGaps() { return permanentLaneGaps; }
    public int getDecomposedMixedShulkers() { return decomposedMixedShulkers; }
    public boolean isUsingImportStaging() { return !stagingStorageClassesPlanned.isEmpty(); }
    public boolean isYielded() { return state == State.YIELDED; }
    public State getYieldedFromState() { return yieldedFromState; }
    public String getYieldReason() { return yieldReason; }
    public boolean hasTemporaryShulkerOutstanding() { return temporaryShulkerOutstanding; }
    public boolean hasDurableCheckpoint() { return journalJobId != null || journalStore.exists(); }
    public boolean isDurableRecoveryLoaded() { return durableRecoveryLoaded; }
    public String getDurableRecoveryError() { return durableRecoveryError; }
    public long getDurableCheckpointUpdatedAtEpochMilli() { return durableCheckpointUpdatedAtEpochMilli; }

    public enum DurableRestoreResult { NONE, RESTORED, INVALID }

    /** Load a previously planned organizer transaction without touching world state. */
    public DurableRestoreResult restoreDurableCheckpoint() {
        if (isActive()) return DurableRestoreResult.NONE;

        final Optional<OrganizerJournalStore.Loaded> loaded;
        try {
            loaded = journalStore.load();
        } catch (IOException | RuntimeException e) {
            durableRecoveryError = e.getMessage();
            info("Saved organizer checkpoint could not be loaded: " + e.getMessage());
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "journal_load_failed",
                    "message", Objects.toString(e.getMessage(), "unknown error")
            ));
            return DurableRestoreResult.INVALID;
        }
        if (loaded.isEmpty()) return DurableRestoreResult.NONE;

        OrganizerJournalStore.Plan plan = loaded.get().plan();
        OrganizerJournalStore.Checkpoint checkpoint = loaded.get().checkpoint();
        if (!Arrays.equals(config.pos1, plan.regionPos1())
                || !Arrays.equals(config.pos2, plan.regionPos2())) {
            durableRecoveryError = "configured region no longer matches the saved organizer plan";
            info("Saved organizer checkpoint is blocked because the configured region changed.");
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "region_changed",
                    "message", durableRecoveryError
            ));
            return DurableRestoreResult.INVALID;
        }

        final State interrupted;
        final TargetRole restoredRole;
        try {
            interrupted = State.valueOf(checkpoint.interruptedState());
            restoredRole = TargetRole.valueOf(checkpoint.currentRole());
        } catch (IllegalArgumentException e) {
            durableRecoveryError = "saved organizer state is not recognized by this build";
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "state_not_supported",
                    "message", durableRecoveryError
            ));
            return DurableRestoreResult.INVALID;
        }
        if (interrupted == State.IDLE || interrupted == State.YIELDED || interrupted == State.DONE) {
            durableRecoveryError = "saved organizer checkpoint does not contain a resumable state";
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "state_not_resumable",
                    "message", durableRecoveryError
            ));
            return DurableRestoreResult.INVALID;
        }

        taskQueue.clear();
        consolidationQueue.clear();
        journalTaskIds.clear();
        journalTasks.clear();
        int highestTaskId = 0;
        for (OrganizerJournalStore.TaskSnapshot task : plan.tasks()) {
            MoveTask move = new MoveTask(
                    copyPos(task.source()), copyPos(task.destination()), task.itemId(),
                    task.shulkerContentFilter(), task.alreadyInInventory(),
                    task.mixedDecomposition(), task.mixedBatchConsolidation(),
                    task.mixedContents());
            journalTasks.put(task.id(), move);
            journalTaskIds.put(move, task.id());
            highestTaskId = Math.max(highestTaskId, task.id());
        }
        try {
            currentTask = taskForJournalId(checkpoint.currentTaskId());
            for (Integer id : checkpoint.taskQueue()) taskQueue.addLast(requiredJournalTask(id));
            for (Integer id : checkpoint.consolidationQueue()) {
                consolidationQueue.addLast(requiredJournalTask(id));
            }
        } catch (IllegalStateException e) {
            durableRecoveryError = e.getMessage();
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "task_reference_invalid",
                    "message", durableRecoveryError
            ));
            return DurableRestoreResult.INVALID;
        }

        columnAssignment = new LinkedHashMap<>();
        for (var entry : plan.columnAssignments().entrySet()) {
            OrganizerJournalStore.ColumnSnapshot column = entry.getValue();
            List<int[]> chests = column.chests().stream().map(StashOrganizer::copyPos).toList();
            columnAssignment.put(entry.getKey(), new Column(column.id(), chests));
        }
        managedSourceContainerKeys.clear();
        managedSourceContainerKeys.addAll(plan.managedSourceContainerKeys());

        journalJobId = plan.jobId();
        journalCreatedAtEpochMilli = plan.createdAtEpochMilli();
        journalDimension = Objects.toString(plan.dimension(), "");
        nextJournalTaskId = highestTaskId + 1;
        journalPlanDirty = false;
        journalPersistenceFailed = false;

        currentRole = restoredRole;
        consolidationMode = checkpoint.consolidationMode();
        consolidationSourcesInBatch = checkpoint.consolidationSourcesInBatch();
        movedThisVisit = checkpoint.movedThisVisit();
        sourceVisitFailed = checkpoint.sourceVisitFailed();
        totalTasks = checkpoint.totalTasks();
        completedTasks = checkpoint.completedTasks();
        nextProgressMilestone = checkpoint.nextProgressMilestone() >= ProgressMilestones.FIRST
                ? checkpoint.nextProgressMilestone()
                : ProgressMilestones.nextAfterCompleted(completedTasks, totalTasks);
        reconciliationStation = copyNullablePos(checkpoint.reconciliationStation());
        reconciliationWorksite = copyNullablePos(checkpoint.reconciliationWorksite());
        packItemId = checkpoint.packItemId();
        packDestination = copyNullablePos(checkpoint.packDestination());
        shulkerPlacePos = copyNullablePos(checkpoint.shulkerPlacePos());
        fetchedPackingShulker = checkpoint.fetchedPackingShulker();
        shulkerInventoryCountBeforePlacement = checkpoint.shulkerInventoryCountBeforePlacement();
        compatibleShulkerCountBeforePlacement = checkpoint.compatibleShulkerCountBeforePlacement();
        temporaryShulkerOutstanding = checkpoint.temporaryShulkerOutstanding();
        stagingImportDestinations.clear();
        checkpoint.stagingImportDestinations().stream()
                .map(StashOrganizer::copyPos)
                .forEach(stagingImportDestinations::add);
        stagingStorageClassesPlanned.clear();
        stagingStorageClassesPlanned.addAll(checkpoint.stagingStorageClassesPlanned());
        stagedStorageClasses.clear();
        stagedStorageClasses.addAll(checkpoint.stagedStorageClasses());
        stagedShulkers = checkpoint.stagedShulkers();
        permanentLaneGaps = checkpoint.permanentLaneGaps();
        stagingReason = checkpoint.stagingReason();
        overflowItems.clear();
        overflowItems.putAll(checkpoint.overflowItems());
        mixedDecompositionMode = checkpoint.mixedDecompositionMode();
        mixedBatchConsolidationMode = checkpoint.mixedBatchConsolidationMode();
        mixedBoxDrained = checkpoint.mixedBoxDrained();
        decomposedMixedShulkers = checkpoint.decomposedMixedShulkers();
        mixedPendingSourceSlot = checkpoint.mixedDecompositionMode()
                ? checkpoint.mixedPendingSourceSlot()
                : -1;
        mixedPendingCargoSlot = checkpoint.mixedDecompositionMode()
                ? checkpoint.mixedPendingCargoSlot()
                : -1;
        mixedCargoSlots.clear();
        if (checkpoint.mixedCargoSlots() != null) {
            mixedCargoSlots.addAll(checkpoint.mixedCargoSlots());
        }
        mixedStagingUsedDestinations.clear();
        if (checkpoint.mixedStagingUsedDestinations() != null) {
            checkpoint.mixedStagingUsedDestinations().stream()
                    .map(StashOrganizer::copyPos)
                    .forEach(mixedStagingUsedDestinations::add);
        }
        mixedUnavailableStagingDestinations.clear();
        protectedInventorySlots.clear();
        if (checkpoint.protectedInventorySlots() == null) {
            // Repair checkpoints written before keep-slot ownership was persisted before any
            // resumed transfer is allowed to touch the live inventory.
            keepProtectionNeedsRefresh = true;
        } else {
            protectedInventorySlots.addAll(checkpoint.protectedInventorySlots());
            keepProtectionNeedsRefresh = false;
        }

        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        shulkerFetchTriedSources.clear();
        packStoreTriedDestinations.clear();
        currentRole = restoredRole;
        walkTarget = null;
        trackedWalkTargetKey = Long.MIN_VALUE;
        walkingTicks = 0;
        openWaitTicks = 0;
        actionSlotIndex = 0;
        actionCooldown = 0;
        containerDataReceived = false;
        openContainerId = -1;
        ownedBaritoneRequest = null;
        ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
        ownedInventoryRequest = null;
        yieldedFromState = interrupted;
        yieldReason = "process_restart";
        lateOpenQuarantineTicks = 0;
        durableRecoveryLoaded = true;
        durableRecoveryError = null;
        durableCheckpointUpdatedAtEpochMilli = checkpoint.updatedAtEpochMilli();
        state = State.YIELDED;

        info("Loaded organizer restart checkpoint at " + completedTasks + "/" + totalTasks
                + " tasks; normal cooldown and quiet checks will run before resume.");
        emit("organize_checkpoint_restored", Map.of(
                "interrupted_state", interrupted.name(),
                "checkpoint_age_seconds", Math.max(0L,
                        (System.currentTimeMillis() - checkpoint.updatedAtEpochMilli()) / 1000L)
        ));
        return DurableRestoreResult.RESTORED;
    }

    /** Why a yielded checkpoint cannot resume in the current live session, if anything. */
    public String getDurableResumeBlocker() {
        if (!durableRecoveryLoaded && !isYielded()) return null;
        if (!Proxy.getInstance().isConnected()) return "bot_not_connected";
        if (Proxy.getInstance().hasActivePlayer()) return "proxy_in_use";
        String currentDimension = currentDimensionName();
        if (!journalDimension.isBlank() && !currentDimension.isBlank()
                && !journalDimension.equals(currentDimension)) {
            return "dimension_changed (saved=" + journalDimension + ", current=" + currentDimension + ")";
        }
        return null;
    }

    /** Quiesce shared automation and force the latest semantic checkpoint to disk. */
    public boolean prepareForProcessShutdown(String reason) {
        if (!isActive()) return false;
        if (!isYielded() && !yieldToAutomation(reason == null ? "process_shutdown" : reason)) {
            return false;
        }
        persistDurableCheckpoint(yieldedFromState);
        emit("organize_checkpoint_saved", Map.of(
                "reason", Objects.toString(reason, "process_shutdown"),
                "interrupted_state", Objects.toString(yieldedFromState, "unknown")
        ));
        return journalJobId != null && !journalPersistenceFailed;
    }

    /** Explicitly abandon a saved transaction. This never moves or destroys game items. */
    public boolean discardDurableCheckpoint(String reason) {
        if (isActive() && !isYielded()) return false;
        clearOwnedAutomation();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        taskQueue.clear();
        consolidationQueue.clear();
        currentTask = null;
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        yieldedFromState = null;
        yieldReason = null;
        state = State.IDLE;
        boolean cleared = clearDurableJournal();
        emit("organize_checkpoint_discarded", Map.of(
                "reason", Objects.toString(reason, "manual_discard")
        ));
        return cleared;
    }

    /** Returns true when another Zenith task replaced automation owned by this organizer. */
    public boolean wasAutomationPreempted() {
        if (!isActive() || isYielded()) return false;

        boolean customGoalActive = BARITONE.getCustomGoalProcess().isActive();
        boolean interactionActive = BARITONE.getInteractWithProcess().isActive();
        boolean otherProcessActive = BARITONE.getFollowProcess().isActive()
                || BARITONE.getGetToBlockProcess().isActive()
                || BARITONE.getMineProcess().isActive()
                || BARITONE.getClearAreaProcess().isActive();

        boolean foreignBaritone = otherProcessActive;
        if (ownedBaritoneProcess == OwnedBaritoneProcess.NONE) {
            foreignBaritone |= customGoalActive || interactionActive;
        } else if (ownedBaritoneProcess == OwnedBaritoneProcess.CUSTOM_GOAL) {
            foreignBaritone |= interactionActive;
            foreignBaritone |= customGoalActive
                    && (ownedBaritoneRequest == null || ownedBaritoneRequest.isCompleted());
        } else {
            foreignBaritone |= customGoalActive;
            foreignBaritone |= interactionActive
                    && (ownedBaritoneRequest == null || ownedBaritoneRequest.isCompleted());
        }

        boolean foreignInventory = INVENTORY.hasActiveRequest()
                && (ownedInventoryRequest == null || ownedInventoryRequest.isCompleted());
        return foreignBaritone || foreignInventory;
    }

    /** Preserve the current queue and cargo checkpoint while releasing shared automation. */
    public boolean yieldToAutomation(String reason) {
        if (!isActive() || isYielded()) return false;

        // A mined shulker item despawns on the ground. Finish this short pickup boundary before
        // yielding to another plugin; placed boxes and inventory cargo are safe to checkpoint.
        if ("shared_automation".equals(reason)
                && temporaryShulkerOutstanding
                && !isShulkerAtPosition(shulkerPlacePos)
                && !hasPackedShulkerInInventory()) {
            // The foreign request already owns shared automation. Leave it intact and freeze
            // this pickup timeout until that request releases control; then recover the drop
            // before returning to ordinary organizer work.
            clearOwnedAutomation();
            state = State.SHULKER_RECOVERY_PICKUP;
            shulkerTicks = 0;
            return false;
        }

        yieldedFromState = state;
        yieldReason = reason == null ? "shared_automation" : reason;
        boolean acceptedOpenPending = isAwaitingContainerOpen()
                && !containerDataReceived
                && ownedBaritoneProcess == OwnedBaritoneProcess.INTERACTION
                && ownedBaritoneRequest != null
                && ownedBaritoneRequest.isCompleted()
                && ownedBaritoneRequest.getNow();
        lateOpenQuarantineTicks = acceptedOpenPending ? LATE_OPEN_QUARANTINE_TICKS : 0;
        closeCurrentContainerForYield();
        stopOwnedBaritoneProcess();
        clearOwnedAutomation();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.YIELDED;
        persistDurableCheckpoint(yieldedFromState);
        return true;
    }

    /** Rebuild a live checkpoint after the interrupting task and cooldown both finish. */
    public boolean resumeFromYield() {
        if (!isYielded()) return false;

        String resumeBlocker = getDurableResumeBlocker();
        if (resumeBlocker != null) {
            return false;
        }
        if (keepProtectionNeedsRefresh && !loadAndSnapshotProtectedInventorySlots()) {
            return false;
        }

        State interrupted = yieldedFromState;
        yieldedFromState = null;
        yieldReason = null;
        clearOwnedAutomation();
        saveAndDisableBaritoneBreaking();
        saveAndGuardPlaceBlockSneak();
        containerDataReceived = false;
        openContainerId = -1;
        openWaitTicks = 0;
        actionCooldown = 0;
        trackedWalkTargetKey = Long.MIN_VALUE;
        lateOpenQuarantineTicks = 0;

        resumeInterruptedCheckpoint(interrupted);
        if (state != State.YIELDED) {
            durableRecoveryLoaded = false;
            durableRecoveryError = null;
            persistDurableCheckpoint(state);
        }
        return state != State.YIELDED;
    }

    /** Drop an in-memory checkpoint after proxy control exceeds its grace window. */
    public void abortYielded(String reason) {
        if (!isYielded()) return;

        State interrupted = yieldedFromState;
        int queuedTasks = taskQueue.size() + consolidationQueue.size();
        boolean manualRecovery = temporaryShulkerOutstanding;
        clearOwnedAutomation();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        yieldedFromState = null;
        yieldReason = null;
        lateOpenQuarantineTicks = 0;
        state = State.DONE;
        emit("organize_aborted", Map.ofEntries(
                Map.entry("reason", Objects.toString(reason, "continuance_aborted")),
                Map.entry("interrupted_state", Objects.toString(interrupted, "unknown")),
                Map.entry("completed_tasks", completedTasks),
                Map.entry("total_tasks", totalTasks),
                Map.entry("queued_tasks_discarded", queuedTasks),
                Map.entry("lost_progress", true),
                Map.entry("temporary_shulker_recovery_required", manualRecovery)
        ));
        taskQueue.clear();
        consolidationQueue.clear();
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        currentTask = null;
        clearMixedDecompositionState();
        clearDurableJournal();
    }

    public void tickYieldMaintenance() {
        if (isYielded() && lateOpenQuarantineTicks > 0) lateOpenQuarantineTicks--;
    }

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
        List<Column> columns = excludeImportColumns(detectStorageColumns(regionContainers));

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
                container.isDouble() && !container.inventoryFootprintKnown());

        Set<String> storageClasses = new TreeSet<>();
        Map<String, Long> looseItemsByClass = new LinkedHashMap<>();
        List<Map<String, Integer>> mixedContentsForDemand = new ArrayList<>();
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
                        mixedContentsForDemand.add(classification.contents());
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
                String storageClass = StorageClassPolicy.exact(itemId);
                if (storageClass == null) return;
                storageClasses.add(storageClass);
                looseItemsByClass.merge(storageClass, quantity.longValue(), Long::sum);
            });
        }

        // A mixed/kit shulker is future loose reconciliation work, not exempt capacity. Count
        // every exact contained item now so the lane report reflects the post-decomposition
        // stash instead of hiding its real cost until the boxes have already been opened.
        MixedShulkerPlaybook.aggregateDemand(mixedContentsForDemand).forEach((storageClass, quantity) -> {
            storageClasses.add(storageClass);
            looseItemsByClass.merge(storageClass, quantity, Long::sum);
        });

        Set<Integer> protectedLaneIds = new HashSet<>();
        for (long containerKey : protectedContainers) {
            Column column = posToColumn.get(containerKey);
            if (column != null) protectedLaneIds.add(column.id());
        }

        List<LaneStorageCapacity.Demand> storageDemands = storageClasses.stream()
                .map(storageClass -> LaneStorageCapacity.Demand.calculate(
                        storageClass,
                        looseItemsByClass.getOrDefault(storageClass, 0L),
                        bulkShulkerCountsByClass.getOrDefault(storageClass, List.of())))
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
        if (journalStore.exists()) {
            info("Cannot start a new organization job while a saved checkpoint exists. Resume or discard it first.");
            emit("organize_start_blocked", Map.of("reason", "saved_checkpoint_exists"));
            return false;
        }
        if (temporaryShulkerOutstanding) {
            boolean blockGone = !isShulkerAtPosition(shulkerPlacePos);
            boolean inventoryRecovered = countShulkerBoxesInInventory()
                    >= shulkerInventoryCountBeforePlacement;
            if (blockGone && inventoryRecovered) {
                resetTemporaryShulkerState();
            } else {
                info("Cannot start: a temporary packing shulker still requires recovery at "
                        + posString(shulkerPlacePos) + ".");
                emit("organize_start_blocked", Map.of(
                        "reason", "temporary_shulker_recovery_required",
                        "shulker_position", posString(shulkerPlacePos)
                ));
                return false;
            }
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
        protectedInventorySlots.clear();
        keepProtectionNeedsRefresh = true;
        if (!loadAndSnapshotProtectedInventorySlots()) return false;

        var playerCache = CACHE.getPlayerCache();
        reconciliationStation = new int[]{
                (int) Math.floor(playerCache.getX()),
                (int) Math.floor(playerCache.getY()),
                (int) Math.floor(playerCache.getZ())
        };
        reconciliationWorksite = findShulkerPlaceSpot(reconciliationStation);

        BARITONE.stop();
        clearOwnedAutomation();
        saveAndDisableBaritoneBreaking();
        saveAndGuardPlaceBlockSneak();
        taskQueue.clear();
        consolidationQueue.clear();
        overflowItems.clear();
        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        managedSourceContainerKeys.clear();
        stagingImportDestinations.clear();
        packStoreTriedDestinations.clear();
        stagingStorageClassesPlanned.clear();
        stagedStorageClasses.clear();
        stagedShulkers = 0;
        decomposedMixedShulkers = 0;
        permanentLaneGaps = 0;
        packDestinationOpenFailures = 0;
        packStoreMatchingShulkersBefore = 0;
        packStoreVerificationTicks = 0;
        stagingReason = null;
        currentTask = null;
        walkTarget = null;
        trackedWalkTargetKey = Long.MIN_VALUE;
        walkingTicks = 0;
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        consolidationSourcesInBatch = 0;
        completedTasks = 0;
        totalTasks = 0;
        nextProgressMilestone = ProgressMilestones.FIRST;
        resetJournalMemory();
        containerDataReceived = false;
        openContainerId = -1;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        yieldedFromState = null;
        yieldReason = null;
        lateOpenQuarantineTicks = 0;

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
        if (isYielded()) {
            abortYielded("manual_stop_while_yielded");
            return;
        }
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
        clearOwnedAutomation();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.IDLE;
        taskQueue.clear();
        consolidationQueue.clear();
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        currentTask = null;
        clearMixedDecompositionState();
        protectedInventorySlots.clear();
        keepProtectionNeedsRefresh = false;
        overflowItems.clear();
        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        managedSourceContainerKeys.clear();
        stagingImportDestinations.clear();
        packStoreTriedDestinations.clear();
        stagingStorageClassesPlanned.clear();
        stagedStorageClasses.clear();
        stagedShulkers = 0;
        decomposedMixedShulkers = 0;
        permanentLaneGaps = 0;
        packDestinationOpenFailures = 0;
        packStoreMatchingShulkersBefore = 0;
        packStoreVerificationTicks = 0;
        stagingReason = null;
        columnAssignment.clear();
        yieldedFromState = null;
        yieldReason = null;
        lateOpenQuarantineTicks = 0;
        clearDurableJournal();
        emit("organize_stopped", Map.of("reason", "manual_stop"));
        info("Organizer stopped.");
    }

    // Receives container data from module packet handler.
    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        if (state == State.YIELDED) {
            if (packet.getContainerId() > 0 && lateOpenQuarantineTicks > 0) {
                closeUnexpectedContainer(session, packet.getContainerId());
                lateOpenQuarantineTicks = 0;
            }
            return;
        }
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
                 MIXED_STAGE_OPEN,
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
            case SHULKER_EMPTYING    -> tickShulkerEmptying();
            case MIXED_SOURCE_CLOSING -> tickMixedSourceClosing();
            case SHULKER_FILLING     -> tickShulkerFilling();
            case SHULKER_CLOSING     -> tickShulkerClosing();
            case SHULKER_BREAKING    -> tickShulkerBreaking();
            case SHULKER_PICKUP      -> tickShulkerPickup();
            case SHULKER_RESUME_WALK -> tickWalking();
            case SHULKER_RECOVERY_BREAKING -> tickShulkerRecoveryBreaking();
            case SHULKER_RECOVERY_PICKUP -> tickShulkerRecoveryPickup();
            case SHULKER_FETCH_WALK  -> tickWalking();
            case SHULKER_FETCH_OPEN  -> tickShulkerFetchOpen();
            case SHULKER_FETCH_TAKE  -> tickShulkerFetchTake();
            case SHULKER_FETCH_CLOSING -> tickShulkerFetchClosing();
            case SHULKER_STORE_WALK  -> tickWalking();
            case SHULKER_STORE_OPEN  -> tickShulkerStoreOpen();
            case SHULKER_STORE_DEPOSIT -> tickShulkerStoreDeposit();
            case MIXED_STAGE_WALK    -> tickWalking();
            case MIXED_STAGE_OPEN    -> tickMixedStageOpen();
            case MIXED_STAGE_DEPOSIT -> tickMixedStageDeposit();
            case MIXED_STAGE_CLOSING -> tickMixedStageClosing();
            case MIXED_RETURN_WALK   -> tickWalking();
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
            case YIELDED             -> { }
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

        // Step 1: Detect every supported permanent-storage family in the same region. Hopper
        // chains are joined by their shared inventories, while direct-access chest banks are
        // split into contiguous vertical stacks. A stash may contain both without one family
        // hiding the other. Imports remain staging-only and are removed afterward.
        List<Column> columns = excludeImportColumns(detectStorageColumns(regionContainers));

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
                container.isDouble() && !container.inventoryFootprintKnown())) {
            info("Organization blocked: legacy double-chest rows do not identify their full physical footprint. Run a fresh scan.");
            emit("organize_planning_blocked", Map.of("reason", "double_chest_footprint_requires_fresh_scan"));
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            return;
        }
        List<ContainerEntry> importContainers = planningContainers.stream()
                .filter(index::isImportChest)
                .toList();
        List<ImportStagingPolicy.Candidate> importStagingCandidates = importContainers.stream()
                .map(ImportStagingPolicy::from)
                .toList();
        stagingImportDestinations.clear();
        importStagingCandidates.stream()
                .map(ImportStagingPolicy.Candidate::position)
                .forEach(stagingImportDestinations::add);
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
        record MixedShulkerLoc(int[] pos, int slot, String shulkerType,
                               String fingerprint, Map<String, Integer> contents) {}
        Map<String, List<ShulkerLoc>> shulkersByContent = new LinkedHashMap<>();
        List<MixedShulkerLoc> mixedShulkerLocations = new ArrayList<>();
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
                        mixedShulkerLocations.add(new MixedShulkerLoc(
                                pos, sd.slot(), sd.color(), classification.fingerprint(),
                                classification.contents()));
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
        Map<String, Long> mixedLooseByClass = MixedShulkerPlaybook.aggregateDemand(
                mixedShulkerLocations.stream().map(MixedShulkerLoc::contents).toList());
        for (MixedShulkerLoc mixed : mixedShulkerLocations) {
            mixed.contents().forEach((rawItemId, quantity) -> {
                String storageClass = StorageClassPolicy.exact(rawItemId);
                if (storageClass != null && quantity != null && quantity > 0) {
                    assignmentLocations.computeIfAbsent(storageClass, ignored -> new ArrayList<>())
                            .add(new ItemLocation(mixed.pos(), quantity));
                }
            });
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
            looseItems = Math.min(Long.MAX_VALUE,
                    looseItems + mixedLooseByClass.getOrDefault(storageClass, 0L));
            List<Integer> existingCounts = shulkersByContent.getOrDefault(storageClass, List.of()).stream()
                    .map(ShulkerLoc::contentWeight)
                    .toList();
            storageDemands.add(LaneStorageCapacity.Demand.calculate(
                    storageClass, looseItems, existingCounts));
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
        DedicatedLaneCapacity capacity = DedicatedLaneCapacity.assess(
                columns.size(), unavailableColumnIds.size(), storageDemands.size());
        boolean countShortfall = !capacity.feasible();
        String capacityReason = countShortfall
                ? "insufficient_dedicated_lanes"
                : "insufficient_lane_storage";
        String unassigned = storageCapacity.unassigned().stream()
                .map(demand -> demand.storageClass() + ":" + demand.requiredShulkerSlots())
                .collect(Collectors.joining(","));
        if ((!storageCapacity.feasible() || !mixedShulkerLocations.isEmpty())
                && importStagingCandidates.isEmpty()) {
            String blockedReason = !mixedShulkerLocations.isEmpty()
                    ? "mixed_shulkers_require_import_staging"
                    : capacityReason;
            info("Organization blocked: " + storageCapacity.unassigned().size()
                    + " bulk class(es) cannot fit an assignable lane and/or mixed shulkers need a registered import chest for safe staging.");
            emit("organize_planning_blocked", Map.ofEntries(
                    Map.entry("reason", blockedReason),
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
        if (!storageCapacity.feasible()) {
            permanentLaneGaps = storageCapacity.unassigned().size();
            stagingReason = capacityReason;
            info("Permanent storage is short for " + permanentLaneGaps
                    + " bulk class(es). Loose items will still be reconciled, and their new bulk shulkers will wait in registered import chests.");
            emit("organize_import_staging_planned", Map.ofEntries(
                    Map.entry("reason", capacityReason),
                    Map.entry("import_inventories", importStagingCandidates.size()),
                    Map.entry("permanent_lane_gaps", permanentLaneGaps),
                    Map.entry("lane_shortfall", capacity.laneShortfall()),
                    Map.entry("unassigned_required_shulker_slots", storageCapacity.unassignedRequiredShulkerSlots()),
                    Map.entry("unassigned_storage_classes", unassigned)
            ));
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

        int stagedCondenseTypes = 0;
        for (LaneStorageCapacity.Demand demand : storageCapacity.unassigned()) {
            String itemId = demand.storageClass();
            List<ItemLocation> locations = itemLocations.getOrDefault(itemId, List.of());
            int totalLoose = locations.stream().mapToInt(ItemLocation::quantity).sum();
            if (locations.isEmpty() || totalLoose < config.condenseMinItems) continue;

            Map<Long, Integer> looseByPosition = new HashMap<>();
            for (ItemLocation location : locations) {
                int[] pos = location.pos();
                looseByPosition.merge(posKey(pos[0], pos[1], pos[2]), location.quantity(), Integer::sum);
            }
            Map<Long, Integer> looseByImport = ImportStagingPolicy.sourceQuantities(
                    importContainers, looseByPosition);
            int[] stagingDestination = ImportStagingPolicy.choose(
                            importStagingCandidates, looseByImport)
                    .orElseThrow()
                    .position();
            for (ItemLocation location : locations) {
                consolidationQueue.add(new MoveTask(location.pos(), stagingDestination, itemId));
            }
            stagingStorageClassesPlanned.add(itemId);
            stagedCondenseTypes++;
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
        // filled shulkers already in the bot inventory, decompose mixed/kit boxes one at a
        // time through import staging, and only then pack ordinary loose stash items.
        // Mid-run inventory-full recovery still promotes its emergency deposit tasks.
        queueInventoryDepositTasks(false);

        int mixedDecompositionMoves = 0;
        if (!mixedShulkerLocations.isEmpty()) {
            for (MixedShulkerLoc mixed : mixedShulkerLocations) {
                int minimumSlots = MixedShulkerPlaybook.minimumStagingSlots(mixed.contents());
                List<ImportStagingPolicy.Candidate> singleChestFits = importStagingCandidates.stream()
                        .filter(candidate -> candidate.estimatedFreeSlots() >= minimumSlots)
                        .toList();
                ImportStagingPolicy.Candidate staging = ImportStagingPolicy.choose(
                                singleChestFits.isEmpty() ? importStagingCandidates : singleChestFits,
                                Map.of())
                        .orElseThrow();
                taskQueue.addLast(MoveTask.mixed(
                        mixed.pos(), staging.position(), shulkerItemId(mixed.shulkerType()),
                        mixed.fingerprint(), mixed.contents()));
                mixedDecompositionMoves++;
            }
        }

        totalTasks = taskQueue.size() + consolidationQueue.size();
        completedTasks = 0;
        nextProgressMilestone = ProgressMilestones.FIRST;

        if (taskQueue.isEmpty() && consolidationQueue.isEmpty()) {
            if (permanentLaneGaps > 0) {
                info("No reconciliation moves are needed, but " + permanentLaneGaps
                        + " bulk class(es) still need a suitable permanent lane.");
            } else {
                info("Stash is already organized! (" + regionContainers.size() + " containers in "
                        + columns.size() + " columns, " + itemLocations.size() + " item types)");
            }
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            clearDurableJournal();
            emit("organize_completed", organizerCompletionPayload());
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Planned ").append(totalTasks).append(" moves across ")
                .append(columns.size()).append(" columns (")
                .append(columnAssignment.size()).append(" types");
        if (condenseTypes > 0) summary.append(", ").append(condenseTypes).append(" to condense");
        if (stagedCondenseTypes > 0) {
            summary.append(", ").append(stagedCondenseTypes).append(" to reconcile into import staging");
        }
        if (shulkerMoves > 0) summary.append(", ").append(shulkerMoves).append(" shulker sorts");
        if (mixedDecompositionMoves > 0) {
            summary.append(", ").append(mixedDecompositionMoves).append(" mixed shulkers to separate");
        }
        summary.append(").");
        info(summary.toString());
        emit("organize_planned", Map.ofEntries(
                Map.entry("planned_moves", totalTasks),
                Map.entry("columns", columns.size()),
                Map.entry("item_types", itemLocations.size()),
                Map.entry("condense_types", condenseTypes),
                Map.entry("staged_condense_types", stagedCondenseTypes),
                Map.entry("permanent_lane_gaps", permanentLaneGaps),
                Map.entry("protected_lanes", unavailableColumnIds.size()),
                Map.entry("bulk_shulkers", bulkShulkers),
                Map.entry("empty_shulkers", emptyShulkers),
                Map.entry("mixed_shulkers", mixedShulkers),
                Map.entry("unclassified_shulkers", unclassifiedShulkers),
                Map.entry("mixed_decomposition_moves", mixedDecompositionMoves),
                Map.entry("shulker_moves", shulkerMoves)
        ));

        if (!consolidationQueue.isEmpty()) {
            info(consolidationQueue.size() + " condensing tasks (will pack loose items into shulker boxes).");
        }

        if (!beginDurableJournal()) {
            restoreBaritoneBreaking();
            restorePlaceBlockSneak();
            state = State.DONE;
            return;
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

        int[] currentPos = {
            (int) Math.floor(CACHE.getPlayerCache().getX()),
            (int) Math.floor(CACHE.getPlayerCache().getY()),
            (int) Math.floor(CACHE.getPlayerCache().getZ())
        };

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
            if (isProtectedInventorySlot(slot)) continue;

            String itemId = itemIdFromStack(stack);

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
                // No permanent lane exists for this class. Keep it in explicit import staging
                // so inventory recovery cannot deadlock the organizer or invent a lane.
                int[] overflow = findOverflowChest();
                if (overflow == null) {
                    skippedNoColumn++;
                    continue;
                }
                stagingStorageClassesPlanned.add(columnKey);
                permanentLaneGaps = Math.max(permanentLaneGaps, stagingStorageClassesPlanned.size());
                if (stagingReason == null) stagingReason = "no_permanent_lane_for_inventory_cargo";
                MoveTask task = new MoveTask(currentPos, overflow, itemId, contentFilter, true);
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

    private record HopperEdgeKey(long input, long output) {}
    private record HopperEdge(long input, long output,
                              ContainerEntry inputEntry, ContainerEntry outputEntry) {}
    private record VerticalStorageKey(int x, int z) {}

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

        Map<HopperEdgeKey, HopperEdge> edges = new LinkedHashMap<>();
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

            long inputKey = storageIdentityKey(input);
            long outputKey = storageIdentityKey(output);
            if (inputKey == outputKey) continue;
            HopperEdge edge = new HopperEdge(inputKey, outputKey, input, output);
            edges.putIfAbsent(new HopperEdgeKey(inputKey, outputKey), edge);
        }

        Map<Long, ContainerEntry> inventoryByKey = new LinkedHashMap<>();
        Map<Long, Set<Long>> outgoing = new LinkedHashMap<>();
        Map<Long, Set<Long>> incoming = new LinkedHashMap<>();
        Map<Long, Set<Long>> adjacent = new LinkedHashMap<>();
        for (HopperEdge edge : edges.values()) {
            inventoryByKey.merge(edge.input(), edge.inputEntry(), StashOrganizer::freshest);
            inventoryByKey.merge(edge.output(), edge.outputEntry(), StashOrganizer::freshest);
            outgoing.computeIfAbsent(edge.input(), ignored -> new LinkedHashSet<>()).add(edge.output());
            incoming.computeIfAbsent(edge.output(), ignored -> new LinkedHashSet<>()).add(edge.input());
            adjacent.computeIfAbsent(edge.input(), ignored -> new LinkedHashSet<>()).add(edge.output());
            adjacent.computeIfAbsent(edge.output(), ignored -> new LinkedHashSet<>()).add(edge.input());
        }

        List<Long> orderedSeeds = new ArrayList<>(inventoryByKey.keySet());
        orderedSeeds.sort((left, right) -> compareStorageEntries(
                inventoryByKey.get(left), inventoryByKey.get(right)));

        List<Column> columns = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (long seed : orderedSeeds) {
            if (!visited.add(seed)) continue;

            Set<Long> component = new LinkedHashSet<>();
            Deque<Long> frontier = new ArrayDeque<>();
            frontier.add(seed);
            while (!frontier.isEmpty()) {
                long current = frontier.removeFirst();
                component.add(current);
                for (long neighbor : adjacent.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) frontier.addLast(neighbor);
                }
            }

            // A dedicated lane must be one unambiguous FIFO chain. Shared or branching
            // inventories are a hopper network, not a safe one-item lane.
            boolean branching = component.stream().anyMatch(key ->
                    outgoing.getOrDefault(key, Set.of()).size() > 1
                            || incoming.getOrDefault(key, Set.of()).size() > 1);
            if (branching) continue;

            List<Long> roots = component.stream()
                    .filter(key -> incoming.getOrDefault(key, Set.of()).isEmpty())
                    .sorted((left, right) -> compareStorageEntries(
                            inventoryByKey.get(left), inventoryByKey.get(right)))
                    .toList();
            if (roots.size() != 1) continue;

            List<int[]> ordered = new ArrayList<>();
            Set<Long> traversed = new HashSet<>();
            long current = roots.get(0);
            while (traversed.add(current)) {
                ContainerEntry entry = inventoryByKey.get(current);
                ordered.add(new int[]{entry.x(), entry.y(), entry.z()});
                Set<Long> next = outgoing.getOrDefault(current, Set.of());
                if (next.isEmpty()) break;
                current = next.iterator().next();
            }
            if (ordered.size() == component.size() && ordered.size() >= 2) {
                columns.add(new Column(columns.size(), ordered));
            }
        }

        return sortAndReindex(columns);
    }

    /** Detect hopper lanes and direct-access stacked banks within one mixed stash. */
    static List<Column> detectStorageColumns(Collection<ContainerEntry> containers) {
        List<Column> columns = new ArrayList<>(detectStaircaseColumns(containers));

        Map<Long, ContainerEntry> permanentByPosition = new LinkedHashMap<>();
        for (ContainerEntry entry : containers) {
            if (!isPermanentStorage(entry)) continue;
            permanentByPosition.merge(entry.posKey(), entry, StashOrganizer::freshest);
        }

        Set<Long> claimedInventories = new HashSet<>();
        for (Column column : columns) {
            for (int[] position : column.chests()) {
                ContainerEntry entry = permanentByPosition.get(
                        posKey(position[0], position[1], position[2]));
                if (entry != null) claimedInventories.add(storageIdentityKey(entry));
            }
        }
        columns.addAll(detectStackedColumns(containers, claimedInventories));

        if (columns.isEmpty()) {
            // Compatibility for old direct-access layouts which have neither a hopper chain
            // nor a contiguous vertical bank.
            Set<int[]> positions = permanentByPosition.values().stream()
                    .map(entry -> new int[]{entry.x(), entry.y(), entry.z()})
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            columns.addAll(detectColumns(positions));
        }

        return sortAndReindex(columns);
    }

    private static List<Column> detectStackedColumns(
            Collection<ContainerEntry> containers,
            Set<Long> claimedInventories) {
        Map<Long, ContainerEntry> freshestInventories = new LinkedHashMap<>();
        for (ContainerEntry entry : containers) {
            if (!isPermanentStorage(entry)) continue;
            freshestInventories.merge(storageIdentityKey(entry), entry, StashOrganizer::freshest);
        }

        Map<VerticalStorageKey, List<ContainerEntry>> byFootprint = new LinkedHashMap<>();
        for (ContainerEntry entry : freshestInventories.values()) {
            if (claimedInventories.contains(storageIdentityKey(entry))) continue;
            int footprintX = entry.inventoryIdentityKnown() ? entry.inventoryX() : entry.x();
            int footprintZ = entry.inventoryIdentityKnown() ? entry.inventoryZ() : entry.z();
            byFootprint.computeIfAbsent(
                    new VerticalStorageKey(footprintX, footprintZ), ignored -> new ArrayList<>())
                    .add(entry);
        }

        List<Column> columns = new ArrayList<>();
        List<Map.Entry<VerticalStorageKey, List<ContainerEntry>>> footprints =
                new ArrayList<>(byFootprint.entrySet());
        footprints.sort(Comparator
                .comparingInt((Map.Entry<VerticalStorageKey, List<ContainerEntry>> entry) ->
                        entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z()));

        for (var footprint : footprints) {
            List<ContainerEntry> entries = footprint.getValue();
            entries.sort(Comparator.comparingInt(ContainerEntry::y).reversed());
            List<ContainerEntry> run = new ArrayList<>();
            for (ContainerEntry entry : entries) {
                if (!run.isEmpty() && run.get(run.size() - 1).y() - entry.y() != 1) {
                    addStackedRun(columns, run);
                    run = new ArrayList<>();
                }
                run.add(entry);
            }
            addStackedRun(columns, run);
        }

        return columns;
    }

    private static void addStackedRun(List<Column> columns, List<ContainerEntry> run) {
        if (run.size() < 2) return;
        List<int[]> positions = run.stream()
                .map(entry -> new int[]{entry.x(), entry.y(), entry.z()})
                .toList();
        columns.add(new Column(columns.size(), positions));
    }

    private static ContainerEntry freshest(ContainerEntry current, ContainerEntry candidate) {
        return candidate.timestamp() > current.timestamp() ? candidate : current;
    }

    private static long storageIdentityKey(ContainerEntry entry) {
        return entry.isDouble() && entry.inventoryIdentityKnown()
                ? entry.inventoryKey()
                : entry.posKey();
    }

    private static int compareStorageEntries(ContainerEntry left, ContainerEntry right) {
        int byX = Integer.compare(left.x(), right.x());
        if (byX != 0) return byX;
        int byZ = Integer.compare(left.z(), right.z());
        if (byZ != 0) return byZ;
        return Integer.compare(right.y(), left.y());
    }

    private static List<Column> sortAndReindex(List<Column> columns) {
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

    /** Imports never become permanent lanes, even if a hopper happens to touch one. */
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
            } else if (state == State.SHULKER_RESUME_WALK) {
                abortWithCargo("reconciliation_resume_unreachable_with_cargo",
                        "Could not return to the paused reconciliation worksite; cargo is preserved in inventory.");
            } else if (state == State.MIXED_STAGE_WALK) {
                mixedUnavailableStagingDestinations.add(targetKey);
                if (!switchMixedStagingDestination()) {
                    recoverMixedShulkerAndStop("mixed_staging_unreachable",
                            "No registered import chest was reachable; recovering the placed mixed shulker before stopping.");
                }
            } else if (state == State.MIXED_RETURN_WALK) {
                recoverMixedShulkerAndStop("mixed_station_return_unreachable",
                        "Could not return to the mixed-shulker worksite; recovering the placed box before stopping.");
            } else if (state == State.SHULKER_STORE_WALK) {
                retryOrSwitchPackedShulkerDestination("packed_shulker_destination_walk_timeout", false);
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
        if (state == State.SHULKER_STATION_WALK || state == State.SHULKER_RESUME_WALK
                || state == State.MIXED_RETURN_WALK) {
            ownCustomGoal(BARITONE.pathTo(
                    new GoalBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2]))));
        } else {
            ownCustomGoal(BARITONE.pathTo(
                    new GoalGetToBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2]))));
        }
    }

    private boolean isAtWalkTargetAccessPosition() {
        if (walkTarget == null) return false;
        var playerCache = CACHE.getPlayerCache();
        if (state == State.SHULKER_STATION_WALK || state == State.SHULKER_RESUME_WALK
                || state == State.MIXED_RETURN_WALK) {
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
            case MIXED_STAGE_WALK     -> state = State.MIXED_STAGE_OPEN;
            case MIXED_RETURN_WALK    -> {
                if (mixedBoxDrained && mixedCargoSlots.isEmpty()) {
                    if (countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement) {
                        finishMixedShulkerDecomposition();
                    } else {
                        state = State.SHULKER_CLOSING;
                        shulkerTicks = 3;
                    }
                } else if (!mixedCargoSlots.isEmpty()) {
                    startMixedStageWalk(packDestination);
                } else if (!isShulkerAtPosition(shulkerPlacePos)) {
                    state = State.SHULKER_SELECTING;
                } else {
                    reopenMixedShulkerAtStation();
                }
            }
            case SHULKER_RESUME_WALK  -> resumeTemporaryShulkerAtStation();
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
            ownInteraction(BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]));
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
                        boolean matchesMixed = currentTask.mixedDecomposition()
                                && classification.kind() == ShulkerClassification.Kind.MIXED
                                && currentTask.shulkerContentFilter().equals(classification.fingerprint());
                        boolean matchesBulk = !currentTask.mixedDecomposition()
                                && classification.kind() == ShulkerClassification.Kind.BULK
                                && ItemIdentifier.contentItemIdsMatch(
                                        currentTask.shulkerContentFilter(), classification.storageKey());
                        if (!matchesMixed && !matchesBulk) {
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
            } else if (currentTask != null && currentTask.mixedDecomposition()) {
                startMixedShulkerDecomposition();
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
            if (isProtectedInventorySlot(playerSlot)) {
                playerSlot++;
                continue;
            }
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
            emitProgressMilestoneIfCrossed();
            advanceToNextTask();
        }
    }

    private void emitProgressMilestoneIfCrossed() {
        ProgressMilestones.Crossing crossing = ProgressMilestones.afterProgress(
                completedTasks, totalTasks, nextProgressMilestone);
        nextProgressMilestone = crossing.nextMilestonePercent();
        if (!crossing.crossed()) return;

        int actualPercent = totalTasks <= 0 ? 0
                : (int) Math.min(100L, ((long) completedTasks * 100L) / totalTasks);
        info("Progress: " + completedTasks + "/" + totalTasks
                + " (" + crossing.milestonePercent() + "% milestone)");
        emit("organize_progress", Map.of(
                "milestone_percent", crossing.milestonePercent(),
                "progress_percent", actualPercent
        ));
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
            ownInteraction(BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]));
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
            Container open = getLiveOpenContainer();
            int chestSlots = open == null ? 0 : getOpenContainerSlotCount(open);
            if (open == null || !containerHasEmptySlot(open, chestSlots)) {
                retryOrSwitchPackedShulkerDestination("packed_shulker_destination_full", true);
                return;
            }
            state = State.SHULKER_STORE_DEPOSIT;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            movedThisVisit = 0;
            packStoreVerificationTicks = 0;
            // Container(0) is stale while a non-zero window is open. Use the appended player
            // section of this live window as the transfer baseline.
            packStoreMatchingShulkersBefore = countCompatibleBulkShulkersInOpenPlayerInventory(
                    open, chestSlots, packItemId);
            return;
        }

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening destination for shulker deposit.");
            BARITONE.stop();
            retryOrSwitchPackedShulkerDestination("shulker_store_open_timeout", false);
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            ownInteraction(BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]));
        }
    }

    private void tickShulkerStoreDeposit() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            retryOrSwitchPackedShulkerDestination("packed_shulker_destination_lost", false);
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);

        // Deposit only the shulker produced for this exact packed item variant.
        while (actionSlotIndex < 45) {
            if (isProtectedInventorySlot(actionSlotIndex)) {
                actionSlotIndex++;
                continue;
            }
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
                        packStoreVerificationTicks = 0;
                    }
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }

        int matchingShulkersAfter = countCompatibleBulkShulkersInOpenPlayerInventory(
                open, chestSlots, packItemId);
        boolean depositConfirmed = packedShulkerTransferConfirmed(
                packStoreMatchingShulkersBefore, matchingShulkersAfter, movedThisVisit);
        if (!depositConfirmed && movedThisVisit > 0
                && packStoreVerificationTicks++ < packedShulkerVerificationTimeoutTicks()) {
            // InventoryManager acceptance only means the click packet was sent. Give the live
            // window time to receive the authoritative server slot update before retrying.
            return;
        }
        closeCurrentContainer();
        if (depositConfirmed) {
            completePackedShulkerStore();
        } else {
            retryOrSwitchPackedShulkerDestination(
                    movedThisVisit > 0
                            ? "packed_shulker_deposit_not_confirmed"
                            : "packed_shulker_not_found_in_inventory",
                    false);
            return;
        }
    }

    private void completePackedShulkerStore() {
        if (consolidationMode) {
            completedTasks += Math.max(0, consolidationSourcesInBatch);
            consolidationSourcesInBatch = 0;
        } else {
            completedTasks++;
        }
        emitProgressMilestoneIfCrossed();
        if (isImportStagingPack()) {
            stagedShulkers++;
            stagedStorageClasses.add(packItemId);
        }
        if (consolidationMode) {
            if (countItemInInventory(packItemId) > 0) {
                // A gathered batch may span more than one shulker's 27 slots. Finish the
                // remaining loose cargo before visiting another source or declaring success.
                startShulkerPacking(packItemId, packDestination);
                return;
            }
            advanceConsolidation();
        } else {
            advanceToNextTask();
        }
    }

    // SHULKER PACKING CYCLE
    private void startShulkerPacking(String itemId, int[] destination) {
        clearMixedDecompositionState();
        this.packItemId = itemId;
        this.packDestination = destination;
        this.shulkerFetchTriedSources.clear();
        this.packStoreTriedDestinations.clear();
        this.packDestinationOpenFailures = 0;
        this.packStoreMatchingShulkersBefore = 0;
        this.packStoreVerificationTicks = 0;
        this.fetchedPackingShulker = false;
        resetTemporaryShulkerState();
        info("Returning to the reconciliation station to pack: " + itemId);
        walkTarget = reconciliationStation;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.SHULKER_STATION_WALK;
        shulkerTicks = 0;
        persistDurableCheckpoint(state);
    }

    private void startMixedShulkerDecomposition() {
        if (currentTask == null || !currentTask.mixedDecomposition()) {
            abortWithCargo("mixed_shulker_task_missing",
                    "Mixed-shulker cargo is present but its decomposition task is missing.");
            return;
        }
        resetTemporaryShulkerState();
        mixedDecompositionMode = true;
        mixedBoxDrained = false;
        mixedCargoSlots.clear();
        mixedStagingUsedDestinations.clear();
        mixedUnavailableStagingDestinations.clear();
        packItemId = null;
        packDestination = copyPos(currentTask.destination());
        info("Returning to the reconciliation station to separate a mixed shulker.");
        walkTarget = reconciliationStation;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.SHULKER_STATION_WALK;
        shulkerTicks = 0;
        persistDurableCheckpoint(state);
    }

    private void tickShulkerSelecting() {
        shulkerTicks++;
        
        int shulkerSlot = mixedDecompositionMode
                ? findCurrentMixedShulkerInInventory()
                : findPackingShulkerInInventory();
        if (shulkerSlot < 0) {
            if (mixedDecompositionMode) {
                abortWithCargo("mixed_shulker_cargo_missing",
                        "The selected mixed shulker is no longer in inventory; the task was stopped safely.");
                return;
            }
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
        compatibleShulkerCountBeforePlacement = mixedDecompositionMode
                ? 0
                : countCompatibleBulkShulkersInInventory(packItemId);
        if (!moveShulkerToHotbar(shulkerSlot)) {
            abortWithCargo("safe_hotbar_slot_unavailable",
                    "No unprotected hotbar slot is available for the reconciliation shulker.");
            return;
        }

        state = State.SHULKER_PLACING;
        shulkerTicks = 0;
        shulkerPlaceRetries = 0;
        shulkerPlaceFuture = null;
        persistDurableCheckpoint(state);
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
            ownInteraction(shulkerPlaceFuture);
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
            state = mixedDecompositionMode ? State.SHULKER_EMPTYING : State.SHULKER_FILLING;
            actionSlotIndex = mixedDecompositionMode ? 0 : 9;
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
            ownInteraction(BARITONE.rightClickBlock(
                    shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2]));
        }
    }

    /** Moves mixed-box stacks only into known-empty player slots, preserving keep-list cargo. */
    private void tickShulkerEmptying() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            beginTemporaryShulkerRecovery("mixed_shulker_container_lost");
            return;
        }

        int shulkerSlots = getOpenContainerSlotCount(open);
        if (mixedPendingCargoSlot >= 0) {
            int pendingWindowSlot = rawPlayerSlotToWindowSlot(shulkerSlots, mixedPendingCargoSlot);
            ItemStack transferred = open.getItemStack(pendingWindowSlot);
            ItemStack source = mixedPendingSourceSlot >= 0
                    ? open.getItemStack(mixedPendingSourceSlot)
                    : null;
            if (transferred != null && transferred.getAmount() > 0) {
                mixedCargoSlots.add(mixedPendingCargoSlot);
                actionSlotIndex = Math.max(actionSlotIndex, mixedPendingSourceSlot + 1);
            } else if (source == null || source.getAmount() <= 0) {
                recoverMixedShulkerAndStop("mixed_stack_transfer_unverified",
                        "A mixed stack transfer could not be verified; recovering the placed box before stopping.");
                return;
            }
            mixedPendingSourceSlot = -1;
            mixedPendingCargoSlot = -1;
            persistDurableCheckpoint(State.SHULKER_EMPTYING);
        }
        while (actionSlotIndex < shulkerSlots) {
            ItemStack stack = open.getItemStack(actionSlotIndex);
            if (stack == null || stack.getAmount() <= 0) {
                actionSlotIndex++;
                continue;
            }

            int emptyRawSlot = findEmptyPlayerSlotInOpenContainer(open, shulkerSlots);
            if (emptyRawSlot < 0) {
                if (mixedCargoSlots.isEmpty()) {
                    recoverMixedShulkerAndStop("mixed_shulker_no_cargo_room",
                            "No empty cargo slot is available; recovering the placed mixed shulker before stopping.");
                    return;
                }
                beginMixedSourceClose(false);
                return;
            }

            int destinationSlot = rawPlayerSlotToWindowSlot(shulkerSlots, emptyRawSlot);
            if (moveStackToEmptySlot(actionSlotIndex, destinationSlot)) {
                mixedPendingSourceSlot = actionSlotIndex;
                mixedPendingCargoSlot = emptyRawSlot;
                persistDurableCheckpoint(State.SHULKER_EMPTYING);
            }
            actionCooldown = config.organizerClickCooldownTicks;
            return;
        }

        mixedBoxDrained = true;
        beginMixedSourceClose(true);
    }

    private int findEmptyPlayerSlotInOpenContainer(Container open, int containerSlots) {
        for (int rawSlot = 9; rawSlot < 45; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int windowSlot = rawPlayerSlotToWindowSlot(containerSlots, rawSlot);
            ItemStack stack = open.getItemStack(windowSlot);
            if ((stack == null || stack.getAmount() <= 0) && !mixedCargoSlots.contains(rawSlot)) {
                return rawSlot;
            }
        }
        return -1;
    }

    private boolean moveStackToEmptySlot(int sourceSlot, int destinationSlot) {
        if (openContainerId < 0) return false;
        try {
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(6000)
                    .actions(
                            new ClickItem(openContainerId, sourceSlot, ClickItemAction.LEFT_CLICK),
                            new ClickItem(openContainerId, destinationSlot, ClickItemAction.LEFT_CLICK))
                    .build());
            ownInventory(future);
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            return false;
        }
    }

    private void beginMixedSourceClose(boolean drained) {
        mixedBoxDrained = drained;
        state = State.MIXED_SOURCE_CLOSING;
        actionCooldown = 0;
    }

    private void tickMixedSourceClosing() {
        actionCooldown++;
        if (actionCooldown == 3) {
            closeCurrentContainer();
            return;
        }
        if (actionCooldown < 6) return;
        actionCooldown = 0;
        if (!mixedCargoSlots.isEmpty()) {
            startMixedStageWalk(packDestination);
        } else if (mixedBoxDrained) {
            state = State.SHULKER_CLOSING;
            shulkerTicks = 3;
        } else {
            reopenMixedShulkerAtStation();
        }
    }

    private void startMixedStageWalk(int[] destination) {
        if (destination == null) {
            recoverMixedShulkerAndStop("mixed_staging_destination_missing",
                    "No import staging destination is available; recovering the placed mixed shulker before stopping.");
            return;
        }
        packDestination = copyPos(destination);
        walkTarget = copyPos(destination);
        trackedWalkTargetKey = Long.MIN_VALUE;
        openWaitTicks = 0;
        containerDataReceived = false;
        state = State.MIXED_STAGE_WALK;
        persistDurableCheckpoint(state);
    }

    private void tickMixedStageOpen() {
        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;
        if (containerDataReceived) {
            BARITONE.stop();
            actionCooldown = 0;
            state = State.MIXED_STAGE_DEPOSIT;
            return;
        }
        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            BARITONE.stop();
            mixedUnavailableStagingDestinations.add(posKey(
                    packDestination[0], packDestination[1], packDestination[2]));
            if (!switchMixedStagingDestination()) {
                recoverMixedShulkerAndStop("mixed_staging_open_timeout",
                        "No registered import chest could be opened; recovering the placed mixed shulker before stopping.");
            }
            return;
        }
        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            ownInteraction(BARITONE.rightClickBlock(
                    packDestination[0], packDestination[1], packDestination[2]));
        }
    }

    private void tickMixedStageDeposit() {
        if (actionCooldown > 0) { actionCooldown--; return; }
        Container open = getLiveOpenContainer();
        if (open == null) {
            if (!switchMixedStagingDestination()) {
                recoverMixedShulkerAndStop("mixed_staging_container_lost",
                        "Import staging became unavailable; recovering the placed mixed shulker before stopping.");
            }
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);
        Iterator<Integer> cargo = mixedCargoSlots.iterator();
        while (cargo.hasNext()) {
            int rawSlot = cargo.next();
            int windowSlot = rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = open.getItemStack(windowSlot);
            if (stack == null || stack.getAmount() <= 0) {
                cargo.remove();
                continue;
            }
            if (!containerCanAccept(open, chestSlots, stack)) {
                closeCurrentContainer();
                mixedUnavailableStagingDestinations.add(posKey(
                        packDestination[0], packDestination[1], packDestination[2]));
                if (!switchMixedStagingDestination()) {
                    recoverMixedShulkerAndStop("mixed_staging_full",
                            "All registered import chests are full; recovering the placed mixed shulker before stopping.");
                }
                return;
            }
            rememberMixedStagingDestination(packDestination);
            if (quickMoveSlot(windowSlot)) {
                persistDurableCheckpoint(State.MIXED_STAGE_DEPOSIT);
            }
            actionCooldown = config.organizerClickCooldownTicks;
            return;
        }

        state = State.MIXED_STAGE_CLOSING;
        actionCooldown = 0;
    }

    private void tickMixedStageClosing() {
        actionCooldown++;
        if (actionCooldown == 3) {
            closeCurrentContainer();
            return;
        }
        if (actionCooldown < 6) return;
        actionCooldown = 0;
        walkTarget = reconciliationStation;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.MIXED_RETURN_WALK;
        persistDurableCheckpoint(state);
    }

    private boolean switchMixedStagingDestination() {
        closeCurrentContainer();
        for (int[] candidate : stagingImportDestinations) {
            long key = posKey(candidate[0], candidate[1], candidate[2]);
            if (!mixedUnavailableStagingDestinations.contains(key)) {
                startMixedStageWalk(candidate);
                return true;
            }
        }
        return false;
    }

    private void recoverMixedShulkerAndStop(String reason, String message) {
        info(message);
        emit("organize_failed", Map.of("reason", reason));
        stopAfterShulkerRecovery = true;
        beginTemporaryShulkerRecovery(reason);
    }

    private static boolean containerCanAccept(Container open, int chestSlots, ItemStack cargo) {
        ItemData cargoData = ItemRegistry.REGISTRY.get(cargo.getId());
        int maxStack = cargoData == null ? 1 : Math.max(1, cargoData.stackSize());
        for (int slot = 0; slot < chestSlots; slot++) {
            ItemStack target = open.getItemStack(slot);
            if (target == null || target.getAmount() <= 0) return true;
            if (target.getId() == cargo.getId() && target.getAmount() < maxStack
                    && Objects.equals(target.getDataComponents(), cargo.getDataComponents())) {
                return true;
            }
        }
        return false;
    }

    private void rememberMixedStagingDestination(int[] destination) {
        long key = posKey(destination[0], destination[1], destination[2]);
        boolean known = mixedStagingUsedDestinations.stream().anyMatch(existing ->
                posKey(existing[0], existing[1], existing[2]) == key);
        if (!known) mixedStagingUsedDestinations.add(copyPos(destination));
    }

    private void reopenMixedShulkerAtStation() {
        containerOpenGate.reset();
        openWaitTicks = 0;
        containerDataReceived = false;
        actionSlotIndex = 0;
        state = State.SHULKER_OPENING;
        persistDurableCheckpoint(state);
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
            if (isProtectedInventorySlot(actionSlotIndex)) {
                actionSlotIndex++;
                continue;
            }
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
            BARITONE.stop();
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
            ownInteraction(shulkerBreakFuture);
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

        if (mixedDecompositionMode
                && countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement) {
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            finishMixedShulkerDecomposition();
            return;
        }

        // Breaking only proves that the block became air. Walk onto the former block position
        // like MOAR does so collection is an explicit part of the transaction, then verify that
        // the inventory regained both the placed box and a compatible packed box.
        if (hasPackedShulkerInInventory()) {
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            walkTarget = packDestination;
            trackedWalkTargetKey = Long.MIN_VALUE;
            state = State.SHULKER_STORE_WALK;
            openWaitTicks = 0;
            containerDataReceived = false;
            persistDurableCheckpoint(state);
            return;
        }

        pathToShulkerDrop();
        if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
            BARITONE.stop();
            info("Shulker pickup failed");
            startOverflow();
        }
    }

    private void finishMixedShulkerDecomposition() {
        MoveTask mixedTask = currentTask;
        if (mixedTask == null || !mixedTask.mixedDecomposition() || !mixedBoxDrained) {
            abortWithCargo("mixed_shulker_completion_unverified",
                    "The mixed box could not be verified empty; its cargo is preserved for recovery.");
            return;
        }

        List<int[]> sources = mixedStagingUsedDestinations.isEmpty()
                ? List.of(copyPos(mixedTask.destination()))
                : mixedStagingUsedDestinations.stream().map(StashOrganizer::copyPos).toList();
        List<MoveTask> batch = new ArrayList<>();
        List<String> storageClasses = mixedTask.mixedContents().keySet().stream()
                .map(StorageClassPolicy::exact)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        for (String storageClass : storageClasses) {
            Column column = columnAssignment.get(storageClass);
            int[] destination = column == null
                    ? copyPos(mixedTask.destination())
                    : copyPos(column.top());
            if (column == null) {
                stagingStorageClassesPlanned.add(storageClass);
                stagingReason = stagingReason == null
                        ? "mixed_shulker_lane_shortfall"
                        : stagingReason;
            }
            for (int[] source : sources) {
                batch.add(MoveTask.mixedBatch(source, destination, storageClass));
            }
        }

        completedTasks++;
        decomposedMixedShulkers++;
        totalTasks += batch.size();
        emitProgressMilestoneIfCrossed();

        currentTask = null;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        if (batch.isEmpty()) {
            advanceToNextTask();
            return;
        }
        for (int index = batch.size() - 1; index >= 0; index--) {
            consolidationQueue.addFirst(batch.get(index));
        }
        mixedBatchConsolidationMode = true;
        consolidationMode = true;
        advanceConsolidation();
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
            BARITONE.stop();
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
            ownInteraction(shulkerBreakFuture);
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
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            emit("organize_recovery_completed", Map.of(
                    "reason", "temporary_shulker_recovered",
                    "trigger", Objects.toString(shulkerRecoveryTrigger, "unknown"),
                    "shulker_position", posString(shulkerPlacePos)
            ));
            if (mixedDecompositionMode) {
                shulkerPlacePos = null;
                shulkerPlaceFuture = null;
                shulkerBreakFuture = null;
                if (!mixedCargoSlots.isEmpty()) {
                    startMixedStageWalk(packDestination);
                } else if (mixedBoxDrained) {
                    finishMixedShulkerDecomposition();
                } else {
                    state = State.SHULKER_SELECTING;
                    persistDurableCheckpoint(state);
                }
                return;
            }
            if (stopAfterShulkerRecovery) {
                resetTemporaryShulkerState();
                finishStop();
            } else {
                resetTemporaryShulkerState();
                startOverflowAfterShulkerCleanup();
            }
            return;
        }

        pathToShulkerDrop();

        if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
            BARITONE.stop();
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
                "manual_intervention_required", true,
                "terminal", true,
                "cargo_preserved", false,
                "checkpoint_preserved", false
        ));
        BARITONE.stop();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.DONE;
        clearDurableJournal();
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
            ownInteraction(BARITONE.rightClickBlock(walkTarget[0], walkTarget[1], walkTarget[2]));
        }
    }

    private void tickOverflowDepositing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            advanceToNextTask();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);

        // The appended player inventory is authoritative until this window closes. Never
        // sweep protected bot-kit slots into overflow storage.
        while (actionSlotIndex < 45) {
            if (isProtectedInventorySlot(actionSlotIndex)) {
                actionSlotIndex++;
                continue;
            }
            int containerSlotIndex = rawPlayerSlotToWindowSlot(chestSlots, actionSlotIndex);
            ItemStack stack = open.getItemStack(containerSlotIndex);
            if (stack != null && stack.getAmount() > 0) {
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
        if (mixedBatchConsolidationMode) {
            MoveTask next = consolidationQueue.peekFirst();
            if (next == null || !next.mixedBatchConsolidation()) {
                mixedBatchConsolidationMode = false;
                consolidationMode = false;
                advanceToNextTask();
                return;
            }
        }

        // All collected → continue normal work or finish.
        if (consolidationQueue.isEmpty()) {
            consolidationMode = false;
            if (taskQueue.isEmpty()) finishOrganization();
            else advanceToNextTask();
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
        persistDurableCheckpoint(state);
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
        persistDurableCheckpoint(state);
        return true;
    }

    private void resumeInterruptedCheckpoint(State interrupted) {
        if (interrupted == null) {
            abortWithCargo("organizer_checkpoint_missing",
                    "The organizer pause checkpoint was missing; cargo is preserved in inventory.");
            return;
        }

        if (temporaryShulkerOutstanding || isTemporaryShulkerState(interrupted)) {
            resumeTemporaryShulkerCheckpoint();
            return;
        }

        switch (interrupted) {
            case PLANNING -> state = State.PLANNING;
            case WALKING, OPENING, TAKING, CLOSING_SOURCE, DEPOSITING, CLOSING_DEST ->
                    resumeMoveCheckpoint(interrupted);
            case SHULKER_STATION_WALK, SHULKER_SELECTING -> resumePackingSelection();
            case SHULKER_FETCH_WALK, SHULKER_FETCH_OPEN, SHULKER_FETCH_TAKE,
                 SHULKER_FETCH_CLOSING -> resumePackingSupplyFetch();
            case SHULKER_STORE_WALK, SHULKER_STORE_OPEN, SHULKER_STORE_DEPOSIT ->
                    resumePackedShulkerStore();
            case SHULKER_EMPTYING, MIXED_SOURCE_CLOSING,
                 MIXED_STAGE_WALK, MIXED_STAGE_OPEN, MIXED_STAGE_DEPOSIT,
                 MIXED_STAGE_CLOSING, MIXED_RETURN_WALK -> resumeTemporaryShulkerCheckpoint();
            case CRAFT_MATERIAL_WALK, CRAFT_MATERIAL_OPEN, CRAFT_MATERIAL_TAKE,
                 CRAFT_WALKING, CRAFT_OPENING, CRAFT_PLACING, CRAFT_TAKING,
                 OVERFLOW_WALKING, OVERFLOW_OPENING, OVERFLOW_DEPOSITING ->
                    startOverflowAfterShulkerCleanup();
            case SHULKER_RESUME_WALK, SHULKER_PLACING, SHULKER_WAIT_PLACE,
                 SHULKER_OPENING, SHULKER_FILLING, SHULKER_CLOSING,
                 SHULKER_BREAKING, SHULKER_PICKUP, SHULKER_RECOVERY_BREAKING,
                 SHULKER_RECOVERY_PICKUP -> resumeTemporaryShulkerCheckpoint();
            case IDLE, YIELDED, DONE -> state = State.DONE;
        }
    }

    private static boolean isTemporaryShulkerState(State state) {
        return switch (state) {
            case SHULKER_PLACING, SHULKER_WAIT_PLACE, SHULKER_OPENING,
                 SHULKER_EMPTYING, MIXED_SOURCE_CLOSING,
                 MIXED_STAGE_WALK, MIXED_STAGE_OPEN, MIXED_STAGE_DEPOSIT,
                 MIXED_STAGE_CLOSING, MIXED_RETURN_WALK,
                 SHULKER_FILLING, SHULKER_CLOSING, SHULKER_BREAKING,
                 SHULKER_PICKUP, SHULKER_RESUME_WALK,
                 SHULKER_RECOVERY_BREAKING, SHULKER_RECOVERY_PICKUP -> true;
            default -> false;
        };
    }

    private void resumeMoveCheckpoint(State interrupted) {
        if (currentTask == null) {
            advanceToNextTask();
            return;
        }

        boolean cargoPresent = hasCurrentTaskCargoInInventory();
        if (consolidationMode && cargoPresent) {
            if (interrupted == State.TAKING && movedThisVisit > 0) {
                consolidationSourcesInBatch++;
                consolidationQueue.addFirst(currentTask);
            }
            startShulkerPacking(currentTask.itemId(), currentTask.destination());
            return;
        }

        boolean destinationPhase = currentRole == TargetRole.DESTINATION
                || interrupted == State.DEPOSITING
                || interrupted == State.CLOSING_DEST;
        if (cargoPresent) {
            transitionToDestination();
        } else if (destinationPhase) {
            // The transfer completed while the handoff was being observed. Let the normal
            // completion state account for the task exactly once.
            state = State.CLOSING_DEST;
        } else {
            currentRole = TargetRole.SOURCE;
            walkTarget = currentTask.source();
            actionSlotIndex = 0;
            movedThisVisit = 0;
            sourceVisitFailed = false;
            state = State.WALKING;
        }
    }

    private void resumePackingSelection() {
        if (reconciliationStation == null) {
            abortWithCargo("reconciliation_checkpoint_missing",
                    "The reconciliation station checkpoint was missing; cargo is preserved in inventory.");
            return;
        }
        walkTarget = reconciliationStation;
        state = State.SHULKER_STATION_WALK;
    }

    private void resumePackingSupplyFetch() {
        if (findPackingShulkerInInventory() >= 0) {
            resumePackingSelection();
        } else {
            startFetchShulker();
        }
    }

    private void resumeTemporaryShulkerCheckpoint() {
        if (reconciliationStation == null || shulkerPlacePos == null) {
            abortWithCargo("temporary_shulker_checkpoint_missing",
                    "The temporary shulker checkpoint was incomplete; cargo is preserved in inventory.");
            return;
        }
        walkTarget = reconciliationStation;
        state = State.SHULKER_RESUME_WALK;
    }

    private void resumeTemporaryShulkerAtStation() {
        shulkerPlaceFuture = null;
        shulkerBreakFuture = null;
        shulkerTicks = 0;

        if (mixedDecompositionMode) {
            if (!mixedCargoSlots.isEmpty()) {
                startMixedStageWalk(packDestination != null
                        ? packDestination
                        : currentTask == null ? null : currentTask.destination());
                return;
            }
            if (isShulkerAtPosition(shulkerPlacePos)) {
                temporaryShulkerOutstanding = true;
                reopenMixedShulkerAtStation();
                return;
            }
            if (mixedBoxDrained
                    && countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement) {
                temporaryShulkerOutstanding = false;
                finishMixedShulkerDecomposition();
                return;
            }
            temporaryShulkerOutstanding = true;
            state = State.SHULKER_RECOVERY_PICKUP;
            return;
        }

        if (isShulkerAtPosition(shulkerPlacePos)) {
            temporaryShulkerOutstanding = true;
            containerOpenGate.reset();
            openWaitTicks = 0;
            containerDataReceived = false;
            state = State.SHULKER_OPENING;
            return;
        }
        if (hasPackedShulkerInInventory()) {
            temporaryShulkerOutstanding = false;
            walkToPackedShulkerDestination(packDestination);
            return;
        }
        if (countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement) {
            temporaryShulkerOutstanding = false;
            state = State.SHULKER_SELECTING;
            return;
        }

        // The block is gone but its item has not returned yet. Reuse the bounded pickup
        // recovery rather than assuming the interrupting task collected or destroyed it.
        temporaryShulkerOutstanding = true;
        state = State.SHULKER_RECOVERY_PICKUP;
    }

    private void resumePackedShulkerStore() {
        if (hasPackedShulkerInInventory()) {
            walkToPackedShulkerDestination(packDestination);
        } else {
            completePackedShulkerStore();
        }
    }

    private boolean hasCurrentTaskCargoInInventory() {
        if (currentTask == null) return false;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return false;

        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = player.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            if (currentTask.shulkerContentFilter() == null) return true;
            if (!isShulkerBoxItem(currentTask.itemId())) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (currentTask.mixedDecomposition()
                    && classification.kind() == ShulkerClassification.Kind.MIXED
                    && currentTask.shulkerContentFilter().equals(classification.fingerprint())) {
                return true;
            }
            if (classification.kind() == ShulkerClassification.Kind.BULK
                    && ItemIdentifier.contentItemIdsMatch(
                            currentTask.shulkerContentFilter(), classification.storageKey())) {
                return true;
            }
        }
        return false;
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
        persistDurableCheckpoint(state);
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
        persistDurableCheckpoint(state);
    }

    private void finishOrganization() {
        BARITONE.stop();
        clearOwnedAutomation();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        state = State.DONE;
        clearDurableJournal();
        emit("organize_completed", organizerCompletionPayload());
        info("Organization complete! " + completedTasks + " moves executed.");

        if (decomposedMixedShulkers > 0) {
            info(decomposedMixedShulkers + " mixed shulker(s) were separated into exact-item storage.");
        }

        if (stagedShulkers > 0) {
            info(stagedShulkers + " reconciled shulker(s) across " + stagedStorageClasses.size()
                    + " item type(s) are waiting in import chests. Add suitable permanent lanes, rescan, then organize again.");
        } else if (permanentLaneGaps > 0) {
            info(permanentLaneGaps + " item type(s) still need suitable permanent lanes.");
        }
        if (!overflowItems.isEmpty()) {
            info(overflowItems.size() + " item types overflowed.");
        }

        // Auto-label organized columns
        index.assignLabels();

        info("Run /stash scan to refresh the index.");
    }

    private Map<String, Object> organizerCompletionPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completed_tasks", completedTasks);
        payload.put("total_tasks", totalTasks);
        payload.put("overflow_types", overflowItems.size());
        payload.put("staged_shulkers", stagedShulkers);
        payload.put("decomposed_mixed_shulkers", decomposedMixedShulkers);
        payload.put("staged_storage_classes", stagedStorageClasses.size());
        payload.put("staging_storage_classes_planned", stagingStorageClassesPlanned.size());
        payload.put("permanent_lane_gaps", permanentLaneGaps);
        if (stagingReason != null) payload.put("staging_reason", stagingReason);
        return payload;
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
            ownInteraction(BARITONE.rightClickBlock(pos[0], pos[1], pos[2]));
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
            ownInventory(INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer(cacheContainerId))
                    .priority(5000)
                    .actionDelayTicks(0)
                    .build()));
        } catch (Exception ignored) {}
        containerDataReceived = false;
        openContainerId = -1;
    }

    private void closeCurrentContainerForYield() {
        int cachedContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openContainerId > 0 && cachedContainerId == openContainerId && serverSession != null) {
            try {
                serverSession.send(new ServerboundContainerClosePacket(openContainerId));
            } catch (Exception ignored) {
            }
        }
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
    }

    private void ownCustomGoal(PathingRequestFuture request) {
        ownedBaritoneRequest = request;
        ownedBaritoneProcess = OwnedBaritoneProcess.CUSTOM_GOAL;
    }

    private void ownInteraction(PathingRequestFuture request) {
        ownedBaritoneRequest = request;
        ownedBaritoneProcess = OwnedBaritoneProcess.INTERACTION;
    }

    private void ownInventory(RequestFuture request) {
        ownedInventoryRequest = request;
    }

    private void stopOwnedBaritoneProcess() {
        if (ownedBaritoneRequest != null && ownedBaritoneRequest.isCompleted()) {
            ownedBaritoneRequest = null;
            ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
            return;
        }
        if (ownedBaritoneProcess == OwnedBaritoneProcess.CUSTOM_GOAL) {
            BARITONE.getCustomGoalProcess().stop();
        } else if (ownedBaritoneProcess == OwnedBaritoneProcess.INTERACTION) {
            BARITONE.getInteractWithProcess().stop();
        }
        ownedBaritoneRequest = null;
        ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
    }

    private void clearOwnedAutomation() {
        ownedBaritoneRequest = null;
        ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
        ownedInventoryRequest = null;
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

    /** Keeps a produced bulk shulker in hand until a live destination accepts it. */
    private void retryOrSwitchPackedShulkerDestination(String reason, boolean knownFull) {
        closeCurrentContainer();
        boolean staging = isImportStagingPack();
        int attempt = ++packDestinationOpenFailures;
        boolean retrySame = attempt <= MAX_DESTINATION_OPEN_RETRIES && (!knownFull || !staging);
        if (retrySame) {
            info("Packed-shulker destination failed; retrying " + attempt + "/"
                    + MAX_DESTINATION_OPEN_RETRIES + ".");
            emit("organize_target_failed", Map.of(
                    "reason", reason,
                    "retry_disposition", "same_destination",
                    "attempt", attempt,
                    "max_attempts", MAX_DESTINATION_OPEN_RETRIES
            ));
            walkToPackedShulkerDestination(packDestination);
            return;
        }

        if (staging) {
            packStoreTriedDestinations.add(posKey(
                    packDestination[0], packDestination[1], packDestination[2]));
            for (int[] candidate : stagingImportDestinations) {
                long key = posKey(candidate[0], candidate[1], candidate[2]);
                if (packStoreTriedDestinations.contains(key)) continue;
                packDestination = new int[]{candidate[0], candidate[1], candidate[2]};
                packDestinationOpenFailures = 0;
                packStoreMatchingShulkersBefore = 0;
                packStoreVerificationTicks = 0;
                info("Import staging destination was unavailable; trying another registered import chest.");
                emit("organize_target_failed", Map.of(
                        "reason", reason,
                        "retry_disposition", "alternate_import"
                ));
                walkToPackedShulkerDestination(packDestination);
                return;
            }
            abortWithCargo("import_staging_full_with_cargo",
                    "Every registered import chest is full or unreachable. The reconciled shulker is preserved in inventory; clear import space and organize again.");
            return;
        }

        abortWithCargo("packed_shulker_destination_unavailable_with_cargo",
                "The permanent shulker destination remained unavailable; the reconciled shulker is preserved in inventory.");
    }

    private void walkToPackedShulkerDestination(int[] destination) {
        currentRole = TargetRole.DESTINATION;
        walkTarget = destination;
        openWaitTicks = 0;
        containerDataReceived = false;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.SHULKER_STORE_WALK;
        persistDurableCheckpoint(state);
    }

    private boolean isImportStagingPack() {
        if (packDestination == null || packItemId == null) return false;
        boolean importDestination = index.isImportChest(
                packDestination[0], packDestination[1], packDestination[2]);
        return OrganizerOwnershipPolicy.isReconciliationStagingDestination(
                importDestination, columnAssignment.containsKey(packItemId));
    }

    private static boolean containerHasEmptySlot(Container container, int containerSlots) {
        if (container == null || containerSlots <= 0) return false;
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) return true;
        }
        return false;
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
        State failedState = state;
        BARITONE.stop();
        clearOwnedAutomation();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        // Keep the last transaction checkpoint. The user can repair the destination and use
        // /stash organize resume without replaying the completed portion of a multi-hour job.
        boolean checkpointPreserved = persistDurableCheckpoint(failedState)
                || hasDurableCheckpoint();
        state = State.DONE;
        emit("organize_failed", Map.of(
                "reason", reason,
                "failed_state", failedState.name(),
                "terminal", true,
                "cargo_preserved", true,
                "checkpoint_preserved", checkpointPreserved
        ));
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

    private boolean loadAndSnapshotProtectedInventorySlots() {
        final InventoryKeepPolicy policy;
        try {
            var db = StashManagerPlugin.getDatabase();
            if (!config.databaseEnabled) {
                policy = InventoryKeepPolicy.empty();
            } else if (db == null || !db.isInitialized()) {
                info("Cannot organize safely: the database-backed inventory keep list is unavailable.");
                emit("organize_start_blocked", Map.of("reason", "keep_list_database_unavailable"));
                return false;
            } else {
                policy = InventoryKeepPolicy.from(db.loadKeepItems());
            }
        } catch (SQLException | RuntimeException e) {
            info("Cannot organize safely: the inventory keep list could not be loaded.");
            emit("organize_start_blocked", Map.of(
                    "reason", "keep_list_unavailable",
                    "message", Objects.toString(e.getMessage(), e.getClass().getSimpleName())
            ));
            return false;
        }

        protectedInventorySlots.clear();
        if (!policy.isEmpty()) {
            Container liveOpen = getLiveOpenContainer();
            Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
            if (liveOpen == null && player == null) {
                info("Cannot organize safely: player inventory is unavailable for keep-list protection.");
                emit("organize_start_blocked", Map.of("reason", "inventory_unavailable_for_keep_list"));
                return false;
            }

            List<InventoryKeepPolicy.SlotStack> snapshot = new ArrayList<>();
            for (int slot = 9; slot <= 44; slot++) {
                ItemStack stack = getCurrentPlayerInventoryStack(slot);
                if (stack != null && stack.getAmount() > 0) {
                    snapshot.add(new InventoryKeepPolicy.SlotStack(
                            slot, itemIdFromStack(stack), stack.getAmount()));
                }
            }
            protectedInventorySlots.addAll(policy.protectedSlots(snapshot));
        }
        keepProtectionNeedsRefresh = false;
        if (!protectedInventorySlots.isEmpty()) {
            info("Protected " + protectedInventorySlots.size()
                    + " original inventory slot(s) for this organization job.");
        }
        emit("organize_keep_snapshot", Map.of(
                "protected_slots", protectedInventorySlots.size()
        ));
        return true;
    }

    private boolean isProtectedInventorySlot(int rawSlot) {
        return protectedInventorySlots.contains(rawSlot);
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
            ownInventory(future);
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

    // Durable organizer journal
    private boolean beginDurableJournal() {
        resetJournalMemory();
        journalJobId = UUID.randomUUID().toString();
        journalCreatedAtEpochMilli = System.currentTimeMillis();
        journalDimension = currentDimensionName();
        journalPlanDirty = true;
        if (persistDurableCheckpoint(State.PLANNING)) return true;

        try {
            journalStore.clear();
        } catch (IOException ignored) {
        }
        resetJournalMemory();
        info("Organization stopped before moving items because its restart checkpoint could not be written.");
        return false;
    }

    private boolean persistDurableCheckpoint(State resumableState) {
        if (journalJobId == null || resumableState == null
                || resumableState == State.IDLE || resumableState == State.DONE
                || resumableState == State.YIELDED) {
            return false;
        }
        try {
            syncJournalTaskCatalog();
            if (journalPlanDirty) {
                journalStore.savePlan(snapshotJournalPlan());
                journalPlanDirty = false;
            }
            long now = System.currentTimeMillis();
            journalStore.saveCheckpoint(snapshotJournalCheckpoint(resumableState, now));
            durableCheckpointUpdatedAtEpochMilli = now;
            journalPersistenceFailed = false;
            return true;
        } catch (IOException | RuntimeException e) {
            reportJournalFailure(e);
            return false;
        }
    }

    private OrganizerJournalStore.Plan snapshotJournalPlan() {
        List<OrganizerJournalStore.TaskSnapshot> tasks = journalTasks.entrySet().stream()
                .map(entry -> {
                    MoveTask task = entry.getValue();
                    return new OrganizerJournalStore.TaskSnapshot(
                            entry.getKey(), copyPos(task.source()), copyPos(task.destination()),
                            task.itemId(), task.shulkerContentFilter(), task.alreadyInInventory(),
                            task.mixedDecomposition(), task.mixedBatchConsolidation(),
                            task.mixedContents());
                })
                .toList();
        Map<String, OrganizerJournalStore.ColumnSnapshot> assignments = new LinkedHashMap<>();
        for (var entry : columnAssignment.entrySet()) {
            Column column = entry.getValue();
            assignments.put(entry.getKey(), new OrganizerJournalStore.ColumnSnapshot(
                    column.id(), column.chests().stream().map(StashOrganizer::copyPos).toList()));
        }
        return new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                journalJobId,
                journalCreatedAtEpochMilli,
                journalDimension,
                copyPos(config.pos1),
                copyPos(config.pos2),
                tasks,
                assignments,
                new ArrayList<>(managedSourceContainerKeys));
    }

    private OrganizerJournalStore.Checkpoint snapshotJournalCheckpoint(
            State resumableState, long updatedAtEpochMilli) {
        Integer currentId = currentTask == null ? null : ensureJournalTaskId(currentTask);
        List<Integer> normalIds = taskQueue.stream().map(this::ensureJournalTaskId).toList();
        List<Integer> consolidationIds = consolidationQueue.stream()
                .map(this::ensureJournalTaskId).toList();
        return new OrganizerJournalStore.Checkpoint(
                OrganizerJournalStore.SCHEMA_VERSION,
                journalJobId,
                updatedAtEpochMilli,
                resumableState.name(),
                currentRole.name(),
                currentId,
                normalIds,
                consolidationIds,
                consolidationMode,
                consolidationSourcesInBatch,
                movedThisVisit,
                sourceVisitFailed,
                totalTasks,
                completedTasks,
                nextProgressMilestone,
                copyNullablePos(reconciliationStation),
                copyNullablePos(reconciliationWorksite),
                packItemId,
                copyNullablePos(packDestination),
                copyNullablePos(shulkerPlacePos),
                fetchedPackingShulker,
                shulkerInventoryCountBeforePlacement,
                compatibleShulkerCountBeforePlacement,
                temporaryShulkerOutstanding,
                stagingImportDestinations.stream().map(StashOrganizer::copyPos).toList(),
                new ArrayList<>(stagingStorageClassesPlanned),
                new ArrayList<>(stagedStorageClasses),
                stagedShulkers,
                permanentLaneGaps,
                stagingReason,
                new LinkedHashMap<>(overflowItems),
                mixedDecompositionMode,
                mixedBatchConsolidationMode,
                mixedBoxDrained,
                decomposedMixedShulkers,
                mixedPendingSourceSlot,
                mixedPendingCargoSlot,
                new ArrayList<>(mixedCargoSlots),
                mixedStagingUsedDestinations.stream().map(StashOrganizer::copyPos).toList(),
                new ArrayList<>(protectedInventorySlots));
    }

    private void syncJournalTaskCatalog() {
        if (currentTask != null) ensureJournalTaskId(currentTask);
        taskQueue.forEach(this::ensureJournalTaskId);
        consolidationQueue.forEach(this::ensureJournalTaskId);
    }

    private int ensureJournalTaskId(MoveTask task) {
        Integer current = journalTaskIds.get(task);
        if (current != null) return current;
        int assigned = nextJournalTaskId++;
        journalTaskIds.put(task, assigned);
        journalTasks.put(assigned, task);
        journalPlanDirty = true;
        return assigned;
    }

    private MoveTask taskForJournalId(Integer id) {
        return id == null ? null : requiredJournalTask(id);
    }

    private MoveTask requiredJournalTask(Integer id) {
        MoveTask task = journalTasks.get(id);
        if (task == null) throw new IllegalStateException("Saved organizer task " + id + " is missing");
        return task;
    }

    private boolean clearDurableJournal() {
        if (journalJobId != null) {
            // If deletion only partly succeeds, a terminal marker prevents an old checkpoint
            // from ever being replayed as a valid move transaction.
            try {
                syncJournalTaskCatalog();
                if (journalPlanDirty) journalStore.savePlan(snapshotJournalPlan());
                journalStore.saveCheckpoint(snapshotJournalCheckpoint(
                        State.DONE, System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
        }
        try {
            journalStore.clear();
            resetJournalMemory();
            return true;
        } catch (IOException e) {
            reportJournalFailure(e);
            resetJournalMemory();
            durableRecoveryError = "could not remove organizer checkpoint: " + e.getMessage();
            journalPersistenceFailed = true;
            return false;
        }
    }

    private void resetJournalMemory() {
        journalTaskIds.clear();
        journalTasks.clear();
        journalJobId = null;
        journalCreatedAtEpochMilli = 0L;
        journalDimension = "";
        nextJournalTaskId = 1;
        journalPlanDirty = false;
        journalPersistenceFailed = false;
        durableRecoveryLoaded = false;
        durableRecoveryError = null;
        durableCheckpointUpdatedAtEpochMilli = 0L;
    }

    private void reportJournalFailure(Exception error) {
        durableRecoveryError = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        if (journalPersistenceFailed) return;
        journalPersistenceFailed = true;
        info("Organizer restart checkpoint failed: " + durableRecoveryError);
        emit("organize_checkpoint_failed", Map.of(
                "reason", "journal_write_failed",
                "message", durableRecoveryError
        ));
    }

    private static int[] copyPos(int[] position) {
        return new int[]{position[0], position[1], position[2]};
    }

    private static int[] copyNullablePos(int[] position) {
        return position == null ? null : copyPos(position);
    }

    private static String currentDimensionName() {
        try {
            return World.getCurrentDimension().name();
        } catch (Exception ignored) {
            return "";
        }
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
            case SHULKER_EMPTYING, MIXED_SOURCE_CLOSING,
                 MIXED_STAGE_WALK, MIXED_STAGE_OPEN, MIXED_STAGE_DEPOSIT,
                 MIXED_STAGE_CLOSING, MIXED_RETURN_WALK
                                   -> "Separating a mixed shulker...";
            case SHULKER_RESUME_WALK -> "Returning to paused reconciliation work...";
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
            case YIELDED           -> durableRecoveryLoaded
                    ? "Restart checkpoint loaded; waiting to resume..."
                    : "Paused for another task...";
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
        compatibleShulkerCountBeforePlacement = 0;
        shulkerPlacePos = null;
        shulkerPlaceFuture = null;
        shulkerBreakFuture = null;
    }

    private void clearMixedDecompositionState() {
        mixedDecompositionMode = false;
        mixedBoxDrained = false;
        mixedPendingSourceSlot = -1;
        mixedPendingCargoSlot = -1;
        mixedCargoSlots.clear();
        mixedStagingUsedDestinations.clear();
        mixedUnavailableStagingDestinations.clear();
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
            if (isProtectedInventorySlot(i)) continue;
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

    private int findCurrentMixedShulkerInInventory() {
        if (currentTask == null || !currentTask.mixedDecomposition()) return -1;
        Container playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return -1;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() == ShulkerClassification.Kind.MIXED
                    && currentTask.shulkerContentFilter().equals(classification.fingerprint())) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isCompatiblePartialBulkShulker(ShulkerClassification classification) {
        if (classification.kind() != ShulkerClassification.Kind.BULK
                || !ItemIdentifier.contentItemIdsMatch(packItemId, classification.storageKey())) {
            return false;
        }
        int count = classification.contents().values().stream().mapToInt(Integer::intValue).sum();
        return count < LaneStorageCapacity.itemCapacityFor(
                classification.storageKey()).itemsPerShulker();
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
        int expectedCompatible = Math.max(1, compatibleShulkerCountBeforePlacement);
        return countShulkerBoxesInInventory() >= shulkerInventoryCountBeforePlacement
                && countCompatibleBulkShulkersInInventory(packItemId) >= expectedCompatible;
    }

    private void pathToShulkerDrop() {
        if (shulkerPlacePos == null || BARITONE.getCustomGoalProcess().isActive()) return;
        setBaritoneBreakingAllowed(false);
        ownCustomGoal(BARITONE.pathTo(new GoalBlock(new BlockPos(
                shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2]))));
    }

    private int countCompatibleBulkShulkersInInventory(String storageClass) {
        if (storageClass == null) return 0;
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return 0;
        int count = 0;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() <= 0 || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() == ShulkerClassification.Kind.BULK
                    && ItemIdentifier.contentItemIdsMatch(storageClass, classification.storageKey())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private int countCompatibleBulkShulkersInOpenPlayerInventory(
            Container open, int containerSlots, String storageClass) {
        if (open == null || storageClass == null) return 0;
        int count = 0;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            ItemStack stack = open.getItemStack(rawPlayerSlotToWindowSlot(containerSlots, rawSlot));
            if (stack == null || stack.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() == ShulkerClassification.Kind.BULK
                    && ItemIdentifier.contentItemIdsMatch(storageClass, classification.storageKey())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    static boolean packedShulkerTransferConfirmed(
            int matchingBefore, int matchingAfter, int submittedTransfers) {
        return submittedTransfers > 0 && matchingBefore > matchingAfter;
    }

    private int packedShulkerVerificationTimeoutTicks() {
        return Math.max(40, config.organizerClickCooldownTicks * 4);
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

    private boolean moveShulkerToHotbar(int slot) {
        try {
            var builder = InventoryActionRequest.builder().owner(this).priority(6000);
            if (slot >= 36 && slot <= 44) {
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                int hotbarIndex = preferredUnprotectedHotbarIndex();
                if (hotbarIndex < 0) return false;
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.from(hotbarIndex)),
                    new SetHeldItem(hotbarIndex)
                );
            }
            RequestFuture future = INVENTORY.submit(builder.build());
            ownInventory(future);
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            info("Failed to move shulker to hotbar: " + e.getMessage());
            return false;
        }
    }

    private int preferredUnprotectedHotbarIndex() {
        // Keep the historical slot-seven preference when it is safe, then use any other
        // unprotected hotbar slot. Moving to hotbar swaps both slots, so the destination must
        // never contain bot-kit inventory protected by the keep list.
        if (!isProtectedInventorySlot(42)) return 6;
        for (int hotbarIndex = 0; hotbarIndex < HOTBAR_SIZE; hotbarIndex++) {
            if (!isProtectedInventorySlot(36 + hotbarIndex)) return hotbarIndex;
        }
        return -1;
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

        for (int i = 9; i <= 44; i++) {
            if (isProtectedInventorySlot(i)) continue;
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
