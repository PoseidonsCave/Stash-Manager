package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.process.InteractWithProcess;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.World;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
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
import com.zenith.plugin.stashmanager.scanner.ContainerReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.BulkBatchPlanner;
import com.zenith.plugin.stashmanager.orchestration.DedicatedLaneCapacity;
import com.zenith.plugin.stashmanager.orchestration.ImportDestinationTracker;
import com.zenith.plugin.stashmanager.orchestration.ImportStagingPolicy;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.orchestration.MixedInventoryRecoveryPlanner;
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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.network.Session;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zenith.Globals.*;

// Sorts items across containers in the configured region.
public final class StashOrganizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Plugin.StashManager.Organizer");

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
        MIXED_SHELL_VERIFY,
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
        DONE,
        FAILED
    }

    private enum TargetRole { SOURCE, DESTINATION }

    private enum OwnedBaritoneProcess {
        NONE,
        CUSTOM_GOAL,
        INTERACTION
    }

    private static final class PendingQuickMove {
        final int containerId;
        final int slot;
        final int itemId;
        final int amount;
        final InventoryTransferEvidence evidence;
        final RequestFuture request;
        int verificationTicks;

        PendingQuickMove(
                int containerId,
                int slot,
                ItemStack source,
                InventoryTransferEvidence evidence,
                RequestFuture request) {
            this.containerId = containerId;
            this.slot = slot;
            this.itemId = source.getId();
            this.amount = evidence.requestedAmount();
            this.evidence = evidence;
            this.request = request;
        }
    }

    private static final class PendingOverflowSplit {
        final int containerId;
        final int sourceRawSlot;
        final int sourceWindowSlot;
        final int cargoUnits;
        final int maxWaitTicks;
        final InventoryTransferEvidence evidence;
        final RequestFuture request;
        int verificationTicks;

        PendingOverflowSplit(
                int containerId,
                int sourceRawSlot,
                int sourceWindowSlot,
                int cargoUnits,
                int maxWaitTicks,
                InventoryTransferEvidence evidence,
                RequestFuture request) {
            this.containerId = containerId;
            this.sourceRawSlot = sourceRawSlot;
            this.sourceWindowSlot = sourceWindowSlot;
            this.cargoUnits = cargoUnits;
            this.maxWaitTicks = maxWaitTicks;
            this.evidence = evidence;
            this.request = request;
        }
    }

    private enum QuickMoveOutcome {
        NONE,
        WAITING,
        CONFIRMED_DRAINED,
        CONFIRMED_PARTIAL,
        RETRYING
    }

    private record QuickMovePoll(QuickMoveOutcome outcome, int slot, int movedAmount) {
        private static QuickMovePoll none() {
            return new QuickMovePoll(QuickMoveOutcome.NONE, -1, 0);
        }
    }

    private record ShulkerPlacementSubmission(
            InputRequestFuture future,
            int[] support,
            String face,
            int targetOrdinal,
            int targetCount) {}

    private volatile State state = State.IDLE;
    private volatile OrganizerJournalStore.Failure lastFailure;
    private ContainerEntry openIndexedContainer;
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
            Map<String, Integer> mixedContents,
            boolean mandatoryInventoryDeposit) {
        MoveTask {
            mixedContents = mixedContents == null ? Map.of() : Map.copyOf(mixedContents);
        }
        MoveTask(int[] source, int[] destination, String itemId, String shulkerContentFilter,
                 boolean alreadyInInventory, boolean mixedDecomposition,
                 boolean mixedBatchConsolidation, Map<String, Integer> mixedContents) {
            this(source, destination, itemId, shulkerContentFilter, alreadyInInventory,
                    mixedDecomposition, mixedBatchConsolidation, mixedContents, false);
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
        static MoveTask mixedInInventory(
                int[] source, int[] stagingDestination, String shulkerItemId,
                String fingerprint, Map<String, Integer> contents) {
            return new MoveTask(source, stagingDestination, shulkerItemId, fingerprint,
                    true, true, false, contents);
        }
        MoveTask markAlreadyInInventory() {
            if (alreadyInInventory) return this;
            return new MoveTask(source, destination, itemId, shulkerContentFilter,
                    true, mixedDecomposition, mixedBatchConsolidation, mixedContents,
                    mandatoryInventoryDeposit);
        }
        MoveTask withDestination(int[] newDestination) {
            return new MoveTask(source, newDestination, itemId, shulkerContentFilter,
                    alreadyInInventory, mixedDecomposition, mixedBatchConsolidation, mixedContents,
                    mandatoryInventoryDeposit);
        }
        MoveTask withShulkerFingerprint(String fingerprint) {
            return new MoveTask(source, destination, itemId, fingerprint,
                    alreadyInInventory, mixedDecomposition, mixedBatchConsolidation, mixedContents,
                    mandatoryInventoryDeposit);
        }
        MoveTask withItemId(String newItemId) {
            return new MoveTask(source, destination, newItemId, shulkerContentFilter,
                    alreadyInInventory, mixedDecomposition, mixedBatchConsolidation, mixedContents,
                    mandatoryInventoryDeposit);
        }
        MoveTask withShulkerSnapshot(
                String fingerprint,
                Map<String, Integer> contents) {
            return new MoveTask(source, destination, itemId, fingerprint,
                    alreadyInInventory, mixedDecomposition, mixedBatchConsolidation, contents,
                    mandatoryInventoryDeposit);
        }
        MoveTask requireInventoryDeposit() {
            if (mandatoryInventoryDeposit) return this;
            return new MoveTask(source, destination, itemId, shulkerContentFilter,
                    alreadyInInventory, mixedDecomposition, mixedBatchConsolidation, mixedContents, true);
        }
        static MoveTask mixedBatch(int[] source, int[] destination, String itemId) {
            return new MoveTask(source, destination, itemId, null,
                    false, false, true, Map.of());
        }
        static MoveTask mixedBatchInInventory(int[] source, int[] destination, String itemId) {
            return new MoveTask(source, destination, itemId, null,
                    true, false, true, Map.of());
        }
    }

    record LegacyMixedPackingCandidate(
            int slot,
            String shulkerItemId,
            String fingerprint,
            Map<String, Integer> contents) {
        LegacyMixedPackingCandidate {
            contents = contents == null ? Map.of() : Map.copyOf(contents);
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
    private static final int BREAK_TIMEOUT_TICKS = 400;
    private static final int CONDENSE_MIN_ITEMS = 1;
    private static final int MIN_OPEN_TIMEOUT_TICKS = 400;
    private static final int OPEN_RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_DESTINATION_OPEN_RETRIES = 3;
    private static final int MAX_PACKED_IMPORT_CAPACITY_PROBES = 8;
    private static final int MAX_MIXED_STAGING_CAPACITY_PROBES = 8;
    private static final int MAX_PACKED_SHULKER_INVENTORY_SYNC_RECOVERIES = 3;
    private static final int MAX_SOURCE_TASK_RETRIES = 3;
    private static final int MAX_SHULKER_RECOVERY_BREAK_ATTEMPTS = 3;
    private static final int SHULKER_PICKUP_TIMEOUT_TICKS = 300;
    private static final int SHULKER_RECOVERY_PICKUP_TIMEOUT_TICKS = 900;
    private static final double SHULKER_PICKUP_EVIDENCE_RADIUS_SQ = 36.0;
    private static final int LATE_OPEN_QUARANTINE_TICKS = 100;
    private static final int CONTAINER_CACHE_READY_TIMEOUT_TICKS = 40;
    private static final int TRANSFER_VERIFICATION_TIMEOUT_TICKS = 40;
    private static final int MAX_TRANSFER_RETRIES = 3;
    private static final int INTERACTION_ATTEMPT_TIMEOUT_TICKS = 60;
    private static final String EMPTY_SHULKER_STAGING_FILTER = "@empty";
    private static final String EMPTY_SHULKER_FINGERPRINT =
            ShulkerClassification.classify(Map.of()).fingerprint();
    private static final String ORPHANED_WORKSITE_RECOVERY = "orphaned_reconciliation_worksite";

    // Runtime State
    private int[] walkTarget;
    private long trackedWalkTargetKey = Long.MIN_VALUE;
    private int walkingTicks;
    private final StationWalkRecovery stationWalkRecovery = new StationWalkRecovery();
    private StationWalkRecovery.Point stationPathTarget;
    private int stationLastPathTick = -20;
    private boolean stationWalkSettingsGuardActive;
    private boolean savedStationAllowPlace;
    private boolean savedStationAllowParkourPlace;
    private int openWaitTicks;
    private int containerCacheReadyTicks;
    private int openInteractionAttempts;
    private int lastOpenInteractionTick = -1;
    private int actionSlotIndex;
    private int actionCooldown;
    private int shulkerFillMovedUnits;
    private int zeroFillCycles;
    private boolean packedAtMaximumCapacity;
    private boolean packingLaneExhausted;
    private final Set<ContainerCapacitySnapshot> rejectedPackingShulkers = new HashSet<>();
    private final ImportCapacityCache importCapacityCache = new ImportCapacityCache();
    private int importCapacityMisses;
    private final PackingSupplySearch packingSupplySearch = new PackingSupplySearch();
    private boolean packingCargoAbsenceConfirmed;
    private final InventoryRecoveryGuard inventoryRecoveryGuard = new InventoryRecoveryGuard();
    private final PackingSourceSelector packingSourceSelector = new PackingSourceSelector();
    private final Set<MoveTask> supplyDeferredTasks = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int MAX_SUPPLY_STAGES_WITHOUT_DELIVERY = 3;
    private final Map<String, Integer> supplyStagesWithoutDelivery = new LinkedHashMap<>();
    private final MixedStagingLedger supplyStagingLedger = new MixedStagingLedger();
    private boolean stagingForPackingSupply;
    private boolean recoverOnboardMixedAfterPackedDelivery;
    private boolean fetchedPackingShulker;
    private boolean packingHeadroomTransferPending;
    private final Map<String, Integer> destinationOpenFailures = new HashMap<>();
    private final SourceTaskRetryTracker<MoveTask> sourceTaskFailures =
            new SourceTaskRetryTracker<>();
    private final Set<Long> managedSourceContainerKeys = new HashSet<>();
    // Counts successful clicks during the current TAKING/DEPOSITING container visit. TAKING
    // resumes scanning from wherever it left off each tick rather than from slot 0, so the
    // final tick of a visit naturally finds "nothing left" once everything matching has already
    // been taken — that's normal completion, not a failure, and should only be reported as a
    // failure if nothing was ever moved during the whole visit.
    private int movedThisVisit;
    private boolean sourceVisitFailed;
    private boolean destinationVisitFailed;
    // Container visits can be retried and reopened. Keep transaction evidence at task scope
    // so a fresh window cannot turn "cargo missing" into a false successful completion.
    private final CargoTransactionLedger taskCargo = new CargoTransactionLedger();
    // Recompute these slots from the live inventory so protection follows kept items after
    // another plugin consumes or moves them during a multi-day job.
    private final Set<Integer> protectedInventorySlots = new TreeSet<>();
    private InventoryKeepPolicy inventoryKeepPolicy = InventoryKeepPolicy.empty();
    private boolean keepProtectionNeedsRefresh;
    private int consolidationSourcesInBatch;
    private final List<int[]> stagingImportDestinations = new ArrayList<>();
    private final Set<Long> packStoreTriedDestinations = new HashSet<>();
    private final Set<Long> emptyShulkerStagingTriedDestinations = new HashSet<>();
    // A live success is useful cadence evidence only for the same cargo class. Rejections stay
    // transaction-local because a chest that rejects an unstackable box may accept another
    // item into a partial stack, and import contents can change during a multi-hour run.
    private final ImportDestinationTracker importDestinationTracker =
            new ImportDestinationTracker();
    private final Set<Long> overflowTriedDestinations = new HashSet<>();
    private final Set<String> stagingStorageClassesPlanned = new TreeSet<>();
    private final Set<String> stagedStorageClasses = new TreeSet<>();
    private int stagedShulkers;
    private int permanentLaneGaps;
    private int packDestinationOpenFailures;
    private int packStoreMatchingShulkersBefore;
    private int packStoreVerificationTicks;
    private int packedShulkerInventorySyncRecoveries;
    private String stagingReason;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    private int totalTasks;
    private int completedTasks;
    private int generatedLooseTasks;
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
    private InputRequestFuture shulkerPlaceFuture;
    private PathingRequestFuture shulkerBreakFuture;
    private final BlockBreakAttemptGate shulkerBreakAttemptGate = new BlockBreakAttemptGate();
    private float savedYaw, savedPitch;
    private int shulkerTicks;
    private int shulkerPlaceRetries;
    private int shulkerPlaceRetryDelayTicks;
    private int shulkerPlaceTargetCursor;
    private int[] shulkerPlaceSupportPos;
    private String shulkerPlaceSupportFace;
    private int shulkerPlaceTargetOrdinal = -1;
    private int shulkerPlaceTargetCount;
    private boolean shulkerPlaceDispatchLogged;
    private boolean shulkerPlaceFinalGrace;
    private int shulkerInventoryCountBeforePlacement;
    private int compatibleShulkerCountBeforePlacement;
    private int selectedShulkerHotbarRawSlot = -1;
    private String selectedShulkerRoutingKey;
    private int shulkerRecoveryBreakAttempts;
    private volatile boolean temporaryShulkerOutstanding;
    private volatile boolean temporaryShulkerPickupConfirmed;
    private volatile String temporaryShulkerPickupFingerprint;
    private volatile String temporaryShulkerPickupStorageKey;
    private volatile ItemStack temporaryShulkerPickupStack;
    private boolean stopAfterShulkerRecovery;
    private String shulkerRecoveryTrigger;
    private static final String PACKED_SHULKER_INVENTORY_SYNC_RECOVERY =
            "packed_shulker_inventory_sync";
    private int shulkerPickupSweepAttempt;
    private int shulkerPickupLastPathTick = -1;
    private int[] shulkerPickupLastTarget;
    static final int MIXED_SHELL_VERIFY_TIMEOUT_TICKS = 200;
    private boolean mixedShellVerificationActive;
    private int mixedShellVerificationTicks;
    private List<MoveTask> mixedShellVerificationCargo = List.of();

    // A mixed box is unloaded into explicitly registered import storage in bounded batches.
    // Cargo slots are exact empty slots chosen by the organizer, so keep-list items are never
    // mistaken for returned-kit cargo even when they share the same item id.
    private boolean mixedDecompositionMode;
    private boolean mixedBoxDrained;
    private String mixedStagingCargoKey;
    private String mixedStagingCargoItemId;
    private int decomposedMixedShulkers;
    private int mixedPendingSourceSlot = -1;
    private int mixedPendingCargoSlot = -1;
    private final Set<Integer> mixedCargoSlots = new TreeSet<>();
    private final List<int[]> mixedStagingUsedDestinations = new ArrayList<>();
    private final MixedStagingLedger mixedStagingLedger = new MixedStagingLedger();
    private final Set<Long> mixedUnavailableStagingDestinations = new HashSet<>();

    // Cooperative task handoff state. The organizer keeps its queues and cargo checkpoint but
    // releases Zenith's shared automation until the module's continuance gate allows a resume.
    private volatile State yieldedFromState;
    private volatile String yieldReason;
    private PathingRequestFuture ownedBaritoneRequest;
    private OwnedBaritoneProcess ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
    private RequestFuture ownedInventoryRequest;
    private PendingQuickMove pendingQuickMove;
    private PendingOverflowSplit pendingOverflowSplit;
    private String quickMoveFailureKey;
    private int quickMoveFailureAttempts;
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
    public boolean isActive() { return state != State.IDLE && state != State.DONE && state != State.FAILED; }
    public boolean isFailed() { return state == State.FAILED; }
    public String getLastFailureReason() { return lastFailure == null ? null : lastFailure.reason(); }
    public String getLastFailureState() { return lastFailure == null ? null : lastFailure.state(); }
    public long getLastFailureTimestamp() { return lastFailure == null ? 0 : lastFailure.timestamp(); }
    public int getTotalTasks() { return totalTasks; }
    public int getCompletedTasks() { return completedTasks; }
    public int getStagedShulkers() { return stagedShulkers; }
    public int getStagingStorageClassCount() { return stagingStorageClassesPlanned.size(); }
    public int getPermanentLaneGaps() { return permanentLaneGaps; }
    public int getDecomposedMixedShulkers() { return decomposedMixedShulkers; }
    public int getGeneratedLooseTasks() { return generatedLooseTasks; }
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

    /** Give an explicit operator-requested resume one new bounded station trip. */
    public boolean prepareManualCheckpointResume() {
        if (!isYielded() || !isStationWalkState(yieldedFromState)) return true;
        StationWalkRecovery.Snapshot exhausted = stationWalkRecovery.snapshot();
        if (exhausted == null) return true;

        stationWalkRecovery.reset();
        stationPathTarget = null;
        stationLastPathTick = -20;
        if (!persistDurableCheckpoint(yieldedFromState)) {
            stationWalkRecovery.restore(exhausted);
            return false;
        }
        emit("organize_station_walk_manual_rearmed", Map.of(
                "reason", "explicit_checkpoint_resume",
                "previous_elapsed_ticks", exhausted.elapsedTicks(),
                "previous_idle_ticks", exhausted.idleTicks(),
                "previous_detour_attempts", exhausted.attempts(),
                "disposition", "fresh_bounded_station_trip"
        ));
        return true;
    }

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
        if (interrupted == State.IDLE || interrupted == State.YIELDED || interrupted == State.DONE
                || interrupted == State.FAILED) {
            durableRecoveryError = "saved organizer checkpoint does not contain a resumable state";
            emit("organize_checkpoint_invalid", Map.of(
                    "reason", "state_not_resumable",
                    "message", durableRecoveryError
            ));
            return DurableRestoreResult.INVALID;
        }

        taskQueue.clear();
        consolidationQueue.clear();
        supplyDeferredTasks.clear();
        journalTaskIds.clear();
        journalTasks.clear();
        int highestTaskId = 0;
        for (OrganizerJournalStore.TaskSnapshot task : plan.tasks()) {
            MoveTask move = new MoveTask(
                    copyPos(task.source()), copyPos(task.destination()), task.itemId(),
                    task.shulkerContentFilter(), task.alreadyInInventory(),
                    task.mixedDecomposition(), task.mixedBatchConsolidation(),
                    task.mixedContents(), task.mandatoryInventoryDeposit());
            journalTasks.put(task.id(), move);
            journalTaskIds.put(move, task.id());
            highestTaskId = Math.max(highestTaskId, task.id());
        }
        try {
            currentTask = taskForJournalId(checkpoint.currentTaskId());
            mixedShellVerificationCargo = checkpoint.mixedShellVerification() == null
                    ? List.of() : checkpoint.mixedShellVerification().cargoTaskIds().stream()
                            .map(this::requiredJournalTask).toList();
            for (Integer id : checkpoint.taskQueue()) taskQueue.addLast(requiredJournalTask(id));
            for (Integer id : checkpoint.consolidationQueue()) {
                consolidationQueue.addLast(requiredJournalTask(id));
            }
            var supply = checkpoint.packingSupply();
            if (supply != null && supply.deferredTaskIds() != null) {
                for (Integer id : supply.deferredTaskIds()) supplyDeferredTasks.add(requiredJournalTask(id));
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
        taskCargo.restore(checkpoint.taskCargoAcquired(), checkpoint.taskCargoDeposited());
        totalTasks = checkpoint.totalTasks();
        completedTasks = checkpoint.completedTasks();
        nextProgressMilestone = checkpoint.nextProgressMilestone() >= ProgressMilestones.FIRST
                ? checkpoint.nextProgressMilestone()
                : ProgressMilestones.nextAfterCompleted(completedTasks, totalTasks);
        reconciliationStation = copyNullablePos(checkpoint.reconciliationStation());
        reconciliationWorksite = copyNullablePos(checkpoint.reconciliationWorksite());
        packItemId = checkpoint.packItemId();
        packDestination = copyNullablePos(checkpoint.packDestination());
        var packingProgress = checkpoint.packingProgress();
        shulkerFillMovedUnits = packingProgress == null ? -1 : packingProgress.movedUnits();
        zeroFillCycles = packingProgress == null ? 0 : packingProgress.zeroFillCycles();
        packedAtMaximumCapacity = packingProgress != null && packingProgress.atMaximumCapacity();
        packingLaneExhausted = packingProgress != null && packingProgress.laneExhausted();
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
        generatedLooseTasks = checkpoint.generatedLooseTasks();
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
        stopAfterShulkerRecovery = checkpoint.stopAfterShulkerRecovery();
        mixedStagingLedger.restore(checkpoint.mixedStagingLedger());
        shulkerRecoveryTrigger = checkpoint.shulkerRecoveryTrigger();
        mixedUnavailableStagingDestinations.clear();
        protectedInventorySlots.clear();
        if (checkpoint.protectedInventorySlots() != null) {
            protectedInventorySlots.addAll(checkpoint.protectedInventorySlots());
        }
        // Slot numbers are only a snapshot. Rebuild from current inventory before resuming.
        keepProtectionNeedsRefresh = true;

        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        var supply = checkpoint.packingSupply();
        packingSupplySearch.restore(supply == null ? null : supply.search());
        supplyStagesWithoutDelivery.clear();
        if (supply != null && supply.stagesWithoutDelivery() != null) {
            supplyStagesWithoutDelivery.putAll(supply.stagesWithoutDelivery());
        }
        inventoryRecoveryGuard.restore(checkpoint.inventoryRecovery());
        stationWalkRecovery.restore(checkpoint.stationWalkRecovery());
        var shellVerification = checkpoint.mixedShellVerification();
        mixedShellVerificationActive = shellVerification != null;
        mixedShellVerificationTicks = shellVerification == null ? 0
                : Math.clamp(shellVerification.elapsedTicks(), 0, MIXED_SHELL_VERIFY_TIMEOUT_TICKS);
        var pickupEvidence = checkpoint.shulkerPickupEvidence();
        temporaryShulkerPickupConfirmed = pickupEvidence != null
                ? pickupEvidence.collectionConfirmed()
                : shellVerification != null && shellVerification.collectionConfirmed();
        temporaryShulkerPickupFingerprint = pickupEvidence != null
                ? pickupEvidence.pickupFingerprint()
                : shellVerification == null ? null : shellVerification.pickupFingerprint();
        temporaryShulkerPickupStorageKey = pickupEvidence == null
                ? null : pickupEvidence.pickupStorageKey();
        packedShulkerInventorySyncRecoveries = pickupEvidence == null ? 0
                : pickupEvidence.inventorySyncRecoveries();
        stationPathTarget = null;
        stationLastPathTick = -20;
        stagingForPackingSupply = supply != null && supply.stagingCargo();
        supplyStagingLedger.restore(supply == null ? null : supply.stagingLedger());
        packStoreTriedDestinations.clear();
        if (checkpoint.packedImportTriedDestinationKeys() != null) {
            packStoreTriedDestinations.addAll(checkpoint.packedImportTriedDestinationKeys());
        }
        emptyShulkerStagingTriedDestinations.clear();
        importDestinationTracker.reset();
        importCapacityCache.reset();
        packingSourceSelector.reset();
        importCapacityMisses = 0;
        rejectedPackingShulkers.clear();
        overflowTriedDestinations.clear();
        currentRole = restoredRole;
        walkTarget = null;
        trackedWalkTargetKey = Long.MIN_VALUE;
        walkingTicks = 0;
        openWaitTicks = 0;
        containerCacheReadyTicks = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
        actionSlotIndex = 0;
        actionCooldown = 0;
        containerDataReceived = false;
        openContainerId = -1;
        ownedBaritoneRequest = null;
        ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
        ownedInventoryRequest = null;
        pendingQuickMove = null;
        pendingOverflowSplit = null;
        quickMoveFailureKey = null;
        quickMoveFailureAttempts = 0;
        yieldedFromState = interrupted;
        yieldReason = "process_restart";
        lateOpenQuarantineTicks = 0;
        durableRecoveryLoaded = true;
        durableRecoveryError = null;
        durableCheckpointUpdatedAtEpochMilli = checkpoint.updatedAtEpochMilli();
        lastFailure = checkpoint.lastFailure();
        openIndexedContainer = null;
        state = State.YIELDED;
        normalizeSupplyDeferredTasks();

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
        // Discard abandons the transaction itself, not just its journal files. Leaving these
        // flags armed made the next fresh organize start fail with
        // temporary_shulker_recovery_required until the proxy restarted. A physical box at
        // the fixed worksite is still discovered and recovered by the next job.
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        yieldedFromState = null;
        yieldReason = null;
        durableRecoveryLoaded = false;
        durableRecoveryError = null;
        durableCheckpointUpdatedAtEpochMilli = 0L;
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

        // A partial keep-list handoff briefly uses the cursor to separate organizer cargo
        // from protected items. Finish and verify that short boundary before releasing the
        // inventory window to another task.
        if (pendingOverflowSplit != null) return false;

        // A mined shulker item despawns on the ground. Finish this short pickup boundary before
        // yielding to another plugin; placed boxes and inventory cargo are safe to checkpoint.
        if (("shared_automation".equals(reason) || "food_contingency".equals(reason))
                && temporaryShulkerOutstanding
                && !isShulkerAtPosition(shulkerPlacePos)
                && !hasPackedShulkerInInventory()) {
            // The foreign request already owns shared automation. Leave it intact and freeze
            // this pickup timeout until that request releases control; then recover the drop
            // before returning to ordinary organizer work.
            clearOwnedAutomation();
            state = State.SHULKER_RECOVERY_PICKUP;
            shulkerTicks = 0;
            resetShulkerPickupSweep();
            return false;
        }

        yieldedFromState = state;
        yieldReason = reason == null ? "shared_automation" : reason;
        keepProtectionNeedsRefresh = true;
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
        markFailed(Objects.toString(reason, "continuance_aborted"), interrupted);
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
        Map<String, Long> looseStackSlotsByClass = new LinkedHashMap<>();
        List<Map<String, Integer>> mixedContentsForDemand = new ArrayList<>();
        Map<String, List<LaneStorageCapacity.ShulkerStock>> bulkShulkerCountsByClass = new LinkedHashMap<>();
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
                                .add(new LaneStorageCapacity.ShulkerStock(itemCount,
                                        observedStackSlots(detail.stackSlots(), classification.storageKey(),
                                                itemCount, 27)));
                    }
                    case EMPTY -> {
                        emptyShulkers++;
                        protectedContainers.add(containerKey);
                    }
                    case MIXED -> {
                        mixedShulkers++;
                        mixedContentsForDemand.add(classification.contents());
                        classification.contents().forEach((itemId, quantity) ->
                                looseStackSlotsByClass.merge(itemId,
                                        (long) observedStackSlots(detail.stackSlots(), itemId,
                                                quantity, 27), Long::sum));
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
                looseStackSlotsByClass.merge(storageClass,
                        (long) observedStackSlots(container.directStackSlots(), itemId,
                                quantity, Integer.MAX_VALUE), Long::sum);
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
                .map(storageClass -> LaneStorageCapacity.Demand.calculateWithObservedSlots(
                        storageClass,
                        looseItemsByClass.getOrDefault(storageClass, 0L),
                        looseStackSlotsByClass.getOrDefault(storageClass, 0L),
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
            TemporaryShulkerRecoveryStatus.Assessment recovery = temporaryShulkerRecoveryStatus();
            if (!recovery.blockPresent() && recovery.inventoryRecovered()) {
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
        emptyShulkerStagingTriedDestinations.clear();
        importDestinationTracker.reset();
        importCapacityCache.reset();
        importCapacityMisses = 0;
        packingSourceSelector.reset();
        rejectedPackingShulkers.clear();
        overflowTriedDestinations.clear();
        stagingStorageClassesPlanned.clear();
        stagedStorageClasses.clear();
        stagedShulkers = 0;
        decomposedMixedShulkers = 0;
        generatedLooseTasks = 0;
        permanentLaneGaps = 0;
        packDestinationOpenFailures = 0;
        packStoreMatchingShulkersBefore = 0;
        packStoreVerificationTicks = 0;
        packedShulkerInventorySyncRecoveries = 0;
        stagingReason = null;
        currentTask = null;
        taskCargo.reset(0);
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
        containerCacheReadyTicks = 0;
        openInteractionAttempts = 0;
        clearPendingQuickMove();
        pendingOverflowSplit = null;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        yieldedFromState = null;
        yieldReason = null;
        lateOpenQuarantineTicks = 0;

        lastFailure = null;
        openIndexedContainer = null;
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
        finishStop(null);
    }

    private void finishStop(String recoveredFailureReason) {
        BARITONE.stop();
        clearOwnedAutomation();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        if (recoveredFailureReason == null) {
            state = State.IDLE;
            lastFailure = null;
        } else {
            markFailed(recoveredFailureReason, state);
        }
        taskQueue.clear();
        consolidationQueue.clear();
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        currentTask = null;
        taskCargo.reset(0);
        clearMixedDecompositionState();
        protectedInventorySlots.clear();
        inventoryKeepPolicy = InventoryKeepPolicy.empty();
        keepProtectionNeedsRefresh = false;
        overflowItems.clear();
        destinationOpenFailures.clear();
        sourceTaskFailures.clear();
        managedSourceContainerKeys.clear();
        stagingImportDestinations.clear();
        packStoreTriedDestinations.clear();
        emptyShulkerStagingTriedDestinations.clear();
        importDestinationTracker.reset();
        importCapacityCache.reset();
        importCapacityMisses = 0;
        packingSourceSelector.reset();
        rejectedPackingShulkers.clear();
        overflowTriedDestinations.clear();
        stagingStorageClassesPlanned.clear();
        stagedStorageClasses.clear();
        stagedShulkers = 0;
        decomposedMixedShulkers = 0;
        generatedLooseTasks = 0;
        permanentLaneGaps = 0;
        packDestinationOpenFailures = 0;
        packStoreMatchingShulkersBefore = 0;
        packStoreVerificationTicks = 0;
        packedShulkerInventorySyncRecoveries = 0;
        stagingReason = null;
        columnAssignment.clear();
        yieldedFromState = null;
        yieldReason = null;
        lateOpenQuarantineTicks = 0;
        clearDurableJournal();
        if (recoveredFailureReason == null) {
            emit("organize_stopped", Map.of("reason", "manual_stop"));
            info("Organizer stopped.");
        } else {
            emit("organize_failed", Map.of(
                    "reason", recoveredFailureReason,
                    "terminal", true,
                    "cargo_preserved", true,
                    "checkpoint_preserved", false,
                    "temporary_shulker_recovered", true
            ));
            info("Organizer stopped after recovering the temporary shulker.");
        }
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
        this.containerCacheReadyTicks = 0;
        this.containerDataReceived = true;
        // Bind the observation to this window, not a walk target that may change before close.
        this.openIndexedContainer = state == State.SHULKER_OPENING || walkTarget == null
                ? null : index.get(walkTarget[0], walkTarget[1], walkTarget[2]);
    }

    /** Record the server's authoritative collection of the temporary shulker drop. */
    public void onItemCollected(ClientboundTakeItemEntityPacket packet) {
        if (!temporaryShulkerOutstanding
                || shulkerPlacePos == null
                || packet.getItemCount() <= 0
                || packet.getCollectorEntityId() != CACHE.getPlayerCache().getEntityId()
                || !isTemporaryShulkerPickupState(state)) {
            return;
        }

        // This handler runs before Zenith removes the collected entity from its cache.
        var entity = CACHE.getEntityCache().get(packet.getCollectedEntityId());
        if (entity == null || entity.getEntityType() != EntityType.ITEM) return;
        // MCProtocolLib renamed the item metadata type after 1.21.4. Reading the cached value
        // keeps this listener source-compatible across every StashManager target.
        var itemMetadata = entity.getMetadata().get(8);
        if (itemMetadata == null || !(itemMetadata.getValue() instanceof ItemStack collected)
                || collected.getAmount() <= 0
                || !isShulkerBoxItem(itemIdFromStack(collected))) {
            return;
        }

        double dx = entity.getX() - (shulkerPlacePos[0] + 0.5);
        double dy = entity.getY() - (shulkerPlacePos[1] + 0.5);
        double dz = entity.getZ() - (shulkerPlacePos[2] + 0.5);
        if (dx * dx + dy * dy + dz * dz > SHULKER_PICKUP_EVIDENCE_RADIUS_SQ) return;

        String collectedItemId = itemIdFromStack(collected);
        if (mixedDecompositionMode
                && (currentTask == null || !currentTask.itemId().equals(collectedItemId))) {
            return;
        }
        ShulkerClassification collectedShape = ShulkerClassification.classify(
                ItemIdentifier.readShulkerContents(collected));
        temporaryShulkerPickupStack = collected;
        temporaryShulkerPickupFingerprint = collectedShape.fingerprint();
        temporaryShulkerPickupStorageKey = collectedShape.storageKey();
        if (!temporaryShulkerPickupConfirmed) {
            temporaryShulkerPickupConfirmed = true;
            emit("organize_shulker_pickup_confirmed", Map.of(
                    "evidence", "take_item_entity",
                    "item_id", collectedItemId,
                    "item_count", packet.getItemCount()
            ));
        }
    }

    private static boolean isTemporaryShulkerPickupState(State state) {
        return switch (state) {
            case SHULKER_BREAKING, SHULKER_PICKUP,
                 SHULKER_RECOVERY_BREAKING, SHULKER_RECOVERY_PICKUP -> true;
            default -> false;
        };
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
        if (!isActive()) return;

        if (!keepProtectionNeedsRefresh) {
            refreshProtectedInventorySlots();
        }

        setBaritoneBreakingAllowed(state == State.SHULKER_BREAKING
                || state == State.SHULKER_RECOVERY_BREAKING);
        // Exact placement uses a standing-pose raycast. The worksite policy already rejects
        // interactive supports, so sneaking here only makes the calculated aim disagree with
        // the pose used when Zenith executes the click.
        setPlaceBlockSneak(false);

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
            case MIXED_SHELL_VERIFY  -> tickMixedShellVerification();
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
            markFailed("no_containers_in_region", State.PLANNING);
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

        int excludedScannedInventoriesWithContents = (int) planningContainers.stream()
                .filter(container -> !OrganizerOwnershipPolicy.isManagedSource(
                        posToColumn.containsKey(posKey(container.x(), container.y(), container.z())),
                        index.isImportChest(container)))
                .filter(container -> !container.items().isEmpty())
                .count();
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
            markFailed("double_chest_footprint_requires_fresh_scan", State.PLANNING);
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
                .sorted(Comparator
                        .comparingInt(ImportStagingPolicy.Candidate::estimatedFreeSlots)
                        .reversed()
                        .thenComparingInt(ImportStagingPolicy.Candidate::x)
                        .thenComparingInt(ImportStagingPolicy.Candidate::y)
                        .thenComparingInt(ImportStagingPolicy.Candidate::z))
                .map(ImportStagingPolicy.Candidate::position)
                .forEach(stagingImportDestinations::add);
        managedSourceContainerKeys.clear();
        planningContainers.forEach(container -> managedSourceContainerKeys.add(
                posKey(container.x(), container.y(), container.z())));

        // Step 2: Map items to locations (accessible items only)
        Map<String, List<ItemLocation>> itemLocations = new LinkedHashMap<>();
        Map<String, Long> looseStackSlotsByClass = new LinkedHashMap<>();

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
                looseStackSlotsByClass.merge(StorageClassPolicy.exact(item.getKey()),
                        (long) observedStackSlots(container.directStackSlots(), item.getKey(),
                                item.getValue(), Integer.MAX_VALUE), Long::sum);
            }
        }

        // Step 2b: Classify each physical shulker. Only exact single-variant boxes are bulk
        // inventory. Empty boxes are packing supplies; mixed boxes are preserved for future
        // explicit kit classification and must never be guessed from a majority item.
        record ShulkerLoc(int[] pos, int slot, String shulkerType, String storageKey,
                          String fingerprint, int contentWeight, int occupiedSlots) {}
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
                        classification.contents().forEach((itemId, quantity) ->
                                looseStackSlotsByClass.merge(itemId,
                                        (long) observedStackSlots(sd.stackSlots(), itemId,
                                                quantity, 27), Long::sum));
                        containersWithProtectedShulkers.add(posKey(pos[0], pos[1], pos[2]));
                    }
                    case BULK -> {
                        int contentWeight = classification.contents().values().stream()
                                .mapToInt(Integer::intValue).sum();
                        shulkersByContent.computeIfAbsent(classification.storageKey(), k -> new ArrayList<>())
                                .add(new ShulkerLoc(pos, sd.slot(), sd.color(), classification.storageKey(),
                                        classification.fingerprint(), contentWeight,
                                        observedStackSlots(sd.stackSlots(), classification.storageKey(),
                                                contentWeight, 27)));
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
            List<LaneStorageCapacity.ShulkerStock> existingCounts = shulkersByContent
                    .getOrDefault(storageClass, List.of()).stream()
                    .map(shulker -> new LaneStorageCapacity.ShulkerStock(
                            shulker.contentWeight(), shulker.occupiedSlots()))
                    .toList();
            storageDemands.add(LaneStorageCapacity.Demand.calculateWithObservedSlots(
                    storageClass, looseItems,
                    looseStackSlotsByClass.getOrDefault(storageClass, 0L), existingCounts));
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
            markFailed(blockedReason, State.PLANNING);
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
                // Contents and the recovered empty shell both need a staging slot. Without
                // shell headroom a full inventory can finish separation but cannot free the
                // slot needed to process the staged loose-item phase.
                int minimumSlots = MixedShulkerPlaybook.minimumStagingSlotsWithShell(mixed.contents());
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
            int belowThresholdClasses = (int) itemLocations.values().stream()
                    .filter(locations -> locations.stream().mapToLong(ItemLocation::quantity).sum()
                            < config.condenseMinItems)
                    .count();
            emit("organize_no_owned_work", Map.of(
                    "managed_import_inventories", importContainers.size(),
                    "registered_import_blocks", index.getImportChestBlockCount(),
                    "excluded_scanned_inventories_with_contents", excludedScannedInventoriesWithContents,
                    "below_condense_threshold_classes", belowThresholdClasses,
                    "condense_min_items", config.condenseMinItems));
            if (permanentLaneGaps > 0) {
                info("No reconciliation moves are needed, but " + permanentLaneGaps
                        + " bulk class(es) still need a suitable permanent lane.");
            } else {
                info("No organize tasks were found in the scanned lanes and registered imports. "
                        + "Check that remaining cargo is inside the scanned region and its chest is "
                        + "registered as an import; " + excludedScannedInventoriesWithContents
                        + " scanned inventory/inventories with contents were outside organizer ownership.");
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
            markFailed("organizer_checkpoint_write_failed", State.PLANNING);
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
                .filter(task -> task.alreadyInInventory() && !task.mixedDecomposition())
                .collect(Collectors.toMap(this::inventoryTaskKey, task -> task, (a, b) -> a,
                        LinkedHashMap::new));
        consolidationQueue.stream()
                .filter(task -> task.alreadyInInventory() && !task.mixedDecomposition())
                .forEach(task -> alreadyQueued.putIfAbsent(inventoryTaskKey(task), task));
        if (currentTask != null && currentTask.alreadyInInventory()
                && !currentTask.mixedDecomposition()) {
            alreadyQueued.putIfAbsent(inventoryTaskKey(currentTask), currentTask);
        }
        List<MixedInventoryRecoveryPlanner.Cargo> mixedInventoryCargo = new ArrayList<>();
        List<MixedInventoryRecoveryPlanner.Cargo> scheduledMixedCargo = new ArrayList<>();
        addScheduledMixedInventoryCargo(scheduledMixedCargo, currentTask);
        taskQueue.forEach(task -> addScheduledMixedInventoryCargo(scheduledMixedCargo, task));
        consolidationQueue.forEach(task -> addScheduledMixedInventoryCargo(scheduledMixedCargo, task));
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
                if (classification.kind() == ShulkerClassification.Kind.MIXED) {
                    for (int box = 0; box < stack.getAmount(); box++) {
                        mixedInventoryCargo.add(new MixedInventoryRecoveryPlanner.Cargo(
                                itemId, classification.fingerprint(), classification.contents()));
                    }
                    continue;
                }
                if (classification.kind() == ShulkerClassification.Kind.EMPTY) {
                    int[] staging = findOverflowChest();
                    if (staging == null) {
                        skippedNoColumn++;
                        continue;
                    }
                    MoveTask task = new MoveTask(
                            currentPos, staging, itemId, EMPTY_SHULKER_STAGING_FILTER, true);
                    if (prioritize) task = task.requireInventoryDeposit();
                    String key = inventoryTaskKey(task);
                    MoveTask existing = alreadyQueued.get(key);
                    if (existing != null) {
                        if (prioritize) {
                            existing = requireInventoryDeposit(existing);
                            alreadyQueued.put(key, existing);
                        }
                        if (prioritize && existing != currentTask) {
                            inventoryTasks.putIfAbsent(key, existing);
                        }
                    } else if (inventoryTasks.putIfAbsent(key, task) == null) {
                        newlyQueued.add(key);
                    }
                    continue;
                }
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
                    if (prioritize && existing != currentTask) {
                        inventoryTasks.putIfAbsent(key, existing);
                    }
                } else if (inventoryTasks.putIfAbsent(key, task) == null) {
                    newlyQueued.add(key);
                }
                continue;
            }
            MoveTask task = new MoveTask(currentPos, col.top(), itemId, contentFilter, true);
            String key = inventoryTaskKey(task);
            MoveTask existing = alreadyQueued.get(key);
            if (existing != null) {
                if (prioritize && existing != currentTask) {
                    inventoryTasks.putIfAbsent(key, existing);
                }
            } else if (inventoryTasks.putIfAbsent(key, task) == null) {
                newlyQueued.add(key);
            }
        }

        List<MoveTask> mixedRecoveryTasks = new ArrayList<>();
        int skippedMixedNoStaging = 0;
        for (MixedInventoryRecoveryPlanner.Cargo cargo : MixedInventoryRecoveryPlanner.uncovered(
                mixedInventoryCargo, scheduledMixedCargo)) {
            int[] staging = chooseMixedInventoryStaging(cargo.contents());
            if (staging == null) {
                skippedMixedNoStaging++;
                continue;
            }
            mixedRecoveryTasks.add(MoveTask.mixedInInventory(
                    currentPos, staging, cargo.itemId(), cargo.fingerprint(), cargo.contents()));
        }
        List<MoveTask> existingMixedRecoveryTasks = prioritize
                ? queuedMixedRecoveryTasksPresentInInventory(mixedInventoryCargo)
                : List.of();

        if (!inventoryTasks.isEmpty() || !mixedRecoveryTasks.isEmpty()
                || !existingMixedRecoveryTasks.isEmpty()) {
            // First evacuate ordinary inventory cargo and recovered empty shells. Those tasks
            // create the slots needed to safely place and decompose the remaining mixed boxes.
            List<MoveTask> uniqueTasks = MixedShulkerPlaybook.inventoryRecoveryOrder(
                    inventoryTasks.values(), existingMixedRecoveryTasks, mixedRecoveryTasks);
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
                    if (task.mixedDecomposition() || isShulkerBoxItem(task.itemId())) {
                        taskQueue.addLast(task);
                    } else {
                        consolidationQueue.addLast(task);
                    }
                }
            }
            int queued = inventoryTasks.size() + mixedRecoveryTasks.size()
                    + existingMixedRecoveryTasks.size();
            info("Queued " + queued + " recovery task(s) for cargo already in the bot inventory for "
                    + (prioritize ? "recovery deposit." : "the appropriate organization phase."));
            if (state != State.PLANNING) {
                totalTasks += newlyQueued.size() + mixedRecoveryTasks.size();
            }
        }
        if (skippedNoColumn > 0) {
            info(skippedNoColumn + " inventory item(s) left in inventory — no matching stash column yet.");
        }
        if (skippedMixedNoStaging > 0) {
            info(skippedMixedNoStaging
                    + " mixed shulker(s) left in inventory — no registered import chest is available for safe staging.");
        }
        return !inventoryTasks.isEmpty() || !mixedRecoveryTasks.isEmpty()
                || !existingMixedRecoveryTasks.isEmpty();
    }

    private MoveTask requireInventoryDeposit(MoveTask task) {
        return replaceTaskReferences(task, task.requireInventoryDeposit());
    }

    private MoveTask replaceTaskReferences(MoveTask task, MoveTask required) {
        if (required == task) return task;
        // Upgrade the existing task without duplicating it or changing its journal ID.
        replaceQueuedTask(taskQueue, task, required);
        replaceQueuedTask(consolidationQueue, task, required);
        if (currentTask == task) currentTask = required;
        if (supplyDeferredTasks.remove(task)) supplyDeferredTasks.add(required);
        Integer id = journalTaskIds.remove(task);
        if (id != null) {
            journalTaskIds.put(required, id);
            journalTasks.put(id, required);
            journalPlanDirty = true;
        }
        return required;
    }

    private static void replaceQueuedTask(Deque<MoveTask> queue, MoveTask task, MoveTask replacement) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            MoveTask queued = queue.removeFirst();
            queue.addLast(queued == task ? replacement : queued);
        }
    }

    private static void addScheduledMixedInventoryCargo(
            List<MixedInventoryRecoveryPlanner.Cargo> scheduled,
            MoveTask task) {
        if (task == null || !task.alreadyInInventory() || !task.mixedDecomposition()) return;
        scheduled.add(new MixedInventoryRecoveryPlanner.Cargo(
                task.itemId(), task.shulkerContentFilter(), task.mixedContents()));
    }

    private List<MoveTask> queuedMixedRecoveryTasksPresentInInventory(
            List<MixedInventoryRecoveryPlanner.Cargo> inventoryCargo) {
        List<MixedInventoryRecoveryPlanner.Cargo> unmatched = new ArrayList<>(inventoryCargo);
        if (currentTask != null && currentTask.alreadyInInventory()
                && currentTask.mixedDecomposition()) {
            removeFirstMatchingMixedCargo(unmatched, currentTask);
        }
        List<MoveTask> queued = new ArrayList<>();
        queued.addAll(taskQueue);
        queued.addAll(consolidationQueue);

        List<MoveTask> present = new ArrayList<>();
        for (MoveTask task : queued) {
            if (!task.alreadyInInventory() || !task.mixedDecomposition()) continue;
            if (removeFirstMatchingMixedCargo(unmatched, task)) present.add(task);
        }
        return present;
    }

    private static boolean removeFirstMatchingMixedCargo(
            List<MixedInventoryRecoveryPlanner.Cargo> cargoList,
            MoveTask task) {
        for (int index = 0; index < cargoList.size(); index++) {
            MixedInventoryRecoveryPlanner.Cargo cargo = cargoList.get(index);
            if (task.itemId().equals(cargo.itemId())
                    && Objects.equals(task.shulkerContentFilter(), cargo.fingerprint())) {
                cargoList.remove(index);
                return true;
            }
        }
        return false;
    }

    private int[] chooseMixedInventoryStaging(Map<String, Integer> contents) {
        List<ImportStagingPolicy.Candidate> candidates = index
                .getInRegion(config.pos1, config.pos2)
                .stream()
                .filter(index::isImportChest)
                .map(ImportStagingPolicy::from)
                .toList();
        for (ImportStagingPolicy.Candidate candidate : candidates) {
            rememberStagingImportDestination(candidate.position());
        }
        if (candidates.isEmpty()) {
            return stagingImportDestinations.isEmpty()
                    ? null
                    : copyPos(stagingImportDestinations.get(0));
        }

        int minimumSlots = MixedShulkerPlaybook.minimumStagingSlotsWithShell(contents);
        List<ImportStagingPolicy.Candidate> singleChestFits = candidates.stream()
                .filter(candidate -> candidate.estimatedFreeSlots() >= minimumSlots)
                .toList();
        return ImportStagingPolicy.choose(
                        singleChestFits.isEmpty() ? candidates : singleChestFits, Map.of())
                .map(ImportStagingPolicy.Candidate::position)
                .orElse(null);
    }

    private void rememberStagingImportDestination(int[] destination) {
        long key = posKey(destination[0], destination[1], destination[2]);
        if (stagingImportDestinations.stream().noneMatch(existing ->
                posKey(existing[0], existing[1], existing[2]) == key)) {
            stagingImportDestinations.add(copyPos(destination));
        }
    }

    private String inventoryTaskKey(MoveTask task) {
        // Destination is mutable routing state, not cargo identity. Import failover can change
        // it dozens of times; including it here duplicated an existing packing obligation as a
        // generic deposit task after restart recovery.
        return task.itemId() + "\u0000"
                + Objects.toString(task.shulkerContentFilter(), "");
    }

    private MoveTask matchingInventoryPackingObligation(MoveTask depositTask) {
        if (depositTask == null || depositTask.mixedBatchConsolidation()
                || depositTask.mixedDecomposition()
                || isShulkerBoxItem(depositTask.itemId())) {
            return null;
        }
        String storageClass = StorageClassPolicy.exact(depositTask.itemId());
        if (storageClass == null) return null;
        return consolidationQueue.stream()
                .filter(MoveTask::mixedBatchConsolidation)
                .filter(MoveTask::alreadyInInventory)
                .filter(task -> ItemIdentifier.contentItemIdsMatch(
                        storageClass, task.itemId()))
                .findFirst().orElse(null);
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

        return deduplicateOverlappingColumns(columns, containers);
    }

    /** A physical inventory belongs to exactly one lane, even if two detectors claim it. */
    static List<Column> deduplicateOverlappingColumns(
            Collection<Column> rawColumns,
            Collection<ContainerEntry> containers) {
        if (rawColumns == null || rawColumns.isEmpty()) return List.of();
        Map<Long, ContainerEntry> byPosition = new LinkedHashMap<>();
        if (containers != null) {
            for (ContainerEntry entry : containers) {
                if (entry != null) {
                    byPosition.merge(entry.posKey(), entry, StashOrganizer::freshest);
                }
            }
        }

        List<Column> ordered = sortAndReindex(new ArrayList<>(rawColumns));
        Set<Long> claimedInventories = new HashSet<>();
        List<Column> unique = new ArrayList<>();
        for (Column column : ordered) {
            Set<Long> laneInventories = new LinkedHashSet<>();
            for (int[] position : column.chests()) {
                long positionKey = posKey(position[0], position[1], position[2]);
                ContainerEntry entry = byPosition.get(positionKey);
                laneInventories.add(entry == null
                        ? positionKey
                        : storageIdentityKey(entry));
            }
            if (laneInventories.isEmpty()
                    || laneInventories.stream().anyMatch(claimedInventories::contains)) {
                continue;
            }
            claimedInventories.addAll(laneInventories);
            unique.add(column);
        }
        return sortAndReindex(unique);
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

        return deduplicateOverlappingColumns(columns, containers);
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
            if (currentTask != null
                    && (currentRole == TargetRole.DESTINATION || taskCargo.hasAcquiredCargo())) {
                abortWithCargo("destination_checkpoint_missing_with_cargo",
                        "The current cargo has no destination checkpoint. The job stopped before advancing the queue.");
                return;
            }
            advanceToNextTask();
            return;
        }

        if (isStationWalkState()) {
            tickStationWalking();
            return;
        }

        long targetKey = posKey(walkTarget[0], walkTarget[1], walkTarget[2]);
        if (targetKey != trackedWalkTargetKey) {
            trackedWalkTargetKey = targetKey;
            walkingTicks = 0;
        }
        walkingTicks++;

        if (isAtWalkTargetAccessPosition()) {
            BARITONE.stop();
            onArrived();
            return;
        }

        if (walkingTicks > config.organizerWalkTimeoutTicks) {
            BARITONE.stop();
            trackedWalkTargetKey = Long.MIN_VALUE;
            if (state == State.MIXED_STAGE_WALK) {
                mixedUnavailableStagingDestinations.add(targetKey);
                if (!switchMixedStagingDestination()) {
                    recoverMixedShulkerAndStop("mixed_staging_unreachable",
                            "No registered import chest was reachable; recovering the placed mixed shulker before stopping.");
                }
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
        ownCustomGoal(BARITONE.pathTo(
                new GoalGetToBlock(new BlockPos(walkTarget[0], walkTarget[1], walkTarget[2]))));
    }

    private boolean isStationWalkState() {
        return isStationWalkState(state);
    }

    private static boolean isStationWalkState(State candidate) {
        return candidate == State.SHULKER_STATION_WALK || candidate == State.SHULKER_RESUME_WALK
                || candidate == State.MIXED_RETURN_WALK;
    }

    private StationWalkRecovery.Position stationWalkPosition() {
        var player = CACHE.getPlayerCache();
        return new StationWalkRecovery.Position(player.getX(), player.getY(), player.getZ());
    }

    private void tickStationWalking() {
        guardStationWalkSettings();
        var position = stationWalkPosition();
        var target = new StationWalkRecovery.Point(walkTarget[0], walkTarget[1], walkTarget[2]);
        stationWalkRecovery.begin(target, position);
        if (target.equals(position.block())) {
            if (stationWalkRecovery.attempts() > 0) {
                emit("organize_station_walk_recovered", stationWalkDetails("station_reached", position));
            }
            stopOwnedBaritoneProcess();
            restoreStationWalkSettings();
            stationWalkRecovery.reset();
            onArrived();
            return;
        }

        StationWalkRecovery.Decision decision = stationWalkRecovery.tick(position, config.organizerWalkTimeoutTicks);
        if (decision == StationWalkRecovery.Decision.EXHAUSTED) {
            failStationWalk("walk_recovery_budget_exhausted", position);
            return;
        }

        boolean invalidWaypoint = false;
        if (stationWalkRecovery.waypoint() != null) {
            if (stationWalkRecovery.atWaypoint(position)) {
                stopOwnedBaritoneProcess();
                stationWalkRecovery.finishDetour(position);
                stationPathTarget = null;
                emit("organize_station_walk_detour_completed", stationWalkDetails("retry_station", position));
                if (!persistStationWalk()) return;
                requestStationWalkPath(target);
                return;
            }
            invalidWaypoint = !isSafeStationDetourCell(stationWalkRecovery.waypoint());
            // Recheck the entire short corridor after a pause or process restart.
            if (stationPathTarget == null) {
                invalidWaypoint |= !StationWalkRecovery.safeCorridor(position.block(),
                        stationWalkRecovery.waypoint(), this::isSafeStationDetourCell);
            }
        }
        if (decision == StationWalkRecovery.Decision.RECOVER || invalidWaypoint) {
            String reason = invalidWaypoint ? "detour_no_longer_safe" : "no_walking_progress";
            emit("organize_station_walk_stalled", stationWalkDetails(reason, position));
            if (stationWalkRecovery.attempts() >= StationWalkRecovery.MAX_DETOURS) {
                failStationWalk("walk_recovery_budget_exhausted", position);
                return;
            }
            var detour = stationWalkRecovery.nextDetour(position, this::isSafeStationDetourCell);
            if (detour == null) {
                failStationWalk("no_safe_local_detour", position);
                return;
            }
            stopOwnedBaritoneProcess();
            stationWalkRecovery.detour(detour, position);
            stationPathTarget = null;
            if (!persistStationWalk()) return;
            emit("organize_station_walk_detour", stationWalkDetails(reason, position));
        }

        var next = stationWalkRecovery.waypoint() == null ? target : stationWalkRecovery.waypoint();
        int elapsed = stationWalkRecovery.snapshot().elapsedTicks();
        if (!next.equals(stationPathTarget)
                || (!BARITONE.getCustomGoalProcess().isActive() && elapsed - stationLastPathTick >= 20)) {
            requestStationWalkPath(next);
        }
    }

    private boolean persistStationWalk() {
        if (persistDurableCheckpoint(state)) return true;
        abortWithCargo("station_walk_checkpoint_unavailable",
                "Could not save walking recovery; the current cargo task was stopped in place.");
        return false;
    }

    private void requestStationWalkPath(StationWalkRecovery.Point target) {
        setBaritoneBreakingAllowed(false);
        stationPathTarget = target;
        stationLastPathTick = stationWalkRecovery.snapshot().elapsedTicks();
        ownCustomGoal(BARITONE.pathTo(new GoalBlock(new BlockPos(target.x(), target.y(), target.z()))));
    }

    private boolean isSafeStationDetourCell(StationWalkRecovery.Point cell) {
        int x = cell.x(), y = cell.y(), z = cell.z();
        if (!World.isInWorldBounds(x, y - 1, z) || !World.isInWorldBounds(x, y + 1, z)
                || !World.isChunkLoadedBlockPos(x, z)) return false;
        try {
            if (World.getChunkSection(x, y - 1, z) == null || World.getChunkSection(x, y, z) == null
                    || World.getChunkSection(x, y + 1, z) == null) return false;
            var floor = World.getBlock(x, y - 1, z);
            return BlockCompat.isAir(World.getBlock(x, y, z))
                    && BlockCompat.isAir(World.getBlock(x, y + 1, z))
                    && BlockCompat.isSolid(x, y - 1, z)
                    && !BlockCompat.isInteractable(floor)
                    && StationWalkRecovery.safeFloor(floor.name());
        } catch (RuntimeException ignored) {
            // Missing or inconsistent world data cannot authorize a recovery step.
            return false;
        }
    }

    private Map<String, Object> stationWalkDetails(String reason, StationWalkRecovery.Position position) {
        var progress = stationWalkRecovery.snapshot();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("elapsed_ticks", progress.elapsedTicks());
        details.put("idle_ticks", progress.idleTicks());
        details.put("detour_attempts", progress.attempts());
        details.put("max_detours", StationWalkRecovery.MAX_DETOURS);
        details.put("trip_limit_ticks", StationWalkRecovery.tripLimit(config.organizerWalkTimeoutTicks));
        details.put("distance_to_station", String.format(Locale.ROOT, "%.2f", position.distance(progress.target())));
        details.put("best_distance_to_station", String.format(Locale.ROOT, "%.2f", progress.bestDistance()));
        details.put("path_active", BARITONE.getCustomGoalProcess().isActive());
        details.put("path_request", ownedBaritoneRequest == null ? "none"
                : !ownedBaritoneRequest.isCompleted() ? "pending" : ownedBaritoneRequest.getNow() ? "accepted" : "rejected");
        details.put("cargo_units", taskCargo.remaining());
        if (progress.waypoint() != null) {
            details.put("distance_to_waypoint", String.format(Locale.ROOT, "%.2f", position.distance(progress.waypoint())));
        }
        return details;
    }

    private void failStationWalk(String reason, StationWalkRecovery.Position position) {
        emit("organize_station_walk_recovery_exhausted", stationWalkDetails(reason, position));
        if (state == State.MIXED_RETURN_WALK) {
            recoverMixedShulkerAndStop("mixed_station_return_unreachable",
                    "Could not return to the mixed-shulker worksite after bounded walking recovery.");
        } else {
            abortWithCargo(state == State.SHULKER_RESUME_WALK
                            ? "reconciliation_resume_unreachable_with_cargo"
                            : "reconciliation_station_unreachable_with_cargo",
                    "Could not reach the reconciliation station after safe walking recovery (" + reason
                            + "); cargo and the current task are preserved.");
        }
    }

    private void guardStationWalkSettings() {
        if (!stationWalkSettingsGuardActive) {
            savedStationAllowPlace = CONFIG.client.extra.pathfinder.allowPlace;
            savedStationAllowParkourPlace = CONFIG.client.extra.pathfinder.allowParkourPlace;
            stationWalkSettingsGuardActive = true;
        }
        CONFIG.client.extra.pathfinder.allowPlace = false;
        CONFIG.client.extra.pathfinder.allowParkourPlace = false;
    }

    private void restoreStationWalkSettings() {
        if (stationWalkSettingsGuardActive) {
            CONFIG.client.extra.pathfinder.allowPlace = savedStationAllowPlace;
            CONFIG.client.extra.pathfinder.allowParkourPlace = savedStationAllowParkourPlace;
            stationWalkSettingsGuardActive = false;
        }
        stationPathTarget = null;
        stationLastPathTick = -20;
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
        openContainerId = -1;
        containerCacheReadyTicks = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
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
                    if (temporaryShulkerRecoveryStatus().inventoryRecovered()) {
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
        if (containerDataReceived) {
            if (awaitLiveOpenContainer() == null) {
                if (containerCacheReadyTimedOut()) {
                    failNormalContainerOpen("container_cache_sync_timeout");
                }
                return;
            }
            BARITONE.stop();
            if (currentRole == TargetRole.DESTINATION) {
                destinationOpenFailures.remove(destinationCargoKey());
            }
            actionSlotIndex = 0;
            actionCooldown = 0;
            movedThisVisit = 0;
            sourceVisitFailed = false;
            destinationVisitFailed = false;
            state = (currentRole == TargetRole.SOURCE) ? State.TAKING : State.DEPOSITING;
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            failNormalContainerOpen(currentRole == TargetRole.DESTINATION
                    ? "destination_open_retry"
                    : "source_open_timeout");
            return;
        }

        // A single missed right-click (rotation not settled, brief lag, etc.) should not
        // doom the whole task — retry periodically like tickShulkerOpening does.
        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(walkTarget);
        }
    }

    private void failNormalContainerOpen(String reason) {
        BARITONE.stop();
        if (currentRole == TargetRole.DESTINATION && currentTask != null) {
            retryOrAbortCargoDestination(reason);
        } else {
            retryUntouchedSourceAtTail(reason);
        }
    }

    // TAKING (container → player inventory via shift-click)
    private void tickTaking() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            if (taskCargo.hasAcquiredCargo() || movedThisVisit > 0) {
                // Finish the cargo already acquired before revisiting this source. Reopening
                // the source now would mix two transaction phases in the player inventory.
                if (currentTask != null && currentTask.shulkerContentFilter() == null) {
                    if (consolidationMode) consolidationQueue.addFirst(currentTask);
                    else taskQueue.addLast(currentTask);
                }
                // Consolidation's failure branch packs the batch acquired so far before it
                // revisits the requeued source. A normal move proceeds to its destination.
                sourceVisitFailed = consolidationMode;
                emit("organize_source_visit_interrupted", Map.of(
                        "reason", "source_container_lost_after_take",
                        "disposition", "deposit_then_retry_source"
                ));
                state = State.CLOSING_SOURCE;
                closeCurrentContainer();
                return;
            }
            sourceVisitFailed = true;
            emit("organize_target_failed", Map.of("reason", "source_container_lost"));
            state = State.CLOSING_SOURCE;
            closeCurrentContainer();
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED
                    || transfer.outcome() == QuickMoveOutcome.CONFIRMED_PARTIAL) {
                sourceTaskFailures.recordSuccess(currentTask);
                movedThisVisit++;
                taskCargo.recordAcquired(transfer.movedAmount());
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                actionSlotIndex = Math.max(actionSlotIndex, transfer.slot() + 1);
                if (currentTask != null && currentTask.shulkerContentFilter() != null) {
                    actionSlotIndex = chestSlots;
                }
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }
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

                    if (currentTask.mixedDecomposition()
                            && !admitMixedShulkerTake(stack)) {
                        return;
                    }

                    if (consolidationMode
                            && !canTakeWhilePreservingPackingHeadroom(
                                    open, chestSlots, stack)) {
                        pauseCollectionForPackingHeadroom();
                        return;
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
                        // queueInventoryDepositTasks() only runs once at the start of planning —
                        // if inventory fills up mid-run with nothing further matching whatever's
                        // stuck inside, every remaining task would fail this same way forever.
                        // Re-queue this task for a later retry, then divert to emptying out
                        // whatever's actually in inventory right now before continuing.
                        boolean interruptedConsolidation = consolidationMode;
                        if (currentTask != null) {
                            if (interruptedConsolidation) consolidationQueue.addFirst(currentTask);
                            else taskQueue.addFirst(currentTask);
                        }
                        if (!queueInventoryDepositTasks(true)) {
                            abortWithCargo("inventory_full_no_recovery_destination",
                                    "Inventory is full and no safe recovery deposit can be scheduled.");
                            return;
                        }
                        emit("organize_inventory_recovery_started", Map.of(
                                "reason", "inventory_full_before_take",
                                "interrupted_phase", interruptedConsolidation
                                        ? "loose_reconciliation" : "container_move"
                        ));
                        // Leave the final loose-item phase while recovery work frees real slots.
                        // Re-enter it only after the ordinary task queue is empty again.
                        if (interruptedConsolidation) {
                            consolidationMode = false;
                            mixedBatchConsolidationMode = false;
                        }
                        advanceToNextTask();
                        return;
                    }

                    submitQuickMove(actionSlotIndex);
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
                if (consolidationMode && currentTask != null && hasCollectedPackingCargo()) {
                    emit("organize_partial_batch_packing", Map.of(
                            "completed_sources", consolidationSourcesInBatch,
                            "moves_this_visit", movedThisVisit,
                            "cargo_units", taskCargo.remaining()));
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
            retryOrAbortCargoDestination("destination_container_lost");
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED
                    || transfer.outcome() == QuickMoveOutcome.CONFIRMED_PARTIAL) {
                movedThisVisit++;
                taskCargo.recordDeposited(transfer.movedAmount());
                if (isImportStagingMoveTask(currentTask)) {
                    recordWritableImportDestination(
                            currentTask.destination(), currentTaskCargoRoutingKey());
                }
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                actionSlotIndex++;
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }

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
                        if (!matchesShulkerTaskFilter(currentTask, classification)) {
                            playerSlot++;
                            continue;
                        }
                    }

                    // Admission is cargo-specific. A container with no empty slots may still
                    // accept a partial stack, while an "empty" slot estimate from the scan may
                    // already be stale after earlier reconciliation transactions.
                    ContainerAdmission admission = inspectContainerAdmission(
                            open, chestSlots, stack);
                    if (!admission.canAccept()) {
                        // Chest full — try cascading to next chest in column
                        closeCurrentContainer();
                        boolean importDestination = isImportStagingMoveTask(currentTask);
                        if (!importDestination && cascadeToNextInColumn()) return;

                        CargoDestinationPolicy.FullDestinationAction action =
                                CargoDestinationPolicy.afterPermanentCascadeExhausted(
                                        importDestination,
                                        isShulkerBoxItem(currentTask.itemId()));
                        switch (action) {
                            case TRY_ALTERNATE_IMPORT -> {
                                if (switchImportStagingMoveDestination(
                                        admission, cargoRoutingKey(stack), itemIdFromStack(stack))) return;
                                if (isPackedImportHandoffTask(currentTask)
                                        && deferCurrentPackedImportHandoffForCapacityRecovery()) {
                                    return;
                                }
                                if (rollbackBlockedMixedShulkerToSource(
                                        "import_staging_capacity_exhausted")) {
                                    return;
                                }
                                abortWithCargo(
                                        isEmptyShulkerStagingTask(currentTask)
                                                ? "empty_shulker_staging_full_with_cargo"
                                                : "import_staging_full_with_cargo",
                                        "No registered import chest can accept this cargo. It is preserved in inventory.");
                            }
                            case STAGE_SHULKER_IN_IMPORT -> {
                                if (rerouteCurrentCargoToImportStaging("shulker_lane_full")) return;
                                abortWithCargo("shulker_lane_full_with_cargo",
                                        "The assigned shulker lane is full and no import capacity remains. Cargo is preserved in inventory.");
                            }
                            case PACK_LOOSE_INTO_IMPORT -> {
                                int[] staging = firstWritableImportDestination();
                                if (staging != null) {
                                    startShulkerPacking(currentTask.itemId(), staging);
                                    return;
                                }
                                abortWithCargo("loose_lane_full_with_cargo",
                                        "The assigned lane is full and no import capacity remains. Loose cargo is preserved in inventory.");
                            }
                        }
                        return;
                    }

                    submitQuickMove(containerSlotIndex);
                    actionCooldown = config.organizerClickCooldownTicks;
                    // Resume from this slot next tick
                    actionSlotIndex = playerSlot;
                    return;
                }
            }
            playerSlot++;
        }

        // Done depositing
        if (taskCargo.remaining() > 0) {
            if (taskCargo.hasAcquiredCargo()) {
                abortWithCargo("task_cargo_not_found_during_deposit",
                        "The destination stopped seeing cargo before every acquired item had a confirmed deposit. The checkpoint was preserved for inspection.");
                return;
            }
        }
        if (movedThisVisit == 0 && !taskCargo.hasAcquiredCargo()) {
            destinationVisitFailed = true;
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
            if (!destinationVisitFailed) {
                completedTasks++;
                emitProgressMilestoneIfCrossed();
            }
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
                + " (" + crossing.milestonePercent() + "% milestone; mixed boxes "
                + decomposedMixedShulkers + "; generated loose tasks "
                + generatedLooseTasks + ")");
        emit("organize_progress", Map.of(
                "milestone_percent", crossing.milestonePercent(),
                "progress_percent", actualPercent,
                "remaining_tasks", Math.max(0, totalTasks - completedTasks),
                "decomposed_mixed_shulkers", decomposedMixedShulkers,
                "generated_loose_tasks", generatedLooseTasks
        ));
    }

    // SHULKER FETCH — take an empty shulker from a region container
    private void tickShulkerFetchOpen() {
        if (containerDataReceived) {
            if (awaitLiveOpenContainer() == null) {
                if (containerCacheReadyTimedOut()) {
                    emit("organize_target_failed", openFailureDetails(
                            "shulker_fetch_cache_sync_timeout"));
                    startOverflow();
                }
                return;
            }
            BARITONE.stop();
            actionSlotIndex = 0;
            actionCooldown = 0;
            state = State.SHULKER_FETCH_TAKE;
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening container for shulker fetch.");
            emit("organize_target_failed", openFailureDetails("shulker_fetch_open_timeout"));
            BARITONE.stop();
            startOverflow();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(walkTarget);
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
        if (currentTask != null && currentTask.alreadyInInventory()
                && currentTask.mixedBatchConsolidation()) {
            // The player section of an open server window is authoritative over Zenith's raw
            // inventory cache. Remember a proven absence so a stale inventory-only obligation
            // cannot spend the packing-source budget and then manufacture an empty overflow
            // transaction.
            packingCargoAbsenceConfirmed = countItemInOpenPlayerInventory(
                    open, chestSlots, currentTask.itemId()) == 0;
        }
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (packingHeadroomTransferPending) {
                if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                    packingHeadroomTransferPending = false;
                    actionCooldown = config.organizerClickCooldownTicks;
                }
                return;
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                fetchedPackingShulker = true;
                actionCooldown = 0;
                state = State.SHULKER_FETCH_CLOSING;
            } else if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }
        int partialSlot = -1;
        int emptySlot = -1;
        for (int slot = 0; slot < chestSlots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                if (!isShulkerBoxItem(itemId)) continue;
                ShulkerClassification classification = ShulkerClassification.classify(
                        ItemIdentifier.readShulkerContents(stack));
                if (isCompatiblePartialBulkShulker(classification)
                        && usablePackingShulker(stack)) {
                    partialSlot = slot;
                    break;
                }
                if (classification.kind() == ShulkerClassification.Kind.EMPTY && emptySlot < 0
                        && usablePackingShulker(stack)) {
                    emptySlot = slot;
                }
            }
        }

        int packingSlot = partialSlot >= 0 ? partialSlot : emptySlot;
        if (packingSlot >= 0) {
            if (!hasInventoryRoom()) {
                if (stageOneCargoStackForPackingHeadroom(open, chestSlots)) return;
                // A full candidate cannot accept even one temporary cargo stack. Another
                // shulker source may have room for the exchange.
                fetchedPackingShulker = false;
                actionCooldown = 0;
                state = State.SHULKER_FETCH_CLOSING;
                emit("organize_target_failed", Map.of(
                        "reason", "shulker_fetch_source_has_no_exchange_headroom",
                        "disposition", "try_next_shulker_source"
                ));
                return;
            }
            // Prefer topping off an exact matching partial bulk box. Fall back to an empty box;
            // mixed boxes are never candidates. The live stack is authoritative over the index.
            submitQuickMove(packingSlot);
            actionCooldown = config.organizerClickCooldownTicks;
            return;
        }

        // This was a false positive from the indexed summary (all its boxes are filled).
        // Close cleanly and try the next candidate before declaring that no packing box exists.
        rememberPackingSourceMiss(open);
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
                // The supply chest can be many blocks from the fixed reconciliation pad.
                // Selection immediately starts placement, so returning straight to SELECTING
                // here attempts a remote right click and burns all placement retries. Carry the
                // fetched box back to the station before selecting its exact hotbar slot.
                if (reconciliationStation == null) {
                    abortWithCargo("reconciliation_checkpoint_missing",
                            "The reconciliation station checkpoint was missing; the fetched box and cargo are preserved.");
                    return;
                }
                walkTarget = copyPos(reconciliationStation);
                trackedWalkTargetKey = Long.MIN_VALUE;
                state = State.SHULKER_STATION_WALK;
                shulkerTicks = 0;
                persistDurableCheckpoint(state);
            } else {
                startFetchShulker();
            }
        }
    }

    // SHULKER STORE — deposit filled shulker into destination
    private void tickShulkerStoreOpen() {
        Container open = containerDataReceived ? awaitLiveOpenContainer() : null;
        ContainerOpenAssessment.Result assessment = ContainerOpenAssessment.assess(
                containerDataReceived,
                open != null,
                open != null && containerHasEmptySlot(open, getOpenContainerSlotCount(open)));
        if (assessment == ContainerOpenAssessment.Result.WAIT_FOR_CACHE) {
            if (containerCacheReadyTimedOut()) {
                retryOrSwitchPackedShulkerDestination(
                        "shulker_store_cache_sync_timeout", false);
            }
            return;
        }
        if (assessment == ContainerOpenAssessment.Result.READY
                || assessment == ContainerOpenAssessment.Result.FULL) {
            BARITONE.stop();
            int chestSlots = getOpenContainerSlotCount(open);
            if (assessment == ContainerOpenAssessment.Result.FULL) {
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
            if (packedShulkerTransactionSlot(
                    open,
                    chestSlots,
                    countItemInOpenPlayerInventory(open, chestSlots, packItemId),
                    isShulkerAtPosition(reconciliationWorksite)) >= 0) {
                packStoreMatchingShulkersBefore = Math.max(1, packStoreMatchingShulkersBefore);
            }
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening destination for shulker deposit.");
            BARITONE.stop();
            retryOrSwitchPackedShulkerDestination("shulker_store_open_timeout", false);
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(walkTarget);
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
        int transactionShulkerSlot = packedShulkerTransactionSlot(
                open,
                chestSlots,
                countItemInOpenPlayerInventory(open, chestSlots, packItemId),
                isShulkerAtPosition(reconciliationWorksite));
        boolean transactionShulkerProven = transactionShulkerSlot >= 0;
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                actionSlotIndex++;
                movedThisVisit++;
                // The packed shulker is the final handoff for every loose unit still owned
                // by this reconciliation transaction.
                taskCargo.recordDeposited(taskCargo.remaining());
                packStoreVerificationTicks = 0;
                if (isImportStagingPack()) {
                    recordWritableImportDestination(
                            packDestination, packedCargoRoutingKey());
                }
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }

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
                boolean matchingBulk = classification.kind() == ShulkerClassification.Kind.BULK
                        && ItemIdentifier.contentItemIdsMatch(packItemId, classification.storageKey());
                if (matchingBulk || transactionShulkerProven && actionSlotIndex == transactionShulkerSlot) {
                    // A late SetContent can reveal the collected box after this window was
                    // first scanned. Refresh the baseline before submitting its click.
                    packStoreMatchingShulkersBefore = Math.max(
                            transactionShulkerProven ? 1 : 0,
                            Math.max(packStoreMatchingShulkersBefore,
                                    countCompatibleBulkShulkersInOpenPlayerInventory(
                                            open, chestSlots, packItemId)));
                    if (!matchingBulk) {
                        emit("organize_packed_shulker_cache_reconciled", Map.of(
                                "reason", "confirmed_fill_and_pickup_override_stale_box_shape",
                                "slot", actionSlotIndex,
                                "fill_moved_units", shulkerFillMovedUnits,
                                "cargo_units", taskCargo.remaining()
                        ));
                    }
                    submitQuickMove(containerSlotIndex);
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
        // A skipped/late hotbar swap can put packing cargo into the mixed source shell. The
        // live destination window is authoritative, so convert that uniquely matching mixed
        // box back into a decomposition task before spending pickup-sync retries or routing it
        // into a single-item lane.
        if (movedThisVisit == 0 && recoverLegacyMixedPackingCheckpoint()) return;
        if (shouldAwaitPackedShulkerInventorySync(
                movedThisVisit, packStoreVerificationTicks,
                packedShulkerVerificationTimeoutTicks())) {
            // Collection and the destination window can arrive out of order. Rescan from the
            // first usable player slot so a later SetContent can expose the packed box.
            if (packStoreVerificationTicks++ == 0) {
                emit("organize_packed_shulker_inventory_sync_wait", Map.of(
                        "reason", "packed_shulker_not_yet_visible_in_destination_window",
                        "timeout_ticks", packedShulkerVerificationTimeoutTicks(),
                        "pickup_packet_confirmed", temporaryShulkerPickupConfirmed,
                        "player_cache_box_visible", hasPackedShulkerForHandoff()));
            }
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = Math.max(1, config.organizerClickCooldownTicks);
            return;
        }
        if (!depositConfirmed && movedThisVisit > 0
                && packStoreVerificationTicks++ < packedShulkerVerificationTimeoutTicks()) {
            // InventoryManager acceptance only means the click packet was sent. Give the live
            // window time to receive the authoritative server slot update before retrying.
            return;
        }
        int looseItemsRemaining = depositConfirmed
                ? countItemInOpenPlayerInventory(open, chestSlots, packItemId)
                : 0;
        closeCurrentContainer();
        if (depositConfirmed) {
            completePackedShulkerStore(
                    looseItemsRemaining, true, matchingShulkersAfter);
        } else if (movedThisVisit == 0) {
            recoverPackedShulkerInventoryVisibility(
                    "packed_shulker_not_found_in_destination_window");
        } else {
            retryOrSwitchPackedShulkerDestination(
                    "packed_shulker_deposit_not_confirmed",
                    false);
            return;
        }
    }

    private void completePackedShulkerStore() {
        completePackedShulkerStore(countItemInInventory(packItemId), false);
    }

    private void completePackedShulkerStore(int looseItemsRemaining) {
        completePackedShulkerStore(looseItemsRemaining, true);
    }

    private void completePackedShulkerStore(int looseItemsRemaining, boolean liveDepositConfirmed) {
        completePackedShulkerStore(looseItemsRemaining, liveDepositConfirmed, -1);
    }

    private void completePackedShulkerStore(
            int looseItemsRemaining,
            boolean liveDepositConfirmed,
            int compatiblePackedShulkersRemaining) {
        boolean releasedImportCapacity = liveDepositConfirmed
                && isImportCapacityRecoveryTask(currentTask)
                && hasDeferredImportCapacityHandoff();
        MoveTask coalescedHandoff = releasedImportCapacity
                && compatiblePackedShulkersRemaining == 0
                ? deferredPackedImportHandoff(packItemId)
                : null;
        packedShulkerInventorySyncRecoveries = 0;
        if (ShulkerFillPolicy.stalled(shulkerFillMovedUnits, looseItemsRemaining)) {
            if (++zeroFillCycles >= 3) {
                abortWithCargo("shulker_fill_no_progress",
                        "Three packing boxes accepted no cargo. Remaining cargo and the checkpoint are preserved.");
                return;
            }
            emit("organize_packing_box_skipped", Map.of(
                    "reason", "shulker_has_no_compatible_headroom",
                    "attempt", zeroFillCycles,
                    "disposition", "try_different_or_empty_box"));
            startShulkerPacking(packItemId, packDestination, true);
            return;
        }
        emit(liveDepositConfirmed ? "organize_packed_shulker_delivered" : "organize_packed_handoff_restored", Map.of(
                "storage_class", packItemId,
                "delivery_position", posString(packDestination),
                "destination_kind", isImportStagingPack() ? "import" : "assigned_lane",
                "at_maximum_capacity", packedAtMaximumCapacity,
                "fill_moved_units", shulkerFillMovedUnits,
                "remaining_loose_units", looseItemsRemaining));
        if (liveDepositConfirmed && shulkerFillMovedUnits > 0) {
            inventoryRecoveryGuard.reset();
            supplyStagesWithoutDelivery.remove(packItemId);
        }
        flushImportCapacitySummary("packed_shulker_delivered");
        zeroFillCycles = 0;
        if (consolidationMode) {
            completedTasks += Math.max(0, consolidationSourcesInBatch);
            consolidationSourcesInBatch = 0;
        } else if (looseItemsRemaining == 0) {
            completedTasks++;
        }
        if (coalescedHandoff != null) {
            // The deferred partial box was selected as this recovery group's compatible
            // packing shell. Its confirmed delivery satisfies both transactions; retaining
            // the old handoff would make the already-delivered box look missing next tick.
            taskQueue.remove(coalescedHandoff);
            completedTasks++;
            emit("organize_packed_shulker_coalesced", Map.of(
                    "reason", "deferred_box_reused_as_capacity_recovery_shell",
                    "storage_class", packItemId,
                    "disposition", "deferred_handoff_completed_by_live_delivery"
            ));
        }
        emitProgressMilestoneIfCrossed();
        if (isImportStagingPack()) {
            stagedShulkers++;
            stagedStorageClasses.add(packItemId);
        }
        if (looseItemsRemaining > 0) {
            // Deliver this box before continuing with the remaining onboard cargo.
            startShulkerPacking(packItemId, packDestination, true);
            return;
        }
        if (releasedImportCapacity) {
            // Retry the older handoff while this compaction's newly freed import space is
            // still available instead of spending it on unrelated mixed work first.
            consolidationMode = false;
            mixedBatchConsolidationMode = false;
            emit("organize_import_capacity_recovered", Map.of(
                    "reason", "staged_import_cargo_compacted",
                    "disposition", "retry_deferred_import_handoff"
            ));
            advanceToNextTask();
            return;
        }
        // A restored old build may have processed an empty box while leaving its intended
        // mixed box onboard. Only that diagnosed recovery path gets an extra inventory audit;
        // ordinary deliveries retain their established shell/cargo ordering.
        boolean auditRecoveredInventory = recoverOnboardMixedAfterPackedDelivery;
        recoverOnboardMixedAfterPackedDelivery = false;
        if (auditRecoveredInventory && queueUnscheduledMixedInventoryRecoveryTasks()) {
            consolidationMode = false;
            mixedBatchConsolidationMode = false;
            advanceToNextTask();
            return;
        }
        if (consolidationMode) {
            advanceConsolidation();
        } else {
            advanceToNextTask();
        }
    }

    // SHULKER PACKING CYCLE
    private void startShulkerPacking(String itemId, int[] destination) {
        startShulkerPacking(itemId, destination, false);
    }

    private void startShulkerPacking(String itemId, int[] destination, boolean continuation) {
        if (retireMissingInventoryPackingCargo("packing_start")) return;
        clearMixedDecompositionState();
        if (!continuation) {
            zeroFillCycles = 0;
            rejectedPackingShulkers.clear();
        }
        this.packItemId = itemId;
        this.packDestination = packedLaneDestination(itemId, columnAssignment, destination);
        this.packingLaneExhausted = false;
        this.packedAtMaximumCapacity = false;
        this.packingSupplySearch.reset();
        this.packingCargoAbsenceConfirmed = false;
        this.stagingForPackingSupply = false;
        this.supplyStagingLedger.reset();
        this.packStoreTriedDestinations.clear();
        this.emptyShulkerStagingTriedDestinations.clear();
        this.packDestinationOpenFailures = 0;
        this.packStoreMatchingShulkersBefore = 0;
        this.packStoreVerificationTicks = 0;
        this.packedShulkerInventorySyncRecoveries = 0;
        this.fetchedPackingShulker = false;
        this.packingHeadroomTransferPending = false;
        this.shulkerFillMovedUnits = 0;
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
        List<int[]> writableImports = orderedWritableImportDestinations();
        if (writableImports.isEmpty()) {
            abortWithCargo("mixed_staging_capacity_exhausted",
                    "No registered import chest is available. The mixed box remains in its source or inventory.");
            return;
        }
        // Planning is a snapshot. Route each new transaction through the latest confirmed
        // writable destination instead of replaying the same now-full planned chest.
        currentTask = currentTask.withDestination(copyPos(writableImports.get(0)));
        resetTemporaryShulkerState();
        mixedStagingLedger.reset();
        mixedDecompositionMode = true;
        mixedBoxDrained = false;
        mixedStagingCargoKey = null;
        mixedStagingCargoItemId = null;
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

        // The worksite is fixed when organization starts. Recover an organizer box left there
        // by an older checkpoint before selecting or fetching another shulker.
        shulkerPlacePos = reconciliationWorksite;
        if (isShulkerAtPosition(shulkerPlacePos)) {
            shulkerInventoryCountBeforePlacement = countShulkerBoxesInInventory() + 1;
            compatibleShulkerCountBeforePlacement = mixedDecompositionMode
                    ? 0 : countCompatibleBulkShulkersInInventory(packItemId);
            temporaryShulkerOutstanding = true;
            temporaryShulkerPickupConfirmed = false;
            temporaryShulkerPickupFingerprint = null;
            temporaryShulkerPickupStorageKey = null;
            temporaryShulkerPickupStack = null;
            stopAfterShulkerRecovery = false;
            beginTemporaryShulkerRecovery(ORPHANED_WORKSITE_RECOVERY);
            return;
        }
        if (!isShulkerWorksiteSafe(shulkerPlacePos, reconciliationStation)) {
            abortWithCargo("reconciliation_station_unsafe",
                    "The starting-position reconciliation worksite is no longer safe; items are preserved in inventory.");
            return;
        }
        if (finishRecoveredEmptyMixedShellCheckpoint()) return;

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
            startFetchShulker();
            return;
        }

        // Keep the recovered empty shell available while useful partial stock can take cargo.
        if (!mixedDecompositionMode && isEmptyPackingShell(getPlayerInventoryStack(shulkerSlot))
                && packingSupplyCandidates(packItemId).stream()
                        .anyMatch(c -> c.rank() == 0 && packingSupplySearch.canTry(c))) {
            startFetchShulker();
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
        temporaryShulkerPickupConfirmed = false;
        temporaryShulkerPickupFingerprint = null;
        temporaryShulkerPickupStorageKey = null;
        temporaryShulkerPickupStack = null;
        if (!moveShulkerToHotbar(shulkerSlot)) {
            abortWithCargo("safe_hotbar_slot_unavailable",
                    "No unprotected hotbar slot is available for the reconciliation shulker.");
            return;
        }

        state = State.SHULKER_PLACING;
        shulkerTicks = 0;
        shulkerPlaceRetries = 0;
        resetShulkerPlacementAttempt();
        shulkerPlaceTargetCursor = 0;
        persistDurableCheckpoint(state);
    }

    private void tickShulkerPlacing() {
        if (ownedInventoryRequest != null) {
            if (!ownedInventoryRequest.isCompleted()) return;
            if (!ownedInventoryRequest.getNow()) {
                abortWithCargo("shulker_hotbar_transfer_rejected",
                        "The packing shulker could not be equipped; cargo is preserved in inventory.");
                return;
            }
            if (!selectedShulkerReadyForPlacement()) {
                if (++shulkerTicks >= TRANSFER_VERIFICATION_TIMEOUT_TICKS) {
                    abortWithCargo("shulker_hotbar_transfer_unverified",
                            "The selected shulker never appeared in the held hotbar slot; no box was placed.");
                }
                return;
            }
            ownedInventoryRequest = null;
        }

        shulkerTicks++;

        // InventoryManager acceptance only proves that its packets were processed. A stale
        // chest window can make Zenith skip both the swap and SetHeldItem actions while still
        // completing the request successfully. Never place whichever unrelated box happens
        // to remain selected; verify the exact selected stack from the live player cache.
        if (!selectedShulkerReadyForPlacement()) {
            if (shulkerTicks >= TRANSFER_VERIFICATION_TIMEOUT_TICKS) {
                abortWithCargo("shulker_hotbar_selection_lost",
                        "The selected shulker changed before placement; no box was placed.");
            }
            return;
        }

        if (isShulkerAtPosition(shulkerPlacePos)) {
            confirmShulkerPlacement();
            return;
        }

        if (shulkerPlaceRetryDelayTicks > 0) {
            shulkerPlaceRetryDelayTicks--;
            return;
        }

        if (shulkerPlaceRetries == 0
                && shulkerTicks < ShulkerPlacementCadence.SELECTION_SETTLE_TICKS) return;

        if (shulkerPlaceFuture == null) {
            ShulkerPlacementSubmission submission = placeSelectedShulkerExact();
            shulkerPlaceFuture = submission.future();
            shulkerPlaceSupportPos = submission.support();
            shulkerPlaceSupportFace = submission.face();
            shulkerPlaceTargetOrdinal = submission.targetOrdinal();
            shulkerPlaceTargetCount = submission.targetCount();
            shulkerPlaceDispatchLogged = false;
            shulkerPlaceFinalGrace = false;
            state = State.SHULKER_WAIT_PLACE;
            shulkerTicks = 0;
        }
    }

    private void tickShulkerWaitPlace() {
        shulkerTicks++;

        if (isShulkerAtPosition(shulkerPlacePos)) {
            confirmShulkerPlacement();
            return;
        }

        if (shulkerPlaceFinalGrace) {
            if (shulkerTicks >= ShulkerPlacementCadence.FINAL_ACK_GRACE_TICKS) {
                finishExhaustedShulkerPlacement();
            }
            return;
        }

        ShulkerPlacementCadence.Assessment assessment =
                ShulkerPlacementCadence.assess(shulkerPlaceFuture, shulkerPlaceSupportPos);
        switch (assessment.evidence()) {
            case PENDING -> {
                if (shulkerTicks >= ShulkerPlacementCadence.DISPATCH_TIMEOUT_TICKS) {
                    emitShulkerPlacementEvidence("organize_shulker_place_local_rejected",
                            assessment, "input_future_pending", "stop_without_resubmission");
                    // An unfinished input can execute late, so neither a duplicate click nor
                    // walking to imports is safe. Preserve the checkpoint and stop in place.
                    abortWithCargo("shulker_place_input_pending",
                            "The shulker placement request did not settle; the job stopped at the worksite with cargo preserved.");
                }
            }
            case REJECTED, NO_CLICK, WRONG_ACTION -> {
                emitShulkerPlacementEvidence("organize_shulker_place_local_rejected",
                        assessment, assessment.action(), "retry_with_backoff");
                scheduleShulkerPlacementRetry(false);
            }
            case WRONG_TARGET -> {
                emitShulkerPlacementEvidence("organize_shulker_place_wrong_target",
                        assessment, "raycast_target_mismatch", "retry_alternate_support");
                scheduleShulkerPlacementRetry(false);
            }
            case DISPATCHED -> {
                if (!shulkerPlaceDispatchLogged) {
                    shulkerPlaceDispatchLogged = true;
                    emitShulkerPlacementEvidence("organize_shulker_place_dispatched",
                            assessment, "use_item_on_expected_support", "await_world_ack");
                }
                if (shulkerTicks >= ShulkerPlacementCadence.SERVER_ACK_TIMEOUT_TICKS) {
                    emitShulkerPlacementEvidence("organize_shulker_place_server_unacknowledged",
                            assessment, "world_block_missing", shulkerPlaceRetries + 1
                                    >= ShulkerPlacementCadence.MAX_ATTEMPTS
                                    ? "late_ack_grace" : "retry_alternate_support");
                    scheduleShulkerPlacementRetry(true);
                }
            }
        }
    }

    private void confirmShulkerPlacement() {
        Map<String, Object> details = shulkerPlacementDetails();
        details.put("reason", "world_block_confirmed");
        details.put("ack_ticks", shulkerTicks);
        emit("organize_shulker_place_confirmed", details);
        temporaryShulkerOutstanding = true;
        state = State.SHULKER_OPENING;
        shulkerTicks = 0;
        resetContainerOpenTracking();
        resetShulkerPlacementAttempt();
    }

    private void scheduleShulkerPlacementRetry(boolean serverDispatchUnacknowledged) {
        shulkerPlaceRetries++;
        if (shulkerPlaceRetries >= ShulkerPlacementCadence.MAX_ATTEMPTS) {
            if (serverDispatchUnacknowledged) {
                // The local raycast proved that a valid packet was emitted. Give the server
                // one final quiet window before deciding that the held box was not consumed.
                shulkerPlaceFinalGrace = true;
                shulkerTicks = 0;
                return;
            }
            finishExhaustedShulkerPlacement();
            return;
        }

        if (!selectedShulkerReadyForPlacement()) {
            temporaryShulkerOutstanding = true;
            beginTemporaryShulkerRecovery("shulker_place_inventory_changed");
            return;
        }

        int retryDelay = ShulkerPlacementCadence.retryBackoffTicks(shulkerPlaceRetries);
        resetShulkerPlacementAttempt();
        shulkerPlaceRetryDelayTicks = retryDelay;
        state = State.SHULKER_PLACING;
        shulkerTicks = 0;
    }

    private void finishExhaustedShulkerPlacement() {
        if (isShulkerAtPosition(shulkerPlacePos)) {
            confirmShulkerPlacement();
            return;
        }
        if (!selectedShulkerReadyForPlacement()) {
            temporaryShulkerOutstanding = true;
            beginTemporaryShulkerRecovery("shulker_place_outcome_unknown");
            return;
        }

        // The exact box is still held after every bounded attempt and the final late-ack
        // window. No placement consumed it, so loose cargo can be journaled into imports.
        info("Shulker placement was not acknowledged; staging cargo in import storage.");
        resetShulkerPlacementAttempt();
        startOverflow();
    }

    private void emitShulkerPlacementEvidence(
            String event,
            ShulkerPlacementCadence.Assessment assessment,
            String reason,
            String disposition) {
        Map<String, Object> details = shulkerPlacementDetails();
        details.put("reason", reason);
        details.put("disposition", disposition);
        details.put("evidence", assessment.evidence().name().toLowerCase(Locale.ROOT));
        details.put("click_action", assessment.action());
        if (assessment.hasActualTarget()) {
            details.put("actual_support", assessment.actualX() + ", "
                    + assessment.actualY() + ", " + assessment.actualZ());
        }
        emit(event, details);
    }

    private Map<String, Object> shulkerPlacementDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("attempt", shulkerPlaceRetries + 1);
        details.put("max_attempts", ShulkerPlacementCadence.MAX_ATTEMPTS);
        details.put("wait_ticks", shulkerTicks);
        details.put("support_face", Objects.toString(shulkerPlaceSupportFace, "none"));
        details.put("support_target", shulkerPlaceTargetOrdinal < 0
                ? "none" : (shulkerPlaceTargetOrdinal + 1) + "/" + shulkerPlaceTargetCount);
        details.put("expected_support", shulkerPlaceSupportPos == null
                ? "none" : posString(shulkerPlaceSupportPos));
        details.put("worksite", shulkerPlacePos == null
                ? "none" : posString(shulkerPlacePos));
        return details;
    }

    private void resetShulkerPlacementAttempt() {
        shulkerPlaceFuture = null;
        shulkerPlaceSupportPos = null;
        shulkerPlaceSupportFace = null;
        shulkerPlaceTargetOrdinal = -1;
        shulkerPlaceTargetCount = 0;
        shulkerPlaceRetryDelayTicks = 0;
        shulkerPlaceDispatchLogged = false;
        shulkerPlaceFinalGrace = false;
    }

    private void tickShulkerOpening() {
        if (containerDataReceived && openContainerId >= 0) {
            if (awaitLiveOpenContainer() == null) {
                if (containerCacheReadyTimedOut()) {
                    emit("organize_target_failed", openFailureDetails(
                            "shulker_cache_sync_timeout"));
                    startOverflow();
                }
                return;
            }
            BARITONE.stop();
            state = mixedDecompositionMode ? State.SHULKER_EMPTYING : State.SHULKER_FILLING;
            actionSlotIndex = mixedDecompositionMode ? 0 : 9;
            actionCooldown = 0;
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening placed shulker");
            emit("organize_target_failed", openFailureDetails("shulker_open_timeout"));
            BARITONE.stop();
            startOverflow();
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(shulkerPlacePos);
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
            boolean requestCompleted = ownedInventoryRequest == null
                    || ownedInventoryRequest.isCompleted();
            boolean requestAccepted = ownedInventoryRequest != null
                    && ownedInventoryRequest.getNow();
            var transferResult = MixedStackTransferPolicy.assess(
                    requestCompleted,
                    requestAccepted,
                    source != null && source.getAmount() > 0,
                    transferred != null && transferred.getAmount() > 0);
            switch (transferResult) {
                case WAIT -> {
                    return;
                }
                case CONFIRMED -> {
                    mixedCargoSlots.add(mixedPendingCargoSlot);
                    actionSlotIndex = Math.max(actionSlotIndex, mixedPendingSourceSlot + 1);
                }
                case RETRY -> {
                    actionCooldown = config.organizerClickCooldownTicks;
                }
                case UNVERIFIED -> {
                    recoverMixedShulkerAndStop("mixed_stack_transfer_unverified",
                            "A mixed stack transfer could not be verified; recovering the placed box before stopping.");
                    return;
                }
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

            int freeCargoSlots = countEmptyPlayerSlotsInOpenContainer(open, shulkerSlots);
            if (MixedShulkerPlaybook.shouldStageBeforeNextTransfer(
                    freeCargoSlots, mixedCargoSlots.size())) {
                beginMixedSourceClose(false);
                return;
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

    private int countEmptyPlayerSlotsInOpenContainer(Container open, int containerSlots) {
        int free = 0;
        for (int rawSlot = 9; rawSlot < 45; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int windowSlot = rawPlayerSlotToWindowSlot(containerSlots, rawSlot);
            ItemStack stack = open.getItemStack(windowSlot);
            if ((stack == null || stack.getAmount() <= 0)
                    && !mixedCargoSlots.contains(rawSlot)) {
                free++;
            }
        }
        return free;
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
        // Each inventory load is a new staging transaction. A chest that rejected an
        // earlier batch may have gained room after other cargo moved, and carrying those
        // rejections into another batch of the same item eventually produces a false
        // "all imports full" result.
        resetRejectedImportsForNewBatch(mixedUnavailableStagingDestinations);
        state = State.MIXED_SOURCE_CLOSING;
        actionCooldown = 0;
    }

    static void resetRejectedImportsForNewBatch(Set<Long> rejectedImports) {
        if (rejectedImports != null) rejectedImports.clear();
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
            for (int rawSlot : mixedCargoSlots) {
                ItemStack cargo = getCurrentPlayerInventoryStack(rawSlot);
                if (cargo == null || cargo.getAmount() <= 0) continue;
                mixedStagingCargoKey = cargoRoutingKey(cargo);
                mixedStagingCargoItemId = itemIdFromStack(cargo);
                break;
            }
            List<int[]> candidates = orderedWritableImportDestinations(
                    mixedStagingCargoKey, mixedStagingCargoItemId);
            startMixedStageWalk(candidates.isEmpty() ? packDestination : candidates.getFirst());
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
        if (containerDataReceived) {
            if (awaitLiveOpenContainer() == null) {
                if (containerCacheReadyTimedOut()) {
                    mixedUnavailableStagingDestinations.add(importInventoryKey(packDestination));
                    if (!switchMixedStagingDestination()) {
                        recoverMixedShulkerAndStop("mixed_staging_cache_sync_timeout",
                                "No import chest produced a usable live window; recovering the mixed shulker before stopping.");
                    }
                }
                return;
            }
            BARITONE.stop();
            actionCooldown = 0;
            state = State.MIXED_STAGE_DEPOSIT;
            return;
        }
        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;
        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            BARITONE.stop();
            mixedUnavailableStagingDestinations.add(importInventoryKey(packDestination));
            if (!switchMixedStagingDestination()) {
                recoverMixedShulkerAndStop("mixed_staging_open_timeout",
                        "No registered import chest could be opened; recovering the placed mixed shulker before stopping.");
            }
            return;
        }
        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(packDestination);
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
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED
                    || transfer.outcome() == QuickMoveOutcome.CONFIRMED_PARTIAL) {
                mixedStagingLedger.confirm();
                persistDurableCheckpoint(State.MIXED_STAGE_DEPOSIT);
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                mixedCargoSlots.removeIf(rawSlot ->
                        rawPlayerSlotToWindowSlot(chestSlots, rawSlot) == transfer.slot());
                recordWritableImportDestination(packDestination, mixedStagingCargoKey);
                persistDurableCheckpoint(State.MIXED_STAGE_DEPOSIT);
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }
        Iterator<Integer> cargo = mixedCargoSlots.iterator();
        while (cargo.hasNext()) {
            int rawSlot = cargo.next();
            int windowSlot = rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = open.getItemStack(windowSlot);
            if (stack == null || stack.getAmount() <= 0) {
                cargo.remove();
                continue;
            }
            String nextCargoKey = cargoRoutingKey(stack);
            if (!Objects.equals(nextCargoKey, mixedStagingCargoKey)) {
                // A rejection applies to this exact loose stack class only. The same import
                // may still merge the next class even when it has no empty slots.
                mixedUnavailableStagingDestinations.clear();
            }
            mixedStagingCargoKey = nextCargoKey;
            mixedStagingCargoItemId = itemIdFromStack(stack);
            ContainerAdmission admission = inspectContainerAdmission(open, chestSlots, stack);
            if (!admission.canAccept()) {
                closeCurrentContainer();
                long fullKey = importInventoryKey(packDestination);
                mixedUnavailableStagingDestinations.add(fullKey);
                importDestinationTracker.recordRejected(fullKey, mixedStagingCargoKey);
                if (!switchMixedStagingDestination(admission)) {
                    recoverMixedShulkerAndStop("mixed_staging_full",
                            "No registered import chest can accept the current mixed cargo; recovering the placed shulker before stopping."
                                    + mixedStagingCapacitySummary());
                }
                return;
            }
            rememberMixedStagingDestination(packDestination);
            mixedStagingLedger.begin(mixedStagingCargoItemId, canonicalStagingPosition(packDestination));
            // Save the exact attempted pair before sending a packet, including restart ambiguity.
            if (journalJobId != null && !persistDurableCheckpoint(State.MIXED_STAGE_DEPOSIT)) {
                recoverMixedShulkerAndStop("mixed_staging_checkpoint_unavailable",
                        "The staging checkpoint could not be saved; recovering the placed shulker before stopping.");
                return;
            }
            submitQuickMove(windowSlot);
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
        flushImportCapacitySummary("mixed_cargo_staged");
        actionCooldown = 0;
        walkTarget = reconciliationStation;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.MIXED_RETURN_WALK;
        persistDurableCheckpoint(state);
    }

    private boolean switchMixedStagingDestination() {
        return switchMixedStagingDestination(null);
    }

    private boolean switchMixedStagingDestination(ContainerAdmission rejectedAdmission) {
        closeCurrentContainer();
        if (rejectedAdmission != null) {
            noteImportCapacityMiss();
            if (!shouldContinueMixedStagingCapacitySearch(
                    mixedUnavailableStagingDestinations.size(),
                    MAX_MIXED_STAGING_CAPACITY_PROBES)) {
                emit("organize_import_capacity_probe_budget_reached", Map.of(
                        "reason", "mixed_staging_probe_budget_exhausted",
                        "destinations_tried", mixedUnavailableStagingDestinations.size(),
                        "max_probes", MAX_MIXED_STAGING_CAPACITY_PROBES,
                        "disposition", "recover_placed_box_and_repack_inventory_cargo"
                ));
                return false;
            }
        }
        for (int[] candidate : orderedWritableImportDestinations(
                mixedStagingCargoKey, mixedStagingCargoItemId)) {
            long key = importInventoryKey(candidate);
            if (!mixedUnavailableStagingDestinations.contains(key)) {
                startMixedStageWalk(candidate);
                return true;
            }
        }
        return false;
    }

    static boolean shouldContinueMixedStagingCapacitySearch(int destinationsTried, int maxProbes) {
        return destinationsTried < Math.max(1, maxProbes);
    }

    private String mixedStagingCapacitySummary() {
        int available = orderedWritableImportDestinations(
                mixedStagingCargoKey, mixedStagingCargoItemId).size();
        return " Live checks tried " + mixedUnavailableStagingDestinations.size()
                + " of " + available + " registered import inventories.";
    }

    private void recoverMixedShulkerAndStop(String reason, String message) {
        flushImportCapacitySummary(reason);
        info(message);
        if (isResumableStagingCapacityFailure(reason)) {
            emit("organize_recovery_started", Map.of(
                    "reason", reason,
                    "terminal", false,
                    "disposition", "recover_placed_box_before_capacity_replan"
            ));
        } else {
            emit("organize_failed", Map.of("reason", reason));
        }
        stopAfterShulkerRecovery = true;
        beginTemporaryShulkerRecovery(reason);
    }

    private record ContainerAdmission(
            int containerSlots,
            int emptySlots,
            int matchingHeadroom,
            int maxStackSize) {
        boolean canAccept() {
            return ShulkerFillPolicy.hasCapacity(emptySlots, matchingHeadroom);
        }

        long totalUnitHeadroom() {
            return (long) emptySlots * maxStackSize + matchingHeadroom;
        }
    }

    private static ContainerAdmission inspectContainerAdmission(
            Container open,
            int chestSlots,
            ItemStack cargo) {
        ContainerCapacitySnapshot capacity = ContainerCapacitySnapshot.read(chestSlots, open::getItemStack);
        return new ContainerAdmission(chestSlots, capacity.emptySlots(),
                capacity.matchingHeadroom(cargo), ContainerCapacitySnapshot.stackLimit(cargo));
    }

    private static Map<String, Object> importCapacityDetails(
            ContainerAdmission admission,
            int destinationsTried,
            String cargoItemId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "import_destination_incompatible");
        details.put("cargo_item", Objects.requireNonNullElse(cargoItemId, "unknown"));
        details.put("container_slots", admission.containerSlots());
        details.put("empty_slots", admission.emptySlots());
        details.put("matching_headroom", admission.matchingHeadroom());
        details.put("total_unit_headroom", admission.totalUnitHeadroom());
        details.put("max_stack_size", admission.maxStackSize());
        details.put("destinations_tried", destinationsTried);
        return details;
    }

    private void rememberMixedStagingDestination(int[] destination) {
        long key = posKey(destination[0], destination[1], destination[2]);
        boolean known = mixedStagingUsedDestinations.stream().anyMatch(existing ->
                posKey(existing[0], existing[1], existing[2]) == key);
        if (!known) mixedStagingUsedDestinations.add(copyPos(destination));
    }

    private void reopenMixedShulkerAtStation() {
        resetContainerOpenTracking();
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
        if (shulkerFillMovedUnits < 0) shulkerFillMovedUnits = 0;
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED
                    || transfer.outcome() == QuickMoveOutcome.CONFIRMED_PARTIAL) {
                shulkerFillMovedUnits += transfer.movedAmount();
                persistDurableCheckpoint(State.SHULKER_FILLING);
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                actionSlotIndex++;
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
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
                    ContainerAdmission admission = inspectContainerAdmission(open, chestSlots, stack);
                    if (!admission.canAccept()) {
                        // Another stack of this storage class may have compatible components.
                        actionSlotIndex++;
                        continue;
                    }
                    submitQuickMove(containerSlotIndex);
                    actionCooldown = config.organizerClickCooldownTicks;
                    return;
                }
            }
            actionSlotIndex++;
        }


        ContainerCapacitySnapshot capacity = ContainerCapacitySnapshot.read(chestSlots, open::getItemStack);
        packedAtMaximumCapacity = capacity.atMaximumCapacity();
        if (shulkerFillMovedUnits == 0
                && countItemInOpenPlayerInventory(open, chestSlots, packItemId) > 0) {
            rejectedPackingShulkers.add(capacity);
        }
        closeCurrentContainer();
        state = State.SHULKER_CLOSING;
        shulkerTicks = 0;
        persistDurableCheckpoint(state);
    }

    private void tickShulkerClosing() {
        shulkerTicks++;

        if (shulkerTicks < 3) return; // Wait after closing

        // Select best tool for breaking
        // (In Zenith context, we may not have tool selection; skip for now)

        shulkerBreakAttemptGate.clear();
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
            shulkerBreakAttemptGate.clear();
            resetShulkerPickupSweep();
            return;
        }

        if (shulkerTicks > BREAK_TIMEOUT_TICKS) {
            info("Shulker breaking timed out");
            emit("organize_failed", Map.of("reason", "shulker_break_timeout"));
            startOverflow();
            return;
        }

        switch (shulkerBreakAttemptGate.next(shulkerBreakFuture != null)) {
            case RESET -> {
                BARITONE.stop();
                if (!BaritoneCompat.resetBlockBreakingState()) {
                    emit("organize_target_failed", Map.of(
                            "reason", "shulker_break_preflight_unavailable"));
                }
                return;
            }
            case SUBMIT -> {
                shulkerBreakFuture = BaritoneCompat.breakBlock(
                    shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], true);
                ownInteraction(shulkerBreakFuture);
                return;
            }
            case WAIT -> { }
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
                && temporaryShulkerRecoveryStatus().inventoryRecovered()) {
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            finishMixedShulkerDecomposition();
            return;
        }

        // Breaking only proves that the block became air. Walk onto the former block position
        // like MOAR does so collection is an explicit part of the transaction, then verify that
        // the inventory regained both the placed box and a compatible packed box.
        if (hasPackedShulkerInInventory() || pickupEvidenceMatchesPackedShulker()) {
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            walkToPackedShulkerDestination(packDestination);
            return;
        }

        pathToShulkerDrop();
        if (shulkerTicks >= SHULKER_PICKUP_TIMEOUT_TICKS) {
            BARITONE.stop();
            if (packedShulkerInventorySyncRecoveries > 0) {
                abortWithCargo("packed_shulker_inventory_sync_unresolved",
                        "The packed shulker pickup was confirmed but did not become visible in inventory; its checkpoint is preserved.");
                return;
            }
            info("Shulker pickup failed");
            startOverflow();
        }
    }

    private void finishMixedShulkerDecomposition() {
        finishMixedShulkerDecomposition(List.of());
    }

    private void finishMixedShulkerDecomposition(List<MoveTask> inventoryCargoTasks) {
        MoveTask mixedTask = currentTask;
        if (mixedTask == null || !mixedTask.mixedDecomposition() || !mixedBoxDrained
                || temporaryShulkerOutstanding) {
            abortWithCargo("mixed_shulker_completion_unverified",
                    "The mixed box could not be verified empty; its cargo is preserved for recovery.");
            return;
        }
        if (isShulkerAtPosition(shulkerPlacePos)) {
            temporaryShulkerOutstanding = true;
            failMixedShellVerification("mixed_shulker_block_still_present");
            return;
        }
        if (temporaryShulkerPickupFingerprint != null
                && !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)) {
            failMixedShellVerification("mixed_shulker_recovered_nonempty");
            return;
        }
        if (!recoveredEmptyShellAvailable(mixedTask)) {
            beginMixedShellVerification(inventoryCargoTasks);
            return;
        }
        // Recovery may substitute a fungible empty shell of another color. Use its live item
        // identity for the staging task so the following inventory lookup can find it.
        mixedTask = currentTask;
        if (mixedShellVerificationActive) {
            emit("organize_mixed_shell_inventory_verified", mixedShellVerificationDetails("empty_shell_visible"));
        }

        inventoryRecoveryGuard.reset();
        emit("organize_empty_shulker_produced", Map.of(
                "reason", "mixed_box_drained_and_recovered",
                "available_empty_shells", countEmptyPackingShells(null)));

        List<String> storageClasses = mixedTask.mixedContents().keySet().stream()
                .map(StorageClassPolicy::exact)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<MoveTask> batch = stagedMixedBatchTasks(storageClasses, mixedTask.destination());

        List<MoveTask> recoveredCargoTasks = inventoryCargoTasks == null
                ? List.of()
                : List.copyOf(inventoryCargoTasks);
        completedTasks++;
        decomposedMixedShulkers++;
        generatedLooseTasks += batch.size() + recoveredCargoTasks.size();

        if (!recoveredCargoTasks.isEmpty()) {
            // Import capacity was exhausted after this shell had already been emptied. Keep
            // the recovered shell in inventory and immediately use it to pack one exact cargo
            // class. Staging the empty shell first would require the very import slot whose
            // absence triggered recovery and would only repeat the same terminal sweep.
            totalTasks += batch.size() + recoveredCargoTasks.size();
            emitProgressMilestoneIfCrossed();

            currentTask = null;
            resetTemporaryShulkerState();
            clearMixedDecompositionState();
            List<MoveTask> recoveryBatch = new ArrayList<>(recoveredCargoTasks);
            recoveryBatch.addAll(batch);
            prependConsolidationTasks(recoveryBatch);
            consolidationMode = true;
            mixedBatchConsolidationMode = true;
            emit("organize_empty_shulker_reused", Map.of(
                    "reason", "mixed_staging_capacity_exhausted",
                    "disposition", "pack_recovered_inventory_cargo",
                    "inventory_cargo_classes", recoveredCargoTasks.size(),
                    "staged_source_tasks", batch.size()
            ));
            advanceConsolidation();
            return;
        }

        // The empty shell is organizer-owned cargo. Stage it before touching any generated
        // loose-item task so each completed mixed box frees one real inventory slot.
        List<int[]> writableImports = orderedWritableImportDestinations();
        int[] emptyShellDestination = writableImports.isEmpty()
                ? copyPos(mixedTask.destination())
                : copyPos(writableImports.get(0));
        MoveTask emptyShellTask = new MoveTask(
                currentPlayerPosition(),
                emptyShellDestination,
                mixedTask.itemId(),
                EMPTY_SHULKER_STAGING_FILTER,
                true);
        totalTasks += batch.size() + recoveredCargoTasks.size() + 1;
        emitProgressMilestoneIfCrossed();

        currentTask = null;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        // Loose contents are a final phase. Putting these at the head previously made a full
        // shulker inventory reopen the same import chest once per item class, fail every take,
        // and only then return to the remaining boxes.
        if (recoveredCargoTasks.isEmpty()) {
            batch.forEach(consolidationQueue::addLast);
        } else {
            List<MoveTask> recoveryBatch = new ArrayList<>(recoveredCargoTasks);
            recoveryBatch.addAll(batch);
            prependConsolidationTasks(recoveryBatch);
        }
        mixedBatchConsolidationMode = false;
        consolidationMode = false;
        taskQueue.addFirst(emptyShellTask);
        emit("organize_empty_shulker_staging_queued", Map.of(
                "reason", "mixed_shulker_decomposed",
                "generated_loose_tasks_deferred",
                batch.size() + recoveredCargoTasks.size()
        ));
        advanceToNextTask();
    }

    private void beginMixedShellVerification(List<MoveTask> inventoryCargoTasks) {
        boolean firstWait = !mixedShellVerificationActive;
        if (firstWait) {
            mixedShellVerificationActive = true;
            mixedShellVerificationTicks = 0;
            mixedShellVerificationCargo = inventoryCargoTasks == null ? List.of() : List.copyOf(inventoryCargoTasks);
        }
        BARITONE.stop();
        state = State.MIXED_SHELL_VERIFY;
        if (!persistDurableCheckpoint(state)) {
            failMixedShellVerification("mixed_shulker_inventory_checkpoint_unavailable");
            return;
        }
        if (firstWait) {
            emit("organize_mixed_shell_inventory_wait", mixedShellVerificationDetails("awaiting_inventory_update"));
        }
    }

    private void tickMixedShellVerification() {
        if (state != State.MIXED_SHELL_VERIFY) return;
        if (!mixedShellVerificationActive) {
            failMixedShellVerification("mixed_shulker_verification_state_missing");
            return;
        }
        // Collection and inventory updates can arrive on different ticks. Do not move cargo
        // or count completion until the recovered empty shell is usable in a live slot.
        if (currentTask == null || !currentTask.mixedDecomposition()
                || !mixedBoxDrained || temporaryShulkerOutstanding
                || isShulkerAtPosition(shulkerPlacePos)
                || temporaryShulkerPickupFingerprint != null
                    && !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)) {
            finishMixedShulkerDecomposition(mixedShellVerificationCargo);
            return;
        }
        if (recoveredEmptyShellAvailable(currentTask)) {
            finishMixedShulkerDecomposition(mixedShellVerificationCargo);
            return;
        }
        mixedShellVerificationTicks = Math.min(MIXED_SHELL_VERIFY_TIMEOUT_TICKS, mixedShellVerificationTicks + 1);
        if (mixedShellVerificationTicks >= MIXED_SHELL_VERIFY_TIMEOUT_TICKS) {
            if (!continueMixedCargoAfterLostEmptyShell()) {
                failMixedShellVerification("mixed_shulker_inventory_sync_timeout");
            }
        } else if (mixedShellVerificationTicks % 20 == 0 && !persistDurableCheckpoint(state)) {
            failMixedShellVerification("mixed_shulker_inventory_checkpoint_unavailable");
        }
    }

    /** Continue with intact loose cargo when a confirmed empty pickup never reaches inventory. */
    private boolean continueMixedCargoAfterLostEmptyShell() {
        if (currentTask == null || !currentTask.mixedDecomposition()
                || !mixedBoxDrained || temporaryShulkerOutstanding
                || isShulkerAtPosition(shulkerPlacePos)
                || !temporaryShulkerPickupConfirmed
                || !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)
                || mixedShellVerificationCargo.isEmpty()) {
            return false;
        }

        MoveTask mixedTask = currentTask;
        List<String> storageClasses = mixedTask.mixedContents().keySet().stream()
                .map(StorageClassPolicy::exact)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<MoveTask> stagedSources = stagedMixedBatchTasks(
                storageClasses, mixedTask.destination());
        List<MoveTask> inventoryCargo = List.copyOf(mixedShellVerificationCargo);

        completedTasks++;
        decomposedMixedShulkers++;
        generatedLooseTasks += stagedSources.size() + inventoryCargo.size();
        totalTasks += stagedSources.size() + inventoryCargo.size();
        emitProgressMilestoneIfCrossed();

        currentTask = null;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        List<MoveTask> recoveryBatch = new ArrayList<>(inventoryCargo);
        recoveryBatch.addAll(stagedSources);
        prependConsolidationTasks(recoveryBatch);
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        emit("organize_empty_shulker_substitution_required", Map.of(
                "reason", "confirmed_empty_pickup_missing_after_inventory_timeout",
                "disposition", "source_replacement_shell_and_pack_preserved_cargo",
                "inventory_cargo_classes", inventoryCargo.size(),
                "staged_source_tasks", stagedSources.size(),
                "unrelated_inventory_shulkers", countShulkerBoxesInInventory()
        ));
        advanceConsolidation();
        return true;
    }

    private Map<String, Object> mixedShellVerificationDetails(String reason) {
        return Map.of(
                "reason", reason,
                "wait_ticks", mixedShellVerificationTicks,
                "timeout_ticks", MIXED_SHELL_VERIFY_TIMEOUT_TICKS,
                "pickup_packet_confirmed", temporaryShulkerPickupConfirmed,
                "pickup_shape", temporaryShulkerPickupFingerprint == null ? "unknown"
                        : isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint) ? "empty" : "nonempty",
                "expected_empty_shells_observed", currentTask == null ? 0 : countEmptyPackingShells(currentTask.itemId()),
                "inventory_shulkers_observed", countShulkerBoxesInInventory(),
                "pending_cargo_tasks", mixedShellVerificationCargo.size());
    }

    private void failMixedShellVerification(String reason) {
        emit("organize_mixed_shell_verification_failed", mixedShellVerificationDetails(reason));
        abortWithCargo(reason, "The recovered mixed shulker could not be verified in inventory; the task has not advanced.");
    }

    private void resetMixedShellVerification() {
        mixedShellVerificationActive = false;
        mixedShellVerificationTicks = 0;
        mixedShellVerificationCargo = List.of();
    }

    /**
     * Cleanup transaction for a temporary shulker which was placed by this organizer. No
     * overflow, queue advance, or manual stop may make the bot leave until the block has been
     * broken and its item has returned to inventory.
     */
    private void beginTemporaryShulkerRecovery(String trigger) {
        restoreStationWalkSettings();
        closeCurrentContainer();
        BARITONE.stop();
        shulkerRecoveryTrigger = trigger;
        shulkerRecoveryBreakAttempts = 0;
        shulkerBreakFuture = null;
        shulkerBreakAttemptGate.clear();
        shulkerTicks = 0;
        resetShulkerPickupSweep();
        state = isShulkerAtPosition(shulkerPlacePos)
                ? State.SHULKER_RECOVERY_BREAKING
                : State.SHULKER_RECOVERY_PICKUP;
        persistDurableCheckpoint(state);
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
            shulkerBreakAttemptGate.clear();
            shulkerTicks = 0;
            resetShulkerPickupSweep();
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
            shulkerBreakAttemptGate.clear();
            shulkerTicks = 0;
        }

        switch (shulkerBreakAttemptGate.next(shulkerBreakFuture != null)) {
            case RESET -> {
                BARITONE.stop();
                if (!BaritoneCompat.resetBlockBreakingState()) {
                    emit("organize_target_failed", Map.of(
                            "reason", "shulker_break_preflight_unavailable"));
                }
            }
            case SUBMIT -> {
                shulkerBreakFuture = BaritoneCompat.breakBlock(
                        shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], true);
                ownInteraction(shulkerBreakFuture);
            }
            case WAIT -> { }
        }
    }

    private void tickShulkerRecoveryPickup() {
        shulkerTicks++;

        // If the server restored the block after a rejected/rolled-back break, recover it as a
        // block again rather than assuming the item entity exists.
        if (isShulkerAtPosition(shulkerPlacePos)) {
            shulkerBreakFuture = null;
            shulkerBreakAttemptGate.clear();
            shulkerTicks = 0;
            state = State.SHULKER_RECOVERY_BREAKING;
            return;
        }

        // Do not declare cleanup complete while a place request can still succeed late.
        if (shulkerPlaceFuture != null && !shulkerPlaceFuture.isDone()) {
            if (shulkerTicks >= SHULKER_RECOVERY_PICKUP_TIMEOUT_TICKS) {
                abortTemporaryShulkerRecovery("temporary_shulker_placement_outcome_unknown");
            }
            return;
        }

        TemporaryShulkerRecoveryStatus.Assessment recovery = temporaryShulkerRecoveryStatus();
        if (shulkerTicks >= PICKUP_DELAY_TICKS && recovery.inventoryRecovered()) {
            BARITONE.stop();
            temporaryShulkerOutstanding = false;
            String recoveredTrigger = Objects.toString(shulkerRecoveryTrigger, "unknown");
            emit("organize_recovery_completed", Map.of(
                    "reason", "temporary_shulker_recovered",
                    "trigger", recoveredTrigger,
                    "shulker_position", posString(shulkerPlacePos)
            ));
            if (ORPHANED_WORKSITE_RECOVERY.equals(recoveredTrigger)) {
                resetTemporaryShulkerState();
                state = State.SHULKER_SELECTING;
                shulkerTicks = 0;
                persistDurableCheckpoint(state);
                return;
            }
            if (stopAfterShulkerRecovery) {
                if ("manual_stop".equals(recoveredTrigger)) {
                    resetTemporaryShulkerState();
                    finishStop();
                } else if (isResumableStagingCapacityFailure(recoveredTrigger)) {
                    finishResumableStagingStop(recoveredTrigger);
                } else {
                    resetTemporaryShulkerState();
                    finishStop(recoveredTrigger);
                }
                return;
            }
            if (mixedDecompositionMode) {
                shulkerPlacePos = null;
                resetShulkerPlacementAttempt();
                shulkerBreakFuture = null;
                shulkerBreakAttemptGate.clear();
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
            boolean packedShulkerRecovered = shouldResumePackedShulkerHandoff(
                    false, hasPackedShulkerInInventory() || pickupEvidenceMatchesPackedShulker(),
                    packDestination != null);
            resetTemporaryShulkerState();
            if (packedShulkerRecovered) {
                emit("organize_recovery_completed", Map.of(
                        "reason", "packed_shulker_recovered",
                        "trigger", recoveredTrigger,
                        "disposition", "resume_packed_shulker_handoff"
                ));
                walkToPackedShulkerDestination(packDestination);
                return;
            }
            startOverflowAfterShulkerCleanup();
            return;
        }

        pathToShulkerDrop();

        if (shulkerTicks >= SHULKER_RECOVERY_PICKUP_TIMEOUT_TICKS) {
            BARITONE.stop();
            abortTemporaryShulkerRecovery("temporary_shulker_pickup_recovery_failed");
        }
    }

    private void abortTemporaryShulkerRecovery(String reason) {
        State failedState = state;
        rememberFailure(reason, failedState);
        int inventoryShulkers = countShulkerBoxesInInventory();
        int compatibleShulkers = countCompatibleBulkShulkersInInventory(packItemId);
        TemporaryShulkerRecoveryStatus.Assessment recovery = temporaryShulkerRecoveryStatus();
        info("Temporary shulker recovery failed at " + posString(shulkerPlacePos)
                + "; stopping in place for manual recovery.");
        BARITONE.stop();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        boolean checkpointPreserved = persistDurableCheckpoint(failedState)
                || hasDurableCheckpoint();
        emit("organize_failed", Map.ofEntries(
                Map.entry("reason", reason),
                Map.entry("failed_state", failedState.name()),
                Map.entry("trigger", Objects.toString(shulkerRecoveryTrigger, "unknown")),
                Map.entry("shulker_position", posString(shulkerPlacePos)),
                Map.entry("manual_intervention_required", true),
                Map.entry("terminal", true),
                Map.entry("cargo_preserved", recovery.cargoPreserved()),
                Map.entry("cargo_state", recovery.cargoState().name().toLowerCase(Locale.ROOT)),
                Map.entry("world_block_present", recovery.blockPresent()),
                Map.entry("inventory_recovered", recovery.inventoryRecovered()),
                Map.entry("pickup_packet_confirmed", temporaryShulkerPickupConfirmed),
                Map.entry("expected_box_shape_present", hasRecoveredMixedShulkerInInventory()
                        || (!mixedDecompositionMode && hasPackedShulkerInInventory())),
                Map.entry("inventory_shulkers_expected", shulkerInventoryCountBeforePlacement),
                Map.entry("inventory_shulkers_observed", inventoryShulkers),
                Map.entry("compatible_shulkers_expected",
                        Math.max(1, compatibleShulkerCountBeforePlacement)),
                Map.entry("compatible_shulkers_observed", compatibleShulkers),
                Map.entry("pickup_sweep_attempts", shulkerPickupSweepAttempt),
                Map.entry("pickup_last_target", shulkerPickupLastTarget == null
                        ? "none" : posString(shulkerPickupLastTarget)),
                Map.entry("checkpoint_preserved", checkpointPreserved),
                Map.entry("recovery_disposition", checkpointPreserved
                        ? "resume_after_manual_recovery" : "manual_recovery_without_checkpoint")
        ));
        state = State.FAILED;
    }

    static boolean isResumableStagingCapacityFailure(String reason) {
        return "mixed_staging_full".equals(reason)
                || "mixed_staging_destination_missing".equals(reason);
    }

    static State stagingCapacityResumeState(boolean cargoPending, boolean boxDrained) {
        if (cargoPending) return State.MIXED_STAGE_WALK;
        return boxDrained ? State.MIXED_RETURN_WALK : State.SHULKER_STATION_WALK;
    }

    /** Stop safely without discarding a multi-hour plan after import capacity is exhausted. */
    private void finishResumableStagingStop(String reason) {
        rememberFailure(reason, state);
        BARITONE.stop();
        clearOwnedAutomation();
        closeCurrentContainer();

        temporaryShulkerOutstanding = false;
        stopAfterShulkerRecovery = false;
        shulkerRecoveryTrigger = null;
        shulkerRecoveryBreakAttempts = 0;
        resetShulkerPlacementAttempt();
        shulkerBreakFuture = null;
        shulkerBreakAttemptGate.clear();
        resetShulkerPickupSweep();
        boolean recoveredBoxIdentified = refreshRecoveredMixedShulkerFingerprint();
        shulkerPlacePos = reconciliationWorksite;

        if (recoveredBoxIdentified && continueRecoveredMixedBoxWithInventoryCargo()) {
            emit("organize_capacity_recovery_continued", Map.of(
                    "reason", reason,
                    "disposition", "pack_recovered_mixed_cargo"
            ));
            return;
        }

        if (!recoveredBoxIdentified) {
            temporaryShulkerPickupConfirmed = false;
            temporaryShulkerPickupFingerprint = null;
            temporaryShulkerPickupStorageKey = null;
            temporaryShulkerPickupStack = null;
        }

        restoreBaritoneBreaking();
        restorePlaceBlockSneak();

        State resumeState = stagingCapacityResumeState(
                !mixedCargoSlots.isEmpty(), mixedBoxDrained);
        boolean checkpointPreserved = recoveredBoxIdentified
                && persistDurableCheckpoint(resumeState);
        if (!recoveredBoxIdentified) clearDurableJournal();
        state = State.FAILED;
        emit("organize_failed", Map.of(
                "reason", reason,
                "terminal", true,
                "cargo_preserved", true,
                "cargo_state", "inventory",
                "checkpoint_preserved", checkpointPreserved,
                "temporary_shulker_recovered", true,
                "recovered_shulker_identified", recoveredBoxIdentified,
                "resume_state", resumeState.name(),
                "recovery_disposition", checkpointPreserved
                        ? "clear_or_add_import_storage_then_resume"
                        : "fresh_scan_required"
        ));
        info(checkpointPreserved
                ? "Organizer paused with cargo recovered. Clear or add import storage, then run /stash organize resume."
                : "Organizer stopped after recovering the temporary shulker, but its checkpoint could not be saved.");
    }

    private boolean continueRecoveredMixedBoxWithInventoryCargo() {
        if (!mixedDecompositionMode || currentTask == null || mixedCargoSlots.isEmpty()) {
            return false;
        }
        List<MoveTask> pendingCargo = pendingMixedInventoryCargoTasks();
        if (pendingCargo.isEmpty()) return false;

        // These slots were filled by tickShulkerEmptying into known-empty inventory slots.
        // A later keep-list refresh can classify the same item type as reserved, but that
        // must not erase the transaction ownership recorded in mixedCargoSlots. Release only
        // those exact slots; unrelated keep-list inventory remains protected.
        Set<Integer> claimedProtectedSlots = mixedCargoSlots.stream()
                .filter(protectedInventorySlots::contains)
                .collect(Collectors.toCollection(TreeSet::new));
        protectedInventorySlots.removeAll(claimedProtectedSlots);
        if (!claimedProtectedSlots.isEmpty()) {
            emit("organize_mixed_cargo_ownership_restored", Map.of(
                    "reason", "transaction_slots_override_keep_snapshot",
                    "claimed_slots", claimedProtectedSlots.size(),
                    "cargo_classes", pendingCargo.size()
            ));
        }
        ShulkerClassification checkpointOwned = claimCheckpointOwnedMixedShulker();

        MoveTask originalTask = currentTask;
        String recoveredFingerprint = temporaryShulkerPickupFingerprint != null
                ? temporaryShulkerPickupFingerprint
                : currentTask.shulkerContentFilter();
        boolean emptyPickupConfirmed = isEmptyShulkerFingerprint(
                temporaryShulkerPickupFingerprint);
        boolean emptyCheckpointVerified = isEmptyShulkerFingerprint(recoveredFingerprint);
        if (emptyCheckpointVerified) {
            mixedBoxDrained = true;
            if (emptyPickupConfirmed || checkpointOwned != null
                    || hasRecoveredMixedShulkerInInventory()) {
                currentTask = originalTask
                        .withShulkerSnapshot(
                                recoveredFingerprint, originalTask.mixedContents())
                        .markAlreadyInInventory();
                finishMixedShulkerDecomposition(preferAssignedStorageClasses(
                        pendingCargo, columnAssignment.keySet()));
                return true;
            }
            mixedBoxDrained = false;
        }

        if (shouldReuseRecoveredMixedShell(
                mixedBoxDrained, true, checkpointOwned != null
                        || hasRecoveredMixedShulkerInInventory())) {
            finishMixedShulkerDecomposition(preferAssignedStorageClasses(
                    pendingCargo, columnAssignment.keySet()));
            return true;
        }

        ShulkerClassification recovered = currentMixedShulkerClassificationInInventory();
        if (recovered == null) return false;
        if (recovered.kind() == ShulkerClassification.Kind.EMPTY) {
            mixedBoxDrained = true;
            currentTask = originalTask
                    .withShulkerSnapshot(recovered.fingerprint(), originalTask.mixedContents())
                    .markAlreadyInInventory();
            finishMixedShulkerDecomposition(pendingCargo);
            return true;
        }

        currentTask = originalTask
                .withShulkerSnapshot(recovered.fingerprint(), recovered.contents())
                .markAlreadyInInventory();
        continuePartialMixedBoxWithInventoryCargo(originalTask, recovered, pendingCargo);
        return true;
    }

    static boolean isEmptyShulkerFingerprint(String fingerprint) {
        return EMPTY_SHULKER_FINGERPRINT.equals(fingerprint);
    }

    static boolean shouldReuseRecoveredMixedShell(
            boolean boxDrained,
            boolean cargoPending,
            boolean emptyBoxRecovered) {
        return boxDrained && cargoPending && emptyBoxRecovered;
    }

    private List<MoveTask> pendingMixedInventoryCargoTasks() {
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null || currentTask == null) return List.of();

        int[] source = currentPlayerPosition();
        Map<String, MoveTask> unique = new LinkedHashMap<>();
        for (int rawSlot : mixedCargoSlots) {
            // mixedCargoSlots is a durable transaction ledger. Its ownership is stronger
            // evidence than the derived keep-list snapshot, which may have been refreshed
            // after this exact stack was extracted from the temporary shulker.
            if (rawSlot < 9 || rawSlot > 44) continue;
            ItemStack stack = inventory.getItemStack(rawSlot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String storageClass = StorageClassPolicy.exact(itemIdFromStack(stack));
            if (storageClass == null) continue;
            Column column = columnAssignment.get(storageClass);
            int[] destination = column == null
                    ? copyPos(currentTask.destination())
                    : copyPos(column.top());
            unique.putIfAbsent(storageClass, MoveTask.mixedBatchInInventory(
                    source, destination, storageClass));
        }
        return List.copyOf(unique.values());
    }

    private void continuePartialMixedBoxWithInventoryCargo(
            MoveTask originalTask,
            ShulkerClassification recovered,
            List<MoveTask> pendingCargo) {
        MoveTask recoveredTask = currentTask;
        List<String> removedClasses = removedStorageClasses(
                originalTask.mixedContents(), recovered.contents());
        List<MoveTask> stagedCargo = stagedMixedBatchTasks(removedClasses, originalTask.destination());
        List<MoveTask> orderedPending = preferAssignedStorageClasses(
                preferStorageClass(
                        pendingCargo,
                        recovered.kind() == ShulkerClassification.Kind.BULK
                                ? recovered.storageKey()
                                : null),
                columnAssignment.keySet());

        boolean reuseRecoveredBulk = recovered.kind() == ShulkerClassification.Kind.BULK
                && orderedPending.stream().anyMatch(task -> ItemIdentifier.contentItemIdsMatch(
                        recovered.storageKey(), task.itemId()))
                && recovered.contents().values().stream().mapToInt(Integer::intValue).sum()
                        < LaneStorageCapacity.itemCapacityFor(
                                recovered.storageKey()).itemsPerShulker();

        if (recovered.kind() == ShulkerClassification.Kind.MIXED) {
            taskQueue.addFirst(recoveredTask);
        } else if (reuseRecoveredBulk) {
            completedTasks++;
            decomposedMixedShulkers++;
        } else {
            Column column = columnAssignment.get(recovered.storageKey());
            int[] destination = column == null
                    ? copyPos(originalTask.destination())
                    : copyPos(column.top());
            taskQueue.addFirst(new MoveTask(
                    currentPlayerPosition(), destination, originalTask.itemId(),
                    recovered.storageKey(), true));
            decomposedMixedShulkers++;
        }

        generatedLooseTasks += orderedPending.size() + stagedCargo.size();
        totalTasks += orderedPending.size() + stagedCargo.size();
        emitProgressMilestoneIfCrossed();

        currentTask = null;
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        List<MoveTask> recoveryBatch = new ArrayList<>(orderedPending);
        recoveryBatch.addAll(stagedCargo);
        prependConsolidationTasks(recoveryBatch);
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        emit("organize_partial_mixed_shulker_deferred", Map.of(
                "remaining_kind", recovered.kind().name().toLowerCase(Locale.ROOT),
                "remaining_storage_class", Objects.toString(recovered.storageKey(), "mixed"),
                "inventory_cargo_classes", orderedPending.size(),
                "staged_source_tasks", stagedCargo.size(),
                "recovered_box_reused", reuseRecoveredBulk
        ));
        advanceConsolidation();
    }

    private List<MoveTask> mixedBatchTasks(
            Collection<String> storageClasses,
            Collection<int[]> sources,
            int[] fallbackDestination) {
        if (storageClasses == null || storageClasses.isEmpty()
                || sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<MoveTask> tasks = new ArrayList<>();
        for (String storageClass : storageClasses) {
            Column column = columnAssignment.get(storageClass);
            int[] destination = column == null
                    ? copyNullablePos(fallbackDestination)
                    : copyPos(column.top());
            if (destination == null) continue;
            if (column == null) {
                stagingStorageClassesPlanned.add(storageClass);
                stagingReason = stagingReason == null
                        ? "mixed_shulker_lane_shortfall"
                        : stagingReason;
            }
            for (int[] source : sources) {
                tasks.add(MoveTask.mixedBatch(
                        copyPos(source), copyPos(destination), storageClass));
            }
        }
        return List.copyOf(tasks);
    }

    private int[] canonicalStagingPosition(int[] position) {
        ContainerEntry entry = index.get(position[0], position[1], position[2]);
        return entry != null && entry.isDouble() && entry.inventoryIdentityKnown()
                ? new int[]{entry.inventoryX(), entry.inventoryY(), entry.inventoryZ()}
                : copyPos(position);
    }

    private List<MoveTask> stagedMixedBatchTasks(Collection<String> classes, int[] fallbackDestination) {
        List<int[]> legacySources = mixedStagingUsedDestinations.isEmpty()
                ? List.of(copyPos(fallbackDestination)) : mixedStagingUsedDestinations;
        List<MoveTask> tasks = new ArrayList<>();
        for (MixedStagingLedger.Source source : mixedStagingLedger.sources(classes, legacySources)) {
            tasks.addAll(mixedBatchTasks(List.of(source.itemId()), List.of(source.position()), fallbackDestination));
        }
        var ledger = mixedStagingLedger.snapshot();
        emit("organize_mixed_followups_planned", Map.of(
                "source_tasks", tasks.size(),
                "confirmed_pairs", ledger.confirmed().size(),
                "uncertain_pairs", ledger.uncertain().size(),
                "legacy_fallback", !ledger.exact()));
        return List.copyOf(tasks);
    }

    static List<String> removedStorageClasses(
            Map<String, Integer> original,
            Map<String, Integer> remaining) {
        Map<String, Long> before = MixedShulkerPlaybook.aggregateDemand(List.of(
                original == null ? Map.of() : original));
        Map<String, Long> after = MixedShulkerPlaybook.aggregateDemand(List.of(
                remaining == null ? Map.of() : remaining));
        return before.entrySet().stream()
                .filter(entry -> entry.getValue() > after.getOrDefault(entry.getKey(), 0L))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    static List<MoveTask> preferStorageClass(
            Collection<MoveTask> tasks,
            String preferredStorageClass) {
        if (tasks == null || tasks.isEmpty()) return List.of();
        List<MoveTask> ordered = new ArrayList<>(tasks);
        if (preferredStorageClass != null) {
            ordered.sort(Comparator.comparingInt(task -> ItemIdentifier.contentItemIdsMatch(
                    preferredStorageClass, task.itemId()) ? 0 : 1));
        }
        return List.copyOf(ordered);
    }

    static List<MoveTask> preferAssignedStorageClasses(
            Collection<MoveTask> tasks,
            Set<String> assignedStorageClasses) {
        if (tasks == null || tasks.isEmpty()) return List.of();
        Set<String> assigned = assignedStorageClasses == null
                ? Set.of()
                : assignedStorageClasses;
        List<MoveTask> ordered = new ArrayList<>(tasks);
        // ArrayList's stable sort preserves any compatible-partial preference inside each
        // group while ensuring lane-backed cargo is packed before import-only cargo.
        ordered.sort(Comparator.comparingInt(task ->
                assigned.contains(task.itemId()) ? 0 : 1));
        return List.copyOf(ordered);
    }

    private void prependConsolidationTasks(List<MoveTask> tasks) {
        if (tasks == null) return;
        for (int index = tasks.size() - 1; index >= 0; index--) {
            consolidationQueue.addFirst(tasks.get(index));
        }
    }

    private ShulkerClassification currentMixedShulkerClassificationInInventory() {
        if (currentTask == null || !currentTask.mixedDecomposition()) return null;
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) return null;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = inventory.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (matchesShulkerTaskFilter(currentTask, classification)) {
                return classification;
            }
        }
        return null;
    }

    /** A partially drained box has a new fingerprint; persist it before a later resume. */
    private boolean refreshRecoveredMixedShulkerFingerprint() {
        if (!mixedDecompositionMode || currentTask == null) return false;
        if (mixedBoxDrained) return true;
        if (temporaryShulkerPickupFingerprint != null) {
            currentTask = currentTask
                    .withShulkerFingerprint(temporaryShulkerPickupFingerprint)
                    .markAlreadyInInventory();
            return true;
        }
        ShulkerClassification checkpointOwned = claimCheckpointOwnedMixedShulker();
        if (checkpointOwned != null) {
            currentTask = currentTask
                    .withShulkerFingerprint(checkpointOwned.fingerprint())
                    .markAlreadyInInventory();
            return true;
        }
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) return false;

        List<ShulkerClassification> candidates = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = inventory.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (currentTask.shulkerContentFilter().equals(classification.fingerprint())) {
                // Capacity recovery happens after at least one stack left the box. An
                // unchanged fingerprint therefore belongs to a different, identical box.
                continue;
            }
            candidates.add(classification);
        }
        if (candidates.size() == 1) {
            String previousFingerprint = currentTask.shulkerContentFilter();
            currentTask = currentTask
                    .withShulkerFingerprint(candidates.get(0).fingerprint())
                    .markAlreadyInInventory();
            emit("organize_mixed_shulker_identity_refreshed", Map.of(
                    "reason", "unique_live_partial_box",
                    "previous_fingerprint", Objects.toString(previousFingerprint, "none"),
                    "live_fingerprint", candidates.get(0).fingerprint(),
                    "remaining_kind", candidates.get(0).kind().name().toLowerCase(Locale.ROOT),
                    "remaining_units", candidates.get(0).contents().values().stream()
                            .mapToInt(Integer::intValue).sum(),
                    "disposition", "continue_live_partial_box"
            ));
            return true;
        }
        emit("organize_target_failed", Map.of(
                "reason", "recovered_mixed_shulker_identity_ambiguous",
                "candidate_count", candidates.size()
        ));
        return false;
    }

    /** Finish a restored capacity checkpoint after its final loose stack was staged. */
    private boolean finishRecoveredEmptyMixedShellCheckpoint() {
        if (!mixedDecompositionMode || currentTask == null
                || !currentTask.alreadyInInventory()
                || !currentTask.mixedDecomposition()
                || !mixedCargoSlots.isEmpty()
                || !isEmptyShulkerFingerprint(currentTask.shulkerContentFilter())
                || temporaryShulkerOutstanding) {
            return false;
        }
        ShulkerClassification recovered = claimCheckpointOwnedMixedShulker();
        if (recovered == null || recovered.kind() != ShulkerClassification.Kind.EMPTY) {
            return false;
        }
        mixedBoxDrained = true;
        emit("organize_capacity_recovery_continued", Map.of(
                "reason", "restored_mixed_cargo_fully_staged",
                "disposition", "finish_recovered_empty_shell"
        ));
        finishMixedShulkerDecomposition();
        return true;
    }

    /**
     * A recovered-box checkpoint is stronger ownership evidence than a later keep snapshot.
     * Claim only a unique exact task match so unrelated user shulkers remain protected.
     */
    private ShulkerClassification claimCheckpointOwnedMixedShulker() {
        if (currentTask == null || !currentTask.alreadyInInventory()
                || !currentTask.mixedDecomposition()
                || !isShulkerBoxItem(currentTask.itemId())
                || currentTask.shulkerContentFilter() == null) {
            return null;
        }
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) return null;

        int candidateSlot = -1;
        ShulkerClassification candidate = null;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack == null || stack.getAmount() != 1
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (!matchesShulkerTaskFilter(currentTask, classification)) continue;
            if (candidateSlot >= 0) return null;
            candidateSlot = slot;
            candidate = classification;
        }
        if (candidateSlot < 0) return null;
        boolean staleProtection = protectedInventorySlots.remove(candidateSlot);
        if (staleProtection) {
            emit("organize_mixed_shell_ownership_restored", Map.of(
                    "reason", "checkpoint_task_overrides_keep_snapshot",
                    "slot", candidateSlot
            ));
        }
        return candidate;
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
        if (!stagingForPackingSupply && currentTask != null && !mixedDecompositionMode
                && !currentTask.mixedDecomposition() && currentTask.shulkerContentFilter() == null
                && !isShulkerBoxItem(currentTask.itemId()) && currentTask.itemId().equals(packItemId)) {
            // Every loose packing fallback needs receipts, including placement/open failures.
            stagingForPackingSupply = true;
            supplyStagingLedger.reset();
            emit("organize_packing_fallback_started", Map.of(
                    "reason", "packing_action_fallback",
                    "cargo_units_remaining", taskCargo.remaining(),
                    "disposition", "journal_import_handoff_then_defer_packing"));
        }
        // Same reasoning as advanceToNextTask() — a container (e.g. a temp placed shulker)
        // may still be open when a failure path routes here.
        closeCurrentContainer();
        overflowTriedDestinations.clear();

        overflowChestPos = findOverflowChest();
        if (overflowChestPos == null) {
            abortWithCargo("overflow_chest_missing_with_cargo",
                    "No registered import chest has usable capacity. Current cargo is preserved in inventory.");
            return;
        }

        info("Overflow: depositing remaining items into overflow chest.");
        walkTarget = overflowChestPos;
        state = State.OVERFLOW_WALKING;
        openWaitTicks = 0;
        containerDataReceived = false;
        if (stagingForPackingSupply && journalJobId != null && !persistDurableCheckpoint(state)) {
            abortWithCargo("packing_supply_checkpoint_unavailable",
                    "The packing fallback checkpoint could not be saved; cargo remains in inventory.");
        }
    }

    private void tickOverflowOpening() {
        if (containerDataReceived) {
            if (awaitLiveOpenContainer() == null) {
                if (containerCacheReadyTimedOut()) {
                    emit("organize_target_failed", openFailureDetails(
                            "overflow_cache_sync_timeout"));
                    abortWithCargo("overflow_cache_unavailable_with_cargo",
                            "The overflow chest opened without a usable inventory window; cargo is preserved.");
                }
                return;
            }
            BARITONE.stop();
            state = State.OVERFLOW_DEPOSITING;
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = 0;
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;

        if (openWaitTicks > organizerOpenTimeoutTicks()) {
            info("Timeout opening overflow chest.");
            emit("organize_target_failed", openFailureDetails("overflow_open_timeout"));
            BARITONE.stop();
            abortWithCargo("overflow_unreachable_with_cargo",
                    "The overflow chest could not be opened; cargo is preserved in inventory.");
            return;
        }

        if (openWaitTicks == 1 || openWaitTicks % OPEN_RETRY_INTERVAL_TICKS == 0) {
            requestContainerInteraction(walkTarget);
        }
    }

    private void tickOverflowDepositing() {
        if (actionCooldown > 0) { actionCooldown--; return; }

        Container open = getLiveOpenContainer();
        if (open == null) {
            abortWithCargo("overflow_container_lost_with_cargo",
                    "The overflow inventory window was lost. Current cargo is preserved in inventory.");
            return;
        }

        int chestSlots = getOpenContainerSlotCount(open);
        if (pendingOverflowSplit != null) {
            tickPendingOverflowSplit(open);
            return;
        }
        QuickMovePoll transfer = pollPendingQuickMove();
        if (transfer.outcome() != QuickMoveOutcome.NONE) {
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED
                    || transfer.outcome() == QuickMoveOutcome.CONFIRMED_PARTIAL) {
                if (!confirmOverflowCargoTransfer(transfer.movedAmount())) return;
            }
            if (transfer.outcome() == QuickMoveOutcome.CONFIRMED_DRAINED) {
                actionSlotIndex++;
            }
            if (transfer.outcome() != QuickMoveOutcome.WAITING) {
                actionCooldown = config.organizerClickCooldownTicks;
            }
            return;
        }

        // A packed box can reach overflow only because its pickup arrived before Zenith's raw
        // inventory cache. The live player section of this window is authoritative: recover
        // the unique transaction box and send it to its real destination instead of looking
        // for loose cargo that was already sealed inside it.
        if (resumePackedShulkerFromOverflowWindow(open, chestSlots)) return;

        // Overflow is still one transaction. Move only cargo owned by currentTask; sweeping
        // every unprotected slot lets a failed handoff silently consume unrelated recovery
        // shells and later tasks.
        while (actionSlotIndex < 45) {
            if (isProtectedInventorySlot(actionSlotIndex)) {
                actionSlotIndex++;
                continue;
            }
            int containerSlotIndex = rawPlayerSlotToWindowSlot(chestSlots, actionSlotIndex);
            ItemStack stack = open.getItemStack(containerSlotIndex);
            if (stack != null && stack.getAmount() > 0 && currentTaskOwnsStack(stack)) {
                ContainerAdmission admission = inspectContainerAdmission(
                        open, chestSlots, stack);
                if (!admission.canAccept()) {
                    if (switchOverflowDestination(
                            admission, cargoRoutingKey(stack), itemIdFromStack(stack))) return;
                    // Loose packing cargo can hit this path when placing its temporary
                    // shulker was rejected. Preserve that cargo as an inventory packing task,
                    // compact one staged import batch, then retry it before normal work.
                    if (deferLoosePackingOverflowForCapacityRecovery()) {
                        return;
                    }
                    // A packed, lane-less box can arrive here after its normal deposit path
                    // falls back to overflow. Apply the same capacity-recovery playbook after
                    // the bounded import sample is rejected instead of terminating the job.
                    if (isPackedImportHandoffTask(currentTask)
                            && deferCurrentPackedImportHandoffForCapacityRecovery()) {
                        return;
                    }
                    if (rollbackBlockedMixedShulkerToSource(
                            "overflow_import_capacity_exhausted")) {
                        return;
                    }
                    abortWithCargo("overflow_full_with_cargo",
                            "No registered import chest can accept the current task cargo. It remains in inventory.");
                    return;
                }
                if (stagingForPackingSupply) {
                    supplyStagingLedger.begin(itemIdFromStack(stack), canonicalStagingPosition(overflowChestPos));
                    if (journalJobId != null && !persistDurableCheckpoint(state)) {
                        abortWithCargo("packing_supply_checkpoint_unavailable",
                                "The staging checkpoint could not be saved; cargo remains in inventory.");
                        return;
                    }
                }
                submitQuickMove(containerSlotIndex);
                actionCooldown = config.organizerClickCooldownTicks;
                return;
            }
            actionSlotIndex++;
        }

        // A restored legacy task can claim that loose cargo is already aboard after that
        // cargo was packed or staged by an earlier transaction. This live window proves the
        // player inventory is empty for the exact class. Retire the stale obligation instead
        // of treating a zero-unit handoff as a fatal overflow failure.
        if (!taskCargo.hasAcquiredCargo() && stagingForPackingSupply
                && currentTask != null && currentTask.alreadyInInventory()
                && currentTask.mixedBatchConsolidation()
                && countItemInOpenPlayerInventory(open, chestSlots, currentTask.itemId()) == 0
                && retireMissingInventoryPackingCargo("overflow_live_window", true)) {
            return;
        }

        if (!taskCargo.fullyDeposited()) {
            if (trySubmitPartialOverflowDeposit(open, chestSlots)) return;
            abortWithCargo("overflow_cargo_not_found",
                    "Overflow could not prove a complete handoff for the current task cargo. The checkpoint was preserved for inspection.");
            return;
        }
        closeCurrentContainer();
        if (stagingForPackingSupply && !finishSupplyStaging()) return;
        advanceToNextTask();
    }

    private boolean confirmOverflowCargoTransfer(int observedMoved) {
        int credited = Math.min(taskCargo.remaining(), Math.max(0, observedMoved));
        if (credited <= 0) return true;
        taskCargo.recordDeposited(credited);
        recordWritableImportDestination(overflowChestPos, currentTaskCargoRoutingKey());
        if (stagingForPackingSupply) {
            supplyStagingLedger.confirm();
            if (journalJobId != null && !persistDurableCheckpoint(state)) {
                abortWithCargo("packing_supply_checkpoint_unavailable",
                        "The confirmed staging receipt could not be saved; remaining cargo is preserved.");
                return false;
            }
        }
        return true;
    }

    private boolean resumePackedShulkerFromOverflowWindow(Container open, int chestSlots) {
        if (!hasUnresolvedPackedShulkerTransaction()) return false;

        int candidateSlot = packedShulkerTransactionSlot(
                open,
                chestSlots,
                countItemInOpenPlayerInventory(open, chestSlots, packItemId),
                isShulkerAtPosition(reconciliationWorksite));
        if (candidateSlot >= 0) {
            closeCurrentContainer();
            stagingForPackingSupply = false;
            supplyStagingLedger.reset();
            packStoreVerificationTicks = 0;
            emit("organize_overflow_packed_shulker_recovered", Map.of(
                    "reason", "live_player_window_contains_packed_transaction_box",
                    "slot", candidateSlot,
                    "storage_class", packItemId,
                    "fill_moved_units", shulkerFillMovedUnits,
                    "disposition", "resume_packed_shulker_handoff"
            ));
            walkToPackedShulkerDestination(packDestination);
            return true;
        }

        if (shouldAwaitPackedShulkerInventorySync(
                0, packStoreVerificationTicks, packedShulkerVerificationTimeoutTicks())) {
            if (packStoreVerificationTicks++ == 0) {
                emit("organize_packed_shulker_inventory_sync_wait", Map.of(
                        "reason", "packed_transaction_not_yet_visible_in_overflow_window",
                        "timeout_ticks", packedShulkerVerificationTimeoutTicks(),
                        "fill_moved_units", shulkerFillMovedUnits,
                        "cargo_units", taskCargo.remaining()
                ));
            }
            actionSlotIndex = HOTBAR_SIZE;
            actionCooldown = Math.max(1, config.organizerClickCooldownTicks);
            return true;
        }

        closeCurrentContainer();
        abortWithCargo("packed_shulker_inventory_sync_unresolved",
                "The filled shulker was collected, but no unique transaction box appeared in the live player window; its checkpoint is preserved.");
        return true;
    }

    /** Deposit only the ledger-owned units when they merged into a protected keep stack. */
    private boolean trySubmitPartialOverflowDeposit(Container open, int chestSlots) {
        if (currentTask == null || pendingOverflowSplit != null) return false;
        int cargoUnits = taskCargo.remaining();
        if (cargoUnits <= 0) return false;

        int sourceRawSlot = -1;
        int sourceWindowSlot = -1;
        ItemStack source = null;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (!isProtectedInventorySlot(rawSlot)) continue;
            int windowSlot = rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack candidate = open.getItemStack(windowSlot);
            if (candidate == null || candidate.getAmount() <= cargoUnits
                    || !currentTaskOwnsStack(candidate)) continue;
            sourceRawSlot = rawSlot;
            sourceWindowSlot = windowSlot;
            source = candidate;
            break;
        }
        if (source == null) return false;

        List<Integer> targets = partialDepositTargets(open, chestSlots, source, cargoUnits);
        if (targets.size() < cargoUnits) {
            ContainerAdmission admission = inspectContainerAdmission(open, chestSlots, source);
            if (switchOverflowDestination(
                    admission, cargoRoutingKey(source), itemIdFromStack(source))) return true;
            abortWithCargo("overflow_full_with_cargo",
                    "No registered import chest has enough exact stack room for the protected cargo split. Cargo remains in inventory.");
            return true;
        }

        if (stagingForPackingSupply) {
            supplyStagingLedger.begin(itemIdFromStack(source),
                    canonicalStagingPosition(overflowChestPos));
        }
        if (journalJobId != null && !persistDurableCheckpoint(State.OVERFLOW_DEPOSITING)) {
            abortWithCargo("overflow_partial_stack_checkpoint_unavailable",
                    "The protected cargo split could not be checkpointed; cargo remains in inventory.");
            return true;
        }

        try {
            List<InventoryAction> actions = new ArrayList<>(cargoUnits + 2);
            actions.add(new ClickItem(openContainerId, sourceWindowSlot, ClickItemAction.LEFT_CLICK));
            for (int target : targets) {
                actions.add(new PlaceOneFromCursor(openContainerId, target));
            }
            actions.add(new ClickItem(openContainerId, sourceWindowSlot, ClickItemAction.LEFT_CLICK));

            InventoryTransferEvidence evidence = InventoryTransferEvidence.partialDeposit(
                    open, chestSlots, sourceWindowSlot, cargoUnits);
            RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(6000)
                    .actions(actions)
                    .build());
            ownInventory(future);
            int delay = Math.max(1, CONFIG.client.inventory.actionDelayTicks);
            pendingOverflowSplit = new PendingOverflowSplit(
                    openContainerId,
                    sourceRawSlot,
                    sourceWindowSlot,
                    cargoUnits,
                    actions.size() * delay + TRANSFER_VERIFICATION_TIMEOUT_TICKS,
                    evidence,
                    future);
            emit("organize_overflow_partial_stack_started", Map.of(
                    "reason", "cargo_merged_with_protected_stack",
                    "cargo_units", cargoUnits,
                    "protected_stack_units", source.getAmount(),
                    "destination_slots", new HashSet<>(targets).size(),
                    "disposition", "deposit_exact_owned_units"));
            if (future.isCompleted() && !future.getNow()) {
                pendingOverflowSplit = null;
                abortWithCargo("overflow_partial_stack_rejected",
                        "The exact protected-stack handoff was rejected before execution; cargo remains in inventory.");
            }
            return true;
        } catch (RuntimeException e) {
            pendingOverflowSplit = null;
            abortWithCargo("overflow_partial_stack_submission_failed",
                    "The exact protected-stack handoff could not be submitted; cargo remains in inventory.");
            return true;
        }
    }

    private void tickPendingOverflowSplit(Container open) {
        PendingOverflowSplit split = pendingOverflowSplit;
        if (split == null) return;
        split.verificationTicks++;
        if (openContainerId != split.containerId) {
            abortPendingOverflowSplit("overflow_partial_stack_window_lost");
            return;
        }
        if (!split.request.isCompleted()) {
            if (split.verificationTicks > split.maxWaitTicks) {
                abortPendingOverflowSplit("overflow_partial_stack_request_pending");
            }
            return;
        }
        if (!split.request.getNow()) {
            abortPendingOverflowSplit("overflow_partial_stack_interrupted");
            return;
        }

        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        boolean cursorEmpty = cursor == null || cursor == Container.EMPTY_STACK
                || cursor.getAmount() <= 0;
        int observedMoved = split.evidence.moved(open);
        if (cursorEmpty && observedMoved >= split.cargoUnits) {
            pendingOverflowSplit = null;
            emit("organize_transfer_confirmed", Map.of(
                    "reason", "protected_stack_partial_handoff_confirmed",
                    "evidence", "exact_player_unit_decrease_and_empty_cursor",
                    "container_id", openContainerId,
                    "slot", split.sourceWindowSlot,
                    "requested_amount", split.cargoUnits,
                    "moved_amount", observedMoved,
                    "player_units_before", split.evidence.playerBefore(),
                    "player_units_now", split.evidence.playerUnits(open),
                    "receiver_observed_amount", split.evidence.received(open)));
            if (!confirmOverflowCargoTransfer(observedMoved)) return;
            actionSlotIndex = Math.max(actionSlotIndex, split.sourceRawSlot + 1);
            actionCooldown = config.organizerClickCooldownTicks;
            return;
        }

        if (split.verificationTicks > split.maxWaitTicks) {
            abortPendingOverflowSplit(cursorEmpty
                    ? "overflow_partial_stack_unverified"
                    : "overflow_partial_stack_cursor_not_empty");
        }
    }

    private void abortPendingOverflowSplit(String reason) {
        pendingOverflowSplit = null;
        abortWithCargo(reason,
                "The exact protected-stack handoff could not be verified. The job stopped with its checkpoint preserved.");
    }

    static List<Integer> partialDepositTargets(
            Container open,
            int chestSlots,
            ItemStack source,
            int requestedUnits) {
        if (open == null || source == null || requestedUnits <= 0) return List.of();
        int maxStack = Math.max(1, ItemRegistry.REGISTRY.get(source.getId()).stackSize());
        List<Integer> targets = new ArrayList<>(requestedUnits);
        for (int slot = 0; slot < chestSlots && targets.size() < requestedUnits; slot++) {
            ItemStack target = open.getItemStack(slot);
            int headroom;
            if (target == null || target == Container.EMPTY_STACK || target.getAmount() <= 0) {
                headroom = maxStack;
            } else if (target.getId() == source.getId()
                    && Objects.equals(target.getDataComponents(), source.getDataComponents())) {
                headroom = Math.max(0, maxStack - target.getAmount());
            } else {
                continue;
            }
            int assign = Math.min(headroom, requestedUnits - targets.size());
            for (int count = 0; count < assign; count++) targets.add(slot);
        }
        return targets.size() == requestedUnits ? List.copyOf(targets) : List.of();
    }

    private boolean finishSupplyStaging() {
        List<MixedStagingLedger.Source> sources = supplyStagingLedger.sources(List.of(), List.of());
        if (currentTask == null || sources.isEmpty()) {
            abortWithCargo("packing_supply_staging_unverified",
                    "Missing cargo has no recorded staging destination; the checkpoint was preserved for inspection.");
            return false;
        }
        int added = 0;
        Set<String> stagedClasses = new LinkedHashSet<>();
        for (var source : sources) {
            MoveTask candidate = MoveTask.mixedBatch(source.position(), copyPos(currentTask.destination()), source.itemId());
            MoveTask existing = consolidationQueue.stream().filter(this::isStoredPackingTask)
                    .filter(task -> packingSourceKey(task).equals(packingSourceKey(candidate))).findFirst().orElse(null);
            if (existing == null) {
                existing = candidate;
                consolidationQueue.addLast(existing);
                added++;
            }
            supplyDeferredTasks.add(existing);
            stagedClasses.add(source.itemId());
        }
        // Replace collected source obligations with staged ones; a round trip is not a delivery.
        int retired = consolidationMode ? Math.max(0, consolidationSourcesInBatch) : 1;
        totalTasks = Math.max(completedTasks, totalTasks + added - retired);
        normalizeSupplyDeferredTasks();
        int noDeliveryStages = 0;
        for (String storageClass : stagedClasses) {
            int stages = supplyStagesWithoutDelivery.compute(storageClass, (key, previous) ->
                    previous == null ? 1 : Math.min(MAX_SUPPLY_STAGES_WITHOUT_DELIVERY, previous + 1));
            noDeliveryStages = Math.max(noDeliveryStages, stages);
        }
        consolidationSourcesInBatch = 0;
        taskCargo.reset(0);
        movedThisVisit = 0;
        sourceVisitFailed = false;
        stagingForPackingSupply = false;
        recoverOnboardMixedAfterPackedDelivery = false;
        supplyStagingLedger.reset();
        emit("organize_packing_supply_staged", Map.of(
                "deferred_source_tasks", sources.size(), "new_source_tasks", added,
                "waiting_for_supply_tasks", supplyDeferredTasks.size(),
                "stages_without_delivery", noDeliveryStages));
        // Save the handoff boundary, not a completed overflow transaction that could replay.
        currentTask = null;
        state = State.CLOSING_SOURCE;
        if (journalJobId != null && !persistDurableCheckpoint(state)) {
            abortWithCargo("packing_supply_checkpoint_unavailable",
                    "Staged cargo and its follow-up tasks are preserved in memory; the checkpoint could not be saved.");
            return false;
        }
        if (noDeliveryStages >= MAX_SUPPLY_STAGES_WITHOUT_DELIVERY) {
            emit("organize_packing_supply_blocked", Map.of(
                    "reason", "packing_supply_no_delivery_progress",
                    "storage_classes", stagedClasses,
                    "stages_without_delivery", noDeliveryStages,
                    "disposition", "wait_for_verified_shell_continue_other_work"));
        }
        return true;
    }

    private record PackingSourceKey(long sourceInventory, long destinationInventory, String storageClass) {}

    private boolean isStoredPackingTask(MoveTask task) {
        return task != null && !task.alreadyInInventory() && !task.mixedDecomposition()
                && !task.mandatoryInventoryDeposit() && task.shulkerContentFilter() == null
                && !isShulkerBoxItem(task.itemId());
    }

    private PackingSourceKey packingSourceKey(MoveTask task) {
        return new PackingSourceKey(importInventoryKey(task.source()), importInventoryKey(task.destination()), task.itemId());
    }

    private Set<String> supplyBlockedClasses() {
        return supplyDeferredTasks.stream().map(MoveTask::itemId).collect(Collectors.toSet());
    }

    /** Keep one obligation per physical source and exact item; repair older identity-only deferrals. */
    private void normalizeSupplyDeferredTasks() {
        Set<String> blocked = supplyBlockedClasses();
        if (blocked.isEmpty()) return;
        Map<PackingSourceKey, MoveTask> canonical = new LinkedHashMap<>();
        // A partially collected current source may also be queued. Keep its identity stable.
        if (isStoredPackingTask(currentTask) && blocked.contains(currentTask.itemId())
                && consolidationQueue.stream().anyMatch(task -> task == currentTask)) {
            canonical.put(packingSourceKey(currentTask), currentTask);
        }
        List<MoveTask> normalized = new ArrayList<>();
        Set<MoveTask> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        int merged = 0;
        int newlyDeferred = 0;
        for (MoveTask task : consolidationQueue) {
            if (!isStoredPackingTask(task) || !blocked.contains(task.itemId())) {
                normalized.add(task);
                continue;
            }
            MoveTask chosen = canonical.computeIfAbsent(packingSourceKey(task), key -> task);
            if (retained.add(chosen)) {
                normalized.add(chosen);
                if (!supplyDeferredTasks.contains(chosen)) newlyDeferred++;
            } else {
                merged++;
            }
            supplyDeferredTasks.add(chosen);
        }
        consolidationQueue.clear();
        consolidationQueue.addAll(normalized);
        Set<MoveTask> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        queued.addAll(consolidationQueue);
        supplyDeferredTasks.removeIf(task -> !queued.contains(task));
        totalTasks = Math.max(completedTasks, totalTasks - merged);
        if (merged > 0 || newlyDeferred > 0) {
            emit("organize_packing_supply_queue_repaired", Map.of(
                    "merged_source_tasks", merged, "newly_deferred_source_tasks", newlyDeferred,
                    "waiting_for_supply_tasks", supplyDeferredTasks.size()));
        }
    }

    private Deque<MoveTask> eligibleConsolidationTasks() {
        Set<String> blocked = supplyBlockedClasses();
        return consolidationQueue.stream().filter(task -> !supplyDeferredTasks.contains(task)
                        && !(isStoredPackingTask(task) && blocked.contains(task.itemId())))
                .collect(Collectors.toCollection(ArrayDeque::new));
    }

    /** Wake one exact cargo group when a recovered shell or useful indexed stock is available. */
    private boolean releaseSupplyDeferredGroup(boolean recoveredShell) {
        normalizeSupplyDeferredTasks();
        boolean shellAvailable = recoveredShell && countEmptyPackingShells(null) > 0;
        List<ContainerEntry> sources = index.getAll().stream().filter(this::isManagedSourceContainer).toList();
        Map<String, Boolean> availability = new HashMap<>();
        MoveTask first = consolidationQueue.stream().filter(supplyDeferredTasks::contains)
                .filter(this::isStoredPackingTask)
                .filter(task -> shellAvailable || (supplyStagesWithoutDelivery.getOrDefault(task.itemId(), 0)
                        < MAX_SUPPLY_STAGES_WITHOUT_DELIVERY && availability.computeIfAbsent(task.itemId(), key ->
                        packingSourceSelector.candidates(sources, key,
                        Set.of(), CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(),
                        CACHE.getPlayerCache().getZ(), System.currentTimeMillis()).stream()
                        .anyMatch(c -> c.rank() < 2))))
                .findFirst().orElse(null);
        if (first == null) return false;
        List<MoveTask> ready = consolidationQueue.stream().filter(supplyDeferredTasks::contains)
                .filter(this::isStoredPackingTask)
                .filter(task -> task.itemId().equals(first.itemId())).toList();
        supplyDeferredTasks.removeAll(ready);
        consolidationQueue.removeAll(ready);
        // Legacy loose tasks must be selectable by the same consumer as generated packing work.
        ready = ready.stream().map(task -> task.mixedBatchConsolidation() ? task
                : replaceTaskReferences(task, MoveTask.mixedBatch(
                        task.source(), task.destination(), task.itemId()))).toList();
        prependConsolidationTasks(ready);
        emit("organize_packing_supply_resumed", Map.of(
                "storage_class", first.itemId(), "source_tasks", ready.size(),
                "supply", shellAvailable ? "recovered_empty_shell" : "useful_indexed_stock"));
        return true;
    }

    private boolean switchOverflowDestination(
            ContainerAdmission rejectedAdmission,
            String cargoKey,
            String cargoItemId) {
        closeCurrentContainer();
        if (overflowChestPos != null) {
            long key = importInventoryKey(overflowChestPos);
            overflowTriedDestinations.add(key);
            importDestinationTracker.recordRejected(key, cargoKey);
        }
        emit("organize_overflow_capacity_rejected", importCapacityDetails(
                rejectedAdmission, overflowTriedDestinations.size(), cargoItemId));
        if (shouldRollbackBlockedMixedShulker(
                overflowTriedDestinations.size())) {
            emit("organize_import_capacity_probe_budget_reached", Map.of(
                    "reason", "mixed_shulker_import_probe_budget_exhausted",
                    "destinations_tried", overflowTriedDestinations.size(),
                    "max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES,
                    "disposition", "return_box_to_vacated_source"));
            return false;
        }
        if (shouldStartCurrentPackedImportCapacityRecovery(
                overflowTriedDestinations.size())) {
            emit("organize_import_capacity_probe_budget_reached", Map.of(
                    "reason", "packed_import_probe_budget_exhausted",
                    "destinations_tried", overflowTriedDestinations.size(),
                    "max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES,
                    "disposition", "compact_staged_import_cargo"));
            return false;
        }
        // The live capacity cache can be stale, but walking hundreds of registered imports
        // is neither useful nor server-friendly. Eight distinct live inventories are enough
        // evidence to compact known staged cargo or stop with the checkpoint preserved.
        if (packedImportProbeBudgetReached(overflowTriedDestinations.size())) {
            boolean canCompact = isLoosePackingOverflowTask()
                    && eligibleConsolidationTasks().stream()
                            .anyMatch(this::isImportCapacityRecoveryTask);
            emit("organize_import_capacity_probe_budget_reached", Map.of(
                    "reason", "overflow_import_probe_budget_exhausted",
                    "destinations_tried", overflowTriedDestinations.size(),
                    "max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES,
                    "disposition", canCompact
                            ? "compact_staged_import_cargo"
                            : "preserve_cargo_and_stop"));
            return false;
        }
        for (int[] candidate : orderedWritableImportDestinations(cargoKey, cargoItemId)) {
            long key = importInventoryKey(candidate);
            if (overflowTriedDestinations.contains(key)) continue;
            overflowChestPos = copyPos(candidate);
            walkTarget = copyPos(candidate);
            openWaitTicks = 0;
            containerDataReceived = false;
            trackedWalkTargetKey = Long.MIN_VALUE;
            state = State.OVERFLOW_WALKING;
            persistDurableCheckpoint(state);
            emit("organize_overflow_destination_switched", Map.of(
                    "reason", "import_destination_incompatible",
                    "destinations_tried", overflowTriedDestinations.size()
            ));
            return true;
        }
        return false;
    }

    // Consolidation
    private void advanceConsolidation() {
        if (mixedBatchConsolidationMode) {
            Deque<MoveTask> eligible = eligibleConsolidationTasks();
            List<MoveTask> pendingInventoryGroup =
                    extractFirstInventoryMixedBatchGroup(eligible);
            if (!pendingInventoryGroup.isEmpty()) {
                consolidationQueue.removeAll(pendingInventoryGroup);
                startMixedInventoryCargoGroup(pendingInventoryGroup);
                return;
            }
            // A stale source must not give its reserved shell to a different item group.
            if (sourceVisitFailed && countEmptyPackingShells(null) > 0
                    && continueCollectingCurrentBulkBatch()) return;
            MoveTask stagedLaneCargo = extractFirstAssignedMixedBatchTask(
                    eligible, columnAssignment.keySet());
            if (stagedLaneCargo != null) {
                consolidationQueue.remove(stagedLaneCargo);
                startMixedStagedLaneCargo(stagedLaneCargo);
                return;
            }
            // Finish every exact class already held by the bot, then return to the main
            // shulker-first queue. Ordinary staged source work remains deferred.
            mixedBatchConsolidationMode = false;
            consolidationMode = false;
            advanceToNextTask();
            return;
        }

        // All collected → continue normal work or finish.
        if (eligibleConsolidationTasks().isEmpty()) {
            consolidationMode = false;
            if (taskQueue.isEmpty()) finishOrganization();
            else advanceToNextTask();
            return;
        }

        // Next batch
        currentTask = eligibleConsolidationTasks().getFirst();
        consolidationQueue.remove(currentTask);
        taskCargo.reset(currentTask.alreadyInInventory()
                ? countCurrentTaskCargoUnitsInInventory()
                : 0);
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

    private void startMixedInventoryCargoGroup(List<MoveTask> group) {
        currentTask = group.getFirst();
        for (int index = group.size() - 1; index >= 1; index--) {
            consolidationQueue.addFirst(group.get(index));
        }
        taskCargo.reset(countCurrentTaskCargoUnitsInInventory());
        consolidationSourcesInBatch = 1;
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        emit("organize_recovered_cargo_packing_started", Map.of(
                "storage_class", currentTask.itemId(),
                "source_tasks", group.size()
        ));
        // Lane-backed cargo can drain its matching import sources before packing because its
        // finished shulker has a permanent destination. This creates the import slot needed
        // by any lane-less packed output waiting behind it. Import-only cargo still packs its
        // onboard stacks first so it cannot refill an already constrained inventory.
        if (columnAssignment.containsKey(currentTask.itemId())
                && continueCollectingCurrentBulkBatch()) return;
        startShulkerPacking(currentTask.itemId(), currentTask.destination());
    }

    private void startMixedStagedLaneCargo(MoveTask task) {
        currentTask = task;
        taskCargo.reset(0);
        consolidationSourcesInBatch = 0;
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.WALKING;
        persistDurableCheckpoint(state);
    }

    private boolean reuseEmptyShulkerForDeferredPacking() {
        if (!isEmptyShulkerStagingTask(currentTask)
                || currentTask.mandatoryInventoryDeposit()
                || countCurrentTaskCargoUnitsInInventory() <= 0
                || countEmptyPackingShells(null) != 1) {
            return false;
        }
        // Wake only when this consumer can immediately claim the recovered shell.
        releaseSupplyDeferredGroup(true);
        Deque<MoveTask> eligible = eligibleConsolidationTasks();
        List<MoveTask> group = extractFirstMixedBatchGroup(eligible);
        if (group.isEmpty()) return false;
        consolidationQueue.removeAll(group);

        closeCurrentContainer();
        completedTasks++;
        emitProgressMilestoneIfCrossed();

        currentTask = group.get(0);
        for (int index = group.size() - 1; index >= 1; index--) {
            consolidationQueue.addFirst(group.get(index));
        }
        taskCargo.reset(currentTask.alreadyInInventory()
                ? countCurrentTaskCargoUnitsInInventory()
                : 0);
        consolidationSourcesInBatch = 0;
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        emptyShulkerStagingTriedDestinations.clear();
        emit("organize_empty_shulker_reused", Map.of(
                "reason", "pack_decomposed_cargo",
                "disposition", "retain_one_shell_prefer_matching_partial_stock",
                "storage_class", currentTask.itemId(),
                "source_tasks", group.size()
        ));

        if (currentTask.alreadyInInventory()) {
            consolidationSourcesInBatch = 1;
            if (continueCollectingCurrentBulkBatch()) return true;
            startShulkerPacking(currentTask.itemId(), currentTask.destination());
            return true;
        }
        currentRole = TargetRole.SOURCE;
        walkTarget = currentTask.source();
        actionSlotIndex = 0;
        containerDataReceived = false;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.WALKING;
        persistDurableCheckpoint(state);
        return true;
    }

    static List<MoveTask> extractFirstMixedBatchGroup(Deque<MoveTask> queue) {
        if (queue == null || queue.isEmpty()) return List.of();
        MoveTask first = null;
        Iterator<MoveTask> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MoveTask task = iterator.next();
            if (!task.mixedBatchConsolidation()) continue;
            first = task;
            iterator.remove();
            break;
        }
        if (first == null) return List.of();

        List<MoveTask> group = new ArrayList<>();
        group.add(first);
        String storageClass = first.itemId();
        iterator = queue.iterator();
        while (iterator.hasNext()) {
            MoveTask task = iterator.next();
            if (task.mixedBatchConsolidation()
                    && storageClass.equals(task.itemId())) {
                group.add(task);
                iterator.remove();
            }
        }
        return List.copyOf(group);
    }

    static List<MoveTask> extractFirstInventoryMixedBatchGroup(Deque<MoveTask> queue) {
        if (queue == null || queue.isEmpty()) return List.of();
        MoveTask first = null;
        Iterator<MoveTask> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MoveTask task = iterator.next();
            if (!task.mixedBatchConsolidation() || !task.alreadyInInventory()) continue;
            first = task;
            iterator.remove();
            break;
        }
        if (first == null) return List.of();

        List<MoveTask> group = new ArrayList<>();
        group.add(first);
        String storageClass = first.itemId();
        iterator = queue.iterator();
        while (iterator.hasNext()) {
            MoveTask task = iterator.next();
            if (task.mixedBatchConsolidation()
                    && storageClass.equals(task.itemId())) {
                group.add(task);
                iterator.remove();
            }
        }
        return List.copyOf(group);
    }

    static MoveTask extractFirstAssignedMixedBatchTask(
            Deque<MoveTask> queue,
            Set<String> assignedStorageClasses) {
        if (queue == null || queue.isEmpty()
                || assignedStorageClasses == null || assignedStorageClasses.isEmpty()) {
            return null;
        }
        Iterator<MoveTask> iterator = queue.iterator();
        while (iterator.hasNext()) {
            MoveTask task = iterator.next();
            if (task.mixedBatchConsolidation()
                    && !task.alreadyInInventory()
                    && assignedStorageClasses.contains(task.itemId())) {
                iterator.remove();
                return task;
            }
        }
        return null;
    }

    /** Collect consecutive source tasks for one exact storage key before consuming a box. */
    private boolean continueCollectingCurrentBulkBatch() {
        if (currentTask == null) return false;
        boolean inventoryHasRoom = hasInventoryRoom();
        MoveTask next = eligibleConsolidationTasks().peekFirst();
        if (next == null || !BulkBatchPlanner.shouldCollectNext(
                currentTask.itemId(), next.itemId(), next.alreadyInInventory(), inventoryHasRoom)) return false;

        currentTask = next;
        consolidationQueue.remove(currentTask);
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

        // Inspect the live box before state-specific recovery. A failed pickup checkpoint can
        // otherwise bypass both overflow and store recovery even though the transaction ledger
        // proves that packing cargo was clicked into an actual mixed box.
        if (recoverLegacyMixedPackingCheckpoint()) return;

        if (temporaryShulkerOutstanding || isTemporaryShulkerState(interrupted)) {
            resumeTemporaryShulkerCheckpoint();
            return;
        }

        switch (interrupted) {
            case MIXED_SHELL_VERIFY -> {
                state = State.MIXED_SHELL_VERIFY;
                tickMixedShellVerification();
            }
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
                    resumeOverflowCheckpoint();
            case SHULKER_RESUME_WALK, SHULKER_PLACING, SHULKER_WAIT_PLACE,
                 SHULKER_OPENING, SHULKER_FILLING, SHULKER_CLOSING,
                 SHULKER_BREAKING, SHULKER_PICKUP, SHULKER_RECOVERY_BREAKING,
                 SHULKER_RECOVERY_PICKUP -> resumeTemporaryShulkerCheckpoint();
            case IDLE, YIELDED, DONE, FAILED -> markFailed("checkpoint_state_not_resumable", interrupted);
        }
    }

    private void resumeOverflowCheckpoint() {
        emit("organize_packed_shulker_inventory_audit", packedShulkerInventoryAudit());
        // A packing pickup can be server-reverted once, then succeed through the recovery
        // breaker. Older recovery flow entered overflow even though the loose cargo was now
        // inside the recovered box. Resume that box's real destination instead of looking for
        // loose items which no longer exist. This check must precede the empty-staging check:
        // a packed box legitimately leaves zero loose task units and has no import receipt.
        if (shouldResumePackedShulkerHandoff(
                mixedDecompositionMode,
                hasPackedShulkerInInventory() || pickupEvidenceMatchesPackedShulker(),
                packDestination != null)) {
            resetTemporaryShulkerState();
            emit("organize_recovery_completed", Map.of(
                    "reason", "restored_overflow_contains_packed_shulker",
                    "disposition", "resume_packed_shulker_handoff"
            ));
            walkToPackedShulkerDestination(packDestination);
            return;
        }
        if (hasUnresolvedPackedShulkerTransaction()) {
            emit("organize_recovery_continued", Map.of(
                    "reason", "restored_overflow_contains_packed_transaction",
                    "disposition", "verify_box_in_live_player_window"
            ));
            startOverflowAfterShulkerCleanup();
            return;
        }
        if (retireMissingInventoryPackingCargo("overflow_resume")) return;
        int cargoUnitsPresent = countRestorableCurrentTaskCargoUnitsInInventory();
        if (stagingForPackingSupply && cargoUnitsPresent == 0) {
            // Confirm an empty staging transaction through a live server window before
            // retiring it. A non-empty ledger means the handoff already happened and can be
            // completed immediately without reopening the import chest.
            if (supplyStagingLedger.sources(List.of(), List.of()).isEmpty()) {
                startOverflowAfterShulkerCleanup();
                return;
            }
            if (!finishSupplyStaging()) return;
            advanceToNextTask();
            return;
        }

        taskCargo.reset(cargoUnitsPresent);
        if (CheckpointCargoRecovery.disposition(cargoUnitsPresent)
                == CheckpointCargoRecovery.Disposition.RESUME_HANDOFF) {
            startOverflowAfterShulkerCleanup();
            return;
        }

        // Destination checkpoints are at-most-once. If their exact cargo is no longer aboard,
        // do not revisit the source or manufacture an empty overflow transaction.
        closeCurrentContainer();
        movedThisVisit = 0;
        destinationVisitFailed = false;
        state = State.CLOSING_DEST;
        emit("organize_recovery_completed", Map.of(
                "reason", "restored_destination_cargo_absent",
                "disposition", "complete_current_task"
        ));
        persistDurableCheckpoint(state);
    }

    /** Retire a stale inventory-only packing obligation once live inventory proves it absent. */
    private boolean retireMissingInventoryPackingCargo(String phase) {
        return retireMissingInventoryPackingCargo(phase, false);
    }

    private boolean retireMissingInventoryPackingCargo(
            String phase, boolean authoritativeAbsence) {
        String storageClass = currentTask == null ? null : currentTask.itemId();
        boolean matchingSibling = hasMatchingPackingSibling(taskQueue, storageClass)
                || hasMatchingPackingSibling(consolidationQueue, storageClass);
        if (!shouldRetireMissingInventoryPackingCargo(
                currentTask != null && currentTask.alreadyInInventory(),
                currentTask != null && currentTask.mixedBatchConsolidation(),
                countCurrentTaskCargoUnitsInInventory(), matchingSibling,
                authoritativeAbsence)) {
            return false;
        }

        int retired = 1;
        retired += removeMissingInventoryPackingTasks(taskQueue, storageClass);
        retired += removeMissingInventoryPackingTasks(consolidationQueue, storageClass);
        supplyDeferredTasks.removeIf(task -> task.alreadyInInventory()
                && task.mixedBatchConsolidation()
                && storageClass.equals(task.itemId()));
        totalTasks = Math.max(completedTasks, totalTasks - retired);
        currentTask = null;
        taskCargo.reset(0);
        consolidationSourcesInBatch = 0;
        stagingForPackingSupply = false;
        supplyStagingLedger.reset();
        packingSupplySearch.reset();
        packingCargoAbsenceConfirmed = false;
        movedThisVisit = 0;
        sourceVisitFailed = true;
        actionCooldown = 0;
        closeCurrentContainer();
        state = State.CLOSING_SOURCE;
        emit("organize_stale_inventory_cargo_retired", Map.of(
                "reason", "inventory_packing_cargo_absent",
                "phase", phase,
                "storage_class", storageClass,
                "retired_tasks", retired,
                "disposition", "continue_remaining_work"));
        persistDurableCheckpoint(state);
        return true;
    }

    static boolean shouldRetireMissingInventoryPackingCargo(
            boolean alreadyInInventory,
            boolean mixedBatchConsolidation,
            int cargoUnits,
            boolean matchingSibling,
            boolean authoritativeAbsence) {
        return alreadyInInventory && mixedBatchConsolidation
                && (cargoUnits <= 0 || authoritativeAbsence)
                && (matchingSibling || authoritativeAbsence);
    }

    private static boolean hasMatchingPackingSibling(
            Deque<MoveTask> queue, String storageClass) {
        return storageClass != null && queue.stream().anyMatch(task -> task.mixedBatchConsolidation()
                && storageClass.equals(task.itemId()));
    }

    private static int removeMissingInventoryPackingTasks(
            Deque<MoveTask> queue, String storageClass) {
        int before = queue.size();
        queue.removeIf(task -> task.alreadyInInventory()
                && task.mixedBatchConsolidation()
                && Objects.equals(storageClass, task.itemId()));
        return before - queue.size();
    }

    /** Repair a legacy checkpoint where a skipped hotbar swap placed the wrong shulker. */
    private boolean recoverLegacyMixedPackingCheckpoint() {
        boolean confirmedPackingTransaction = stagingForPackingSupply
                || shulkerFillMovedUnits > 0
                    && taskCargo.acquired() >= shulkerFillMovedUnits
                    && taskCargo.deposited() == 0;
        if (!confirmedPackingTransaction || mixedDecompositionMode || currentTask == null
                || !currentTask.mixedBatchConsolidation()
                || countCurrentTaskCargoUnitsInInventory() != 0) {
            return false;
        }
        int expectedUnits = taskCargo.remaining();
        if (expectedUnits <= 0) return false;

        List<LegacyMixedPackingCandidate> candidates = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack == null || stack.getAmount() != 1
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() != ShulkerClassification.Kind.MIXED) continue;
            candidates.add(new LegacyMixedPackingCandidate(
                    slot, itemIdFromStack(stack), classification.fingerprint(),
                    classification.contents()));
        }

        Optional<LegacyMixedPackingCandidate> selected = selectLegacyMixedPackingCandidate(
                packItemId, expectedUnits, candidates);
        if (selected.isEmpty()) return false;

        LegacyMixedPackingCandidate recovered = selected.get();
        int[] staging = chooseMixedInventoryStaging(recovered.contents());
        if (staging == null) return false;
        closeCurrentContainer();

        // This box was discovered in inventory rather than taken from a currently-open
        // source. Use its staging chest as the recovery origin as well, so a later rollback
        // can never mistake the reconciliation floor for a container destination.
        MoveTask replacement = MoveTask.mixedInInventory(
                copyPos(staging), copyPos(staging), recovered.shulkerItemId(),
                recovered.fingerprint(), recovered.contents());
        MoveTask existing = Stream.concat(taskQueue.stream(), consolidationQueue.stream())
                .filter(task -> task.mixedDecomposition()
                        && recovered.shulkerItemId().equals(task.itemId())
                        && recovered.fingerprint().equals(task.shulkerContentFilter()))
                .findFirst().orElse(null);
        if (existing != null) {
            taskQueue.remove(existing);
            consolidationQueue.remove(existing);
            supplyDeferredTasks.remove(existing);
        }

        int retiredSources = consolidationMode
                ? Math.max(1, consolidationSourcesInBatch)
                : 1;
        totalTasks = Math.max(completedTasks, totalTasks - retiredSources + (existing == null ? 1 : 0));
        MoveTask staleTask = currentTask;
        supplyDeferredTasks.remove(staleTask);
        currentTask = null;
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        consolidationSourcesInBatch = 0;
        taskCargo.reset(0);
        movedThisVisit = 0;
        sourceVisitFailed = false;
        stagingForPackingSupply = false;
        supplyStagingLedger.reset();
        packingSupplySearch.reset();
        resetTemporaryShulkerState();
        clearMixedDecompositionState();
        state = State.CLOSING_SOURCE;
        taskQueue.addFirst(replacement);
        emit("organize_legacy_mixed_packing_recovered", Map.ofEntries(
                Map.entry("reason", "mixed_shulker_received_packing_cargo"),
                Map.entry("storage_class", packItemId),
                Map.entry("cargo_units", expectedUnits),
                Map.entry("mixed_box_slot", recovered.slot()),
                Map.entry("mixed_box_contents", recovered.contents()),
                Map.entry("retired_source_tasks", retiredSources),
                Map.entry("reused_planned_mixed_task", existing != null),
                Map.entry("disposition", "decompose_recovered_mixed_box")));
        advanceToNextTask();
        return true;
    }

    static Optional<LegacyMixedPackingCandidate> selectLegacyMixedPackingCandidate(
            String expectedStorageClass,
            int expectedUnits,
            List<LegacyMixedPackingCandidate> candidates) {
        if (expectedStorageClass == null || expectedUnits <= 0 || candidates == null) {
            return Optional.empty();
        }
        List<LegacyMixedPackingCandidate> matches = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.contents().entrySet().stream().anyMatch(entry ->
                        entry.getValue() == expectedUnits
                                && ItemIdentifier.contentItemIdsMatch(
                                        expectedStorageClass, entry.getKey())))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    static boolean shouldResumePackedShulkerHandoff(
            boolean mixedDecomposition,
            boolean compatiblePackedShulkerPresent,
            boolean destinationPresent) {
        return !mixedDecomposition && compatiblePackedShulkerPresent && destinationPresent;
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

        int cargoUnitsPresent = countCurrentTaskCargoUnitsInInventory();
        boolean cargoPresent = cargoUnitsPresent > 0;
        MoveTask interruptedSource = currentTask;
        taskCargo.reset(cargoUnitsPresent);
        MoveTask packingObligation = cargoPresent
                ? matchingInventoryPackingObligation(currentTask)
                : null;
        if (packingObligation != null) {
            // Older checkpoints could queue the same onboard cargo twice when import failover
            // changed its destination: once as packing work and once as a generic deposit.
            // Retire the routing duplicate and resume the original packing contract.
            consolidationQueue.remove(packingObligation);
            currentTask = packingObligation.markAlreadyInInventory();
            totalTasks = Math.max(completedTasks, totalTasks - 1);
            consolidationMode = true;
            mixedBatchConsolidationMode = true;
            consolidationSourcesInBatch = Math.max(1, consolidationSourcesInBatch);
            emit("organize_inventory_task_repaired", Map.of(
                    "reason", "destination_variant_duplicate",
                    "storage_class", currentTask.itemId(),
                    "cargo_units", cargoUnitsPresent,
                    "disposition", "resume_existing_packing_obligation"
            ));
            startShulkerPacking(currentTask.itemId(), currentTask.destination());
            return;
        }
        if (consolidationMode && !cargoPresent && isStoredPackingTask(currentTask)
                && supplyBlockedClasses().contains(currentTask.itemId())) {
            if (!consolidationQueue.contains(currentTask)) consolidationQueue.addFirst(currentTask);
            normalizeSupplyDeferredTasks();
            currentTask = null;
            state = State.CLOSING_SOURCE;
            if (!persistDurableCheckpoint(state)) {
                abortWithCargo("packing_supply_checkpoint_unavailable", "Could not save the blocked source handoff.");
                return;
            }
            advanceToNextTask();
            return;
        }
        if (cargoPresent) {
            // A restored source task has crossed the inventory transaction boundary even if
            // the last durable state still said TAKING. Record that ownership before scanning
            // the rest of the inventory so this exact box/stack is not scheduled twice.
            currentTask = currentTask.markAlreadyInInventory();
        }
        // Resume required cleanup before revisiting a blocked mixed source, including old
        // checkpoints whose recovery guard is already exhausted. Only real progress resets it.
        boolean waitingForMixedSource = !cargoPresent && currentTask.mixedDecomposition()
                && currentRole == TargetRole.SOURCE;
        boolean requeuedMixedSource = waitingForMixedSource && !taskQueue.contains(currentTask);
        if (requeuedMixedSource) taskQueue.addFirst(currentTask);
        boolean cleanupQueued = queueInventoryDepositTasks(true);
        if (waitingForMixedSource && cleanupQueued) {
            consolidationMode = false;
            mixedBatchConsolidationMode = false;
            advanceToNextTask();
            return;
        }
        if (requeuedMixedSource) taskQueue.removeFirstOccurrence(currentTask);
        if (cargoPresent && isEmptyShulkerStagingTask(currentTask)
                && reuseEmptyShulkerForDeferredPacking()) {
            return;
        }
        if (cargoPresent && currentTask.mixedDecomposition()
                && !isMixedSourceRollbackTask(currentTask)) {
            startMixedShulkerDecomposition();
            return;
        }
        if (consolidationMode && cargoPresent) {
            if (interrupted == State.TAKING && isStoredPackingTask(interruptedSource)
                    && !consolidationQueue.contains(interruptedSource)) {
                // Pickup proves cargo ownership, not that the source was fully drained.
                consolidationQueue.addFirst(interruptedSource);
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
        recoverOnboardMixedAfterPackedDelivery |= hasUnscheduledMixedInventoryCargo();
        String previousMixedFingerprint = currentTask == null
                ? null : currentTask.shulkerContentFilter();
        if (mixedDecompositionMode && currentTask != null
                && currentTask.alreadyInInventory() && !mixedBoxDrained) {
            refreshRecoveredMixedShulkerFingerprint();
        }
        boolean repairedLiveMixedIdentity = currentTask != null
                && !Objects.equals(previousMixedFingerprint, currentTask.shulkerContentFilter());
        if (repairedLiveMixedIdentity && stationWalkRecovery.snapshot() != null) {
            StationWalkRecovery.Snapshot staleWalk = stationWalkRecovery.snapshot();
            stationWalkRecovery.reset();
            stationPathTarget = null;
            stationLastPathTick = -20;
            emit("organize_station_walk_rearmed", Map.of(
                    "reason", "live_mixed_shulker_identity_refreshed",
                    "previous_elapsed_ticks", staleWalk.elapsedTicks(),
                    "previous_detour_attempts", staleWalk.attempts(),
                    "disposition", "fresh_bounded_station_trip"
            ));
        }
        // A capacity-recovery checkpoint may already own an empty shell with every loose
        // stack durably staged. Finishing that ledger transition is position-independent;
        // forcing a station walk first only exposes the job to unrelated pathing failures.
        if (finishRecoveredEmptyMixedShellCheckpoint()) return;
        if (reconciliationStation == null) {
            abortWithCargo("reconciliation_checkpoint_missing",
                    "The reconciliation station checkpoint was missing; cargo is preserved in inventory.");
            return;
        }
        taskCargo.reset(countCurrentTaskCargoUnitsInInventory());
        walkTarget = reconciliationStation;
        state = State.SHULKER_STATION_WALK;
    }

    private boolean hasUnscheduledMixedInventoryCargo() {
        return !uncoveredMixedInventoryCargo().isEmpty();
    }

    private List<MixedInventoryRecoveryPlanner.Cargo> uncoveredMixedInventoryCargo() {
        List<MixedInventoryRecoveryPlanner.Cargo> onboard = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() != ShulkerClassification.Kind.MIXED) continue;
            for (int count = 0; count < stack.getAmount(); count++) {
                onboard.add(new MixedInventoryRecoveryPlanner.Cargo(
                        itemIdFromStack(stack), classification.fingerprint(),
                        classification.contents()));
            }
        }
        if (onboard.isEmpty()) return List.of();

        List<MixedInventoryRecoveryPlanner.Cargo> scheduled = new ArrayList<>();
        addScheduledMixedInventoryCargo(scheduled, currentTask);
        taskQueue.forEach(task -> addScheduledMixedInventoryCargo(scheduled, task));
        consolidationQueue.forEach(task -> addScheduledMixedInventoryCargo(scheduled, task));
        return MixedInventoryRecoveryPlanner.uncovered(onboard, scheduled);
    }

    /** Queue only mixed boxes; a just-delivered bulk box may linger in cache for one tick. */
    private boolean queueUnscheduledMixedInventoryRecoveryTasks() {
        List<MixedInventoryRecoveryPlanner.Cargo> uncovered = uncoveredMixedInventoryCargo();
        if (uncovered.isEmpty()) return false;

        int[] source = currentPlayerPosition();
        List<MoveTask> recovery = new ArrayList<>();
        for (MixedInventoryRecoveryPlanner.Cargo cargo : uncovered) {
            int[] staging = chooseMixedInventoryStaging(cargo.contents());
            if (staging == null) continue;
            recovery.add(MoveTask.mixedInInventory(
                    source, staging, cargo.itemId(), cargo.fingerprint(), cargo.contents()));
        }
        if (recovery.isEmpty()) return false;

        for (int index = recovery.size() - 1; index >= 0; index--) {
            taskQueue.addFirst(recovery.get(index));
        }
        totalTasks += recovery.size();
        emit("organize_mixed_inventory_recovery_queued", Map.of(
                "reason", "restored_generic_placement_audit",
                "mixed_boxes", recovery.size(),
                "disposition", "decompose_before_more_source_collection"));
        return true;
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
        resetShulkerPlacementAttempt();
        shulkerBreakFuture = null;
        shulkerBreakAttemptGate.clear();
        shulkerTicks = 0;
        resetShulkerPickupSweep();

        // A failure recovery must remain a cleanup transaction after cooperative preemption.
        // Reopening the failed mixed box would repeat the same fault and start another cooldown.
        if (stopAfterShulkerRecovery) {
            temporaryShulkerOutstanding = true;
            state = isShulkerAtPosition(shulkerPlacePos)
                    ? State.SHULKER_RECOVERY_BREAKING
                    : State.SHULKER_RECOVERY_PICKUP;
            return;
        }

        if (PACKED_SHULKER_INVENTORY_SYNC_RECOVERY.equals(shulkerRecoveryTrigger)) {
            if (hasPackedShulkerForHandoff()) {
                temporaryShulkerOutstanding = false;
                walkToPackedShulkerDestination(packDestination);
            } else {
                temporaryShulkerOutstanding = true;
                state = State.SHULKER_PICKUP;
            }
            return;
        }

        if (mixedDecompositionMode) {
            if (continueRecoveredMixedBoxWithInventoryCargo()) {
                emit("organize_capacity_recovery_continued", Map.of(
                        "reason", "restored_mixed_staging_full",
                        "disposition", "pack_recovered_mixed_cargo"
                ));
                return;
            }
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
                    && temporaryShulkerRecoveryStatus().inventoryRecovered()) {
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
            resetContainerOpenTracking();
            state = State.SHULKER_OPENING;
            return;
        }
        if (hasPackedShulkerInInventory() || pickupEvidenceMatchesPackedShulker()) {
            temporaryShulkerOutstanding = false;
            walkToPackedShulkerDestination(packDestination);
            return;
        }
        if (temporaryShulkerRecoveryStatus().inventoryRecovered()) {
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
        if (deferPackedImportShulkerForLaneRecovery(
                "restored_import_staging_capacity_exhausted")) {
            return;
        }
        if (hasPackedShulkerForHandoff()) {
            if (packedShulkerInventorySyncRecoveries > 0) {
                emit("organize_recovery_completed", Map.of(
                        "reason", "restored_packed_shulker_visible",
                        "previous_inventory_sync_attempts",
                        packedShulkerInventorySyncRecoveries));
                packedShulkerInventorySyncRecoveries = 0;
            }
            walkToPackedShulkerDestination(packDestination);
        } else {
            recoverPackedShulkerInventoryVisibility(
                    "restored_packed_shulker_inventory_not_yet_visible");
        }
    }

    private boolean hasCurrentTaskCargoInInventory() {
        return countCurrentTaskCargoUnitsInInventory() > 0;
    }

    private int countCurrentTaskCargoUnitsInInventory() {
        if (currentTask == null) return 0;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;

        int units = 0;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = player.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !currentTask.itemId().equals(itemIdFromStack(stack))) continue;
            if (currentTask.shulkerContentFilter() == null) {
                units += stack.getAmount();
                continue;
            }
            if (!isShulkerBoxItem(currentTask.itemId())) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (matchesShulkerTaskFilter(currentTask, classification)) {
                units += stack.getAmount();
            }
        }
        return units;
    }

    /** Include ledger-owned units merged into a finite protected stack after restart. */
    private int countRestorableCurrentTaskCargoUnitsInInventory() {
        int wholeStackUnits = countCurrentTaskCargoUnitsInInventory();
        int keepExcessUnits = 0;
        if (currentTask != null && currentTask.shulkerContentFilter() == null
                && !isShulkerBoxItem(currentTask.itemId()) && taskCargo.remaining() > 0) {
            List<InventoryKeepPolicy.SlotStack> inventory = new ArrayList<>();
            for (int slot = 9; slot <= 44; slot++) {
                ItemStack stack = getCurrentPlayerInventoryStack(slot);
                if (stack == null || stack.getAmount() <= 0) continue;
                inventory.add(new InventoryKeepPolicy.SlotStack(
                        slot, itemIdFromStack(stack), stack.getAmount(),
                        ItemIdentifier.hasCustomPresentation(stack)));
            }
            keepExcessUnits = inventoryKeepPolicy.inferredMovableUnits(
                    currentTask.itemId(), inventory);
        }
        return CheckpointCargoRecovery.provableCargoUnits(
                taskCargo.remaining(), wholeStackUnits, keepExcessUnits);
    }

    private boolean currentTaskOwnsStack(ItemStack stack) {
        if (currentTask == null || stack == null || stack.getAmount() <= 0
                || !currentTask.itemId().equals(itemIdFromStack(stack))) {
            return false;
        }
        if (currentTask.shulkerContentFilter() == null) return true;
        if (!isShulkerBoxItem(currentTask.itemId())) return false;
        ShulkerClassification classification = ShulkerClassification.classify(
                ItemIdentifier.readShulkerContents(stack));
        return matchesShulkerTaskFilter(currentTask, classification);
    }

    private static boolean matchesShulkerTaskFilter(
            MoveTask task,
            ShulkerClassification classification) {
        if (task == null || classification == null || task.shulkerContentFilter() == null) {
            return false;
        }
        if (EMPTY_SHULKER_STAGING_FILTER.equals(task.shulkerContentFilter())) {
            return classification.kind() == ShulkerClassification.Kind.EMPTY;
        }
        if (task.mixedDecomposition()) {
            return task.shulkerContentFilter().equals(classification.fingerprint());
        }
        return classification.kind() == ShulkerClassification.Kind.BULK
                && ItemIdentifier.contentItemIdsMatch(
                        task.shulkerContentFilter(), classification.storageKey());
    }

    private static boolean isEmptyShulkerStagingTask(MoveTask task) {
        return task != null
                && task.alreadyInInventory()
                && isShulkerBoxItem(task.itemId())
                && EMPTY_SHULKER_STAGING_FILTER.equals(task.shulkerContentFilter());
    }

    private boolean isImportStagingMoveTask(MoveTask task) {
        if (task == null || task.destination() == null) return false;
        int[] destination = task.destination();
        return index.isImportChest(destination[0], destination[1], destination[2]);
    }

    private boolean switchImportStagingMoveDestination(
            ContainerAdmission rejectedAdmission,
            String cargoKey,
            String cargoItemId) {
        if (!isImportStagingMoveTask(currentTask)) return false;
        int[] currentDestination = currentTask.destination();
        long currentKey = importInventoryKey(currentDestination);
        emptyShulkerStagingTriedDestinations.add(currentKey);
        importDestinationTracker.recordRejected(currentKey, cargoKey);
        Map<String, Object> capacity = importCapacityDetails(
                rejectedAdmission,
                emptyShulkerStagingTriedDestinations.size(),
                cargoItemId);
        if (shouldRollbackBlockedMixedShulker(
                emptyShulkerStagingTriedDestinations.size())) {
            capacity.put("max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES);
            capacity.put("disposition", "return_box_to_vacated_source");
            emit("organize_import_capacity_probe_budget_reached", capacity);
            return false;
        }
        if (shouldStartCurrentPackedImportCapacityRecovery(
                emptyShulkerStagingTriedDestinations.size())) {
            capacity.put("max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES);
            capacity.put("disposition", "compact_staged_import_cargo");
            emit("organize_import_capacity_probe_budget_reached", capacity);
            return false;
        }
        for (int[] candidate : orderedWritableImportDestinations(cargoKey, cargoItemId)) {
            long key = importInventoryKey(candidate);
            if (emptyShulkerStagingTriedDestinations.contains(key)) continue;
            currentTask = currentTask.withDestination(copyPos(candidate));
            currentRole = TargetRole.DESTINATION;
            walkTarget = currentTask.destination();
            actionSlotIndex = 0;
            depositColumnIndex = 0;
            containerDataReceived = false;
            trackedWalkTargetKey = Long.MIN_VALUE;
            state = State.WALKING;
            persistDurableCheckpoint(state);
            emit(isEmptyShulkerStagingTask(currentTask)
                            ? "organize_empty_shulker_staging_switched"
                            : "organize_import_staging_switched", capacity);
            return true;
        }
        emit("organize_import_capacity_exhausted", capacity);
        return false;
    }

    private boolean rerouteCurrentCargoToImportStaging(String reason) {
        int[] staging = firstWritableImportDestination();
        if (currentTask == null || staging == null) return false;
        currentTask = currentTask.withDestination(staging);
        currentRole = TargetRole.DESTINATION;
        walkTarget = currentTask.destination();
        actionSlotIndex = 0;
        depositColumnIndex = 0;
        containerDataReceived = false;
        trackedWalkTargetKey = Long.MIN_VALUE;
        state = State.WALKING;
        persistDurableCheckpoint(state);
        emit("organize_cargo_staged", Map.of(
                "reason", reason,
                "disposition", "import_staging"
        ));
        return true;
    }

    private int[] firstWritableImportDestination() {
        List<int[]> destinations = orderedWritableImportDestinations();
        return destinations.isEmpty() ? null : copyPos(destinations.get(0));
    }

    private List<int[]> orderedWritableImportDestinations() {
        return orderedWritableImportDestinations(
                currentTaskCargoRoutingKey(),
                currentTask == null ? null : currentTask.itemId());
    }

    private List<int[]> orderedWritableImportDestinations(
            String cargoKey,
            String cargoItemId) {
        record RankedImport(long inventoryKey, int[] position, int matchingItems, int freeSlots) {}

        refreshStagingImportDestinations();
        Map<Long, RankedImport> unique = new LinkedHashMap<>();
        for (int[] candidate : stagingImportDestinations) {
            ContainerEntry entry = index.get(candidate[0], candidate[1], candidate[2]);
            long key = importInventoryKey(candidate);
            int matchingItems = ImportStagingPolicy.looseItemCount(entry, cargoItemId);
            int freeSlots = entry == null
                    ? 0
                    : ImportStagingPolicy.from(entry).estimatedFreeSlots();
            RankedImport ranked = new RankedImport(
                    key, copyPos(candidate), matchingItems, freeSlots);
            unique.merge(key, ranked, (current, replacement) ->
                    replacement.matchingItems() > current.matchingItems()
                            || (replacement.matchingItems() == current.matchingItems()
                                && replacement.freeSlots() > current.freeSlots())
                            ? replacement
                            : current);
        }
        List<RankedImport> ranked = unique.values().stream()
                .sorted(Comparator
                        .comparing((RankedImport candidate) -> candidate.matchingItems() > 0)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                RankedImport::matchingItems).reversed())
                        .thenComparing(Comparator.comparingInt(
                                RankedImport::freeSlots).reversed())
                        .thenComparingLong(RankedImport::inventoryKey))
                .toList();
        Map<Long, int[]> byKey = ranked.stream().collect(Collectors.toMap(
                RankedImport::inventoryKey,
                RankedImport::position,
                (current, ignored) -> current,
                LinkedHashMap::new));
        ItemStack cargo = findImportCargo(cargoKey, cargoItemId);
        boolean needsEmptySlot = cargoKey != null && cargoKey.startsWith("packed_shulker\u0000");
        long now = System.currentTimeMillis();
        return importDestinationTracker.order(byKey.keySet(), cargoKey).stream()
                .sorted(Comparator.comparingInt(key -> importCapacityCache.rank(key, cargo, needsEmptySlot, now)))
                .map(byKey::get)
                .map(StashOrganizer::copyPos)
                .toList();
    }

    /** Long jobs and restored checkpoints can outlive import-chest registration changes. */
    private void refreshStagingImportDestinations() {
        if (config.pos1 == null || config.pos2 == null) return;
        index.getInRegion(config.pos1, config.pos2).stream()
                .filter(index::isImportChest)
                .map(entry -> new int[]{entry.x(), entry.y(), entry.z()})
                .forEach(this::rememberStagingImportDestination);
    }

    private void recordWritableImportDestination(int[] destination, String cargoKey) {
        if (destination == null || !index.isImportChest(
                destination[0], destination[1], destination[2])) return;
        importDestinationTracker.recordWritable(importInventoryKey(destination), cargoKey);
    }

    private ItemStack findImportCargo(String cargoKey, String itemId) {
        if (itemId == null) return null;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(rawSlot);
            if (stack == null || stack.getAmount() <= 0 || !itemId.equals(itemIdFromStack(stack))) continue;
            if (Objects.equals(cargoKey, cargoRoutingKey(stack))) return stack;
        }
        return null;
    }

    private void rememberLiveImportCapacity() {
        boolean importVisit = switch (state) {
            case OPENING, TAKING, CLOSING_SOURCE, DEPOSITING, CLOSING_DEST,
                 SHULKER_FETCH_OPEN, SHULKER_FETCH_TAKE, SHULKER_FETCH_CLOSING,
                 SHULKER_STORE_OPEN, SHULKER_STORE_DEPOSIT,
                 MIXED_STAGE_OPEN, MIXED_STAGE_DEPOSIT, MIXED_STAGE_CLOSING,
                 OVERFLOW_OPENING, OVERFLOW_DEPOSITING -> true;
            default -> false;
        };
        Container open = getLiveOpenContainer();
        if (!importVisit || open == null || walkTarget == null
                || !index.isImportChest(walkTarget[0], walkTarget[1], walkTarget[2])) return;
        importCapacityCache.record(importInventoryKey(walkTarget),
                ContainerCapacitySnapshot.read(getOpenContainerSlotCount(open), open::getItemStack),
                System.currentTimeMillis());
    }

    private void noteImportCapacityMiss() {
        importCapacityMisses++;
        if (importCapacityMisses >= 64) flushImportCapacitySummary("search_continuing");
    }

    private void flushImportCapacitySummary(String disposition) {
        if (importCapacityMisses == 0) return;
        emit("organize_import_capacity_summary", Map.of(
                "capacity_misses", importCapacityMisses,
                "disposition", disposition));
        importCapacityMisses = 0;
    }

    static int[] packedLaneDestination(String storageClass, Map<String, Column> assignments, int[] fallback) {
        Column lane = assignments.get(storageClass);
        if (lane == null || lane.chests().isEmpty()) return copyNullablePos(fallback);
        // Retain a cascade within this exact lane; replace stale import destinations.
        if (lane.chests().stream().anyMatch(chest -> Arrays.equals(chest, fallback))) return copyPos(fallback);
        return copyPos(lane.top());
    }

    private long importInventoryKey(int[] destination) {
        if (destination == null || destination.length < 3) return Long.MIN_VALUE;
        ContainerEntry entry = index.get(destination[0], destination[1], destination[2]);
        return entry != null && entry.isDouble() && entry.inventoryIdentityKnown()
                ? entry.inventoryKey()
                : posKey(destination[0], destination[1], destination[2]);
    }

    private String currentTaskCargoRoutingKey() {
        if (currentTask == null) return "unknown";
        return currentTask.itemId() + "\u0000"
                + Objects.toString(currentTask.shulkerContentFilter(), "");
    }

    private String packedCargoRoutingKey() {
        return "packed_shulker\u0000" + Objects.toString(packItemId, "unknown");
    }

    private static String cargoRoutingKey(ItemStack stack) {
        if (stack == null) return "unknown";
        return itemIdFromStack(stack) + "\u0000"
                + Integer.toHexString(Objects.hashCode(stack.getDataComponents()));
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
            if (!eligibleConsolidationTasks().isEmpty()) {
                consolidationMode = true;
                info("Starting condensing — packing loose items into shulker boxes...");
                advanceConsolidation();
                return;
            }
            finishOrganization();
            return;
        }

        currentTask = taskQueue.poll();
        taskCargo.reset(currentTask.alreadyInInventory()
                ? countCurrentTaskCargoUnitsInInventory()
                : 0);
        emptyShulkerStagingTriedDestinations.clear();
        if (currentTask.alreadyInInventory()) {
            // Item is already in hand (deposited from the bot's own inventory) — no need
            // to walk to/open a source container. Mixed boxes must be decomposed at the
            // reconciliation station; ordinary cargo can go straight to its destination.
            if (currentTask.mixedDecomposition()
                    && !isMixedSourceRollbackTask(currentTask)) {
                startMixedShulkerDecomposition();
                return;
            }
            if (currentTask.mixedBatchConsolidation()) {
                // Recovery may promote an existing inventory packing task ahead of normal
                // work. Preserve its packing contract instead of treating it as loose cargo
                // to dump directly into the import destination.
                consolidationMode = true;
                mixedBatchConsolidationMode = true;
                consolidationSourcesInBatch = Math.max(1, consolidationSourcesInBatch);
                startShulkerPacking(currentTask.itemId(), currentTask.destination());
                return;
            }
            if (isEmptyShulkerStagingTask(currentTask)
                    && reuseEmptyShulkerForDeferredPacking()) {
                return;
            }
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
        if (!supplyDeferredTasks.isEmpty()) {
            if (releaseSupplyDeferredGroup(countEmptyPackingShells(null) > 0)) {
                consolidationMode = true;
                mixedBatchConsolidationMode = false;
                advanceConsolidation();
                return;
            }
            currentTask = null;
            state = State.CLOSING_SOURCE;
            abortWithCargo("packing_supply_unavailable",
                    "Remaining packing cargo is saved in import chests. Add an empty shulker to an unprotected "
                            + "inventory slot, then resume the saved job.");
            return;
        }
        if (stageUnusedPackingShells()) return;
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

    private boolean stageUnusedPackingShells() {
        Set<String> colors = new LinkedHashSet<>();
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (isEmptyPackingShell(stack)) colors.add(itemIdFromStack(stack));
        }
        if (colors.isEmpty()) return false;
        int[] destination = firstWritableImportDestination();
        if (destination == null) {
            abortWithCargo("empty_shulker_staging_full_with_cargo",
                    "Unused empty shulkers remain in inventory; provide import space before resuming.");
            return true;
        }
        for (String itemId : colors) taskQueue.addLast(new MoveTask(currentPlayerPosition(), copyPos(destination),
                itemId, EMPTY_SHULKER_STAGING_FILTER, true));
        totalTasks += colors.size();
        consolidationMode = false;
        mixedBatchConsolidationMode = false;
        emit("organize_empty_shulker_staging_queued", Map.of(
                "reason", "unused_packing_reserve", "shell_colors", colors.size()));
        advanceToNextTask();
        return true;
    }

    private Map<String, Object> organizerCompletionPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completed_tasks", completedTasks);
        payload.put("total_tasks", totalTasks);
        payload.put("overflow_types", overflowItems.size());
        payload.put("staged_shulkers", stagedShulkers);
        payload.put("decomposed_mixed_shulkers", decomposedMixedShulkers);
        payload.put("generated_loose_tasks", generatedLooseTasks);
        payload.put("staged_storage_classes", stagedStorageClasses.size());
        payload.put("staging_storage_classes_planned", stagingStorageClassesPlanned.size());
        payload.put("permanent_lane_gaps", permanentLaneGaps);
        if (stagingReason != null) payload.put("staging_reason", stagingReason);
        return payload;
    }

    // Container Interaction
    private void resetContainerOpenTracking() {
        openWaitTicks = 0;
        containerCacheReadyTicks = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
        containerDataReceived = false;
        openContainerId = -1;
        containerOpenGate.reset();
    }

    private boolean prepareStandingContainerInteraction() {
        setPlaceBlockSneak(false);
        if (containerOpenGate.tick(BOT.isSneaking())) return true;
        INPUTS.submit(InputRequest.builder()
                .owner(this)
                .input(Input.builder().sneaking(false).build())
                .priority(SneakReleaseGate.INPUT_PRIORITY)
                .build());
        return false;
    }

    private void requestContainerInteraction(int[] position) {
        if (position == null) return;
        if (ownedBaritoneProcess == OwnedBaritoneProcess.INTERACTION
                && ownedBaritoneRequest != null
                && !ownedBaritoneRequest.isCompleted()) {
            if (lastOpenInteractionTick < 0
                    || openWaitTicks - lastOpenInteractionTick < INTERACTION_ATTEMPT_TIMEOUT_TICKS) {
                return;
            }
            stopOwnedBaritoneProcess();
        }
        openInteractionAttempts++;
        lastOpenInteractionTick = openWaitTicks;
        ownInteraction(BARITONE.rightClickBlock(position[0], position[1], position[2]));
    }

    private Container awaitLiveOpenContainer() {
        Container open = getLiveOpenContainer();
        if (open != null) {
            containerCacheReadyTicks = 0;
            return open;
        }
        containerCacheReadyTicks++;
        return null;
    }

    private boolean containerCacheReadyTimedOut() {
        return containerCacheReadyTicks >= CONTAINER_CACHE_READY_TIMEOUT_TICKS;
    }

    private Map<String, Object> openFailureDetails(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("interaction_attempts", openInteractionAttempts);
        details.put("content_packet_received", containerDataReceived);
        details.put("packet_container_id", openContainerId);
        details.put("cache_container_id",
                CACHE.getPlayerCache().getInventoryCache().getOpenContainerId());
        details.put("cache_wait_ticks", containerCacheReadyTicks);
        details.put("distance", walkTarget == null ? -1.0 : distanceTo(walkTarget));
        return details;
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
        recordLiveContainerObservation();
        rememberLiveImportCapacity();
        int cacheContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (cacheContainerId <= 0) {
            clearPendingQuickMove();
            containerDataReceived = false;
            openContainerId = -1;
            containerCacheReadyTicks = 0;
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
        clearPendingQuickMove();
        containerDataReceived = false;
        openContainerId = -1;
        containerCacheReadyTicks = 0;
    }

    private void closeCurrentContainerForYield() {
        recordLiveContainerObservation();
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
        // A rejected follow-up does not replace the accepted request still executing for us.
        if (request != null && request.isCompleted() && !request.getNow()
                && ownedInventoryRequest != null && !ownedInventoryRequest.isCompleted()) {
            return;
        }
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
        restoreStationWalkSettings();
        ownedBaritoneRequest = null;
        ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
        ownedInventoryRequest = null;
        packingHeadroomTransferPending = false;
        clearPendingQuickMove();
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
            Map<String, Object> details = openFailureDetails(retryReason);
            details.put("attempt", attempt);
            details.put("max_attempts", MAX_DESTINATION_OPEN_RETRIES);
            emit("organize_target_failed", details);
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
        if (knownFull && !staging) {
            packStoreTriedDestinations.add(importInventoryKey(packDestination));
            Column lane = columnAssignment.get(packItemId);
            if (lane != null) {
                for (int[] chest : lane.chests()) {
                    if (packStoreTriedDestinations.contains(importInventoryKey(chest))) continue;
                    packDestinationOpenFailures = 0;
                    walkToPackedShulkerDestination(chest);
                    return;
                }
            }
            packingLaneExhausted = true;
            List<int[]> imports = orderedWritableImportDestinations(packedCargoRoutingKey(), null);
            if (!imports.isEmpty()) {
                emit("organize_packed_shulker_staged", Map.of(
                        "reason", "assigned_lane_full", "storage_class", packItemId));
                packDestinationOpenFailures = 0;
                walkToPackedShulkerDestination(imports.getFirst());
                return;
            }
            abortWithCargo("packed_shulker_lane_full",
                    "The assigned lane is full and no import chest is registered. The packed box is preserved.");
            return;
        }
        if (knownFull && staging) noteImportCapacityMiss();
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
            long failedKey = importInventoryKey(packDestination);
            packStoreTriedDestinations.add(failedKey);
            if (knownFull) {
                importDestinationTracker.recordRejected(
                        failedKey, packedCargoRoutingKey());
            }
            boolean probeBatchComplete = packedImportProbeBatchComplete(
                    packStoreTriedDestinations.size());
            if (probeBatchComplete) {
                if (deferPackedImportShulkerForLaneRecovery(
                        "packed_import_probe_budget_exhausted")) {
                    emit("organize_import_capacity_probe_budget_reached", Map.of(
                            "reason", "packed_import_probe_budget_exhausted",
                            "destinations_tried", packStoreTriedDestinations.size(),
                            "max_probes", MAX_PACKED_IMPORT_CAPACITY_PROBES,
                            "disposition", "compact_staged_import_cargo"));
                    return;
                }
            }
            List<int[]> candidates = orderedWritableImportDestinations(
                    packedCargoRoutingKey(), null);
            for (int[] candidate : candidates) {
                long key = importInventoryKey(candidate);
                if (packStoreTriedDestinations.contains(key)) continue;
                packDestination = new int[]{candidate[0], candidate[1], candidate[2]};
                packDestinationOpenFailures = 0;
                packStoreMatchingShulkersBefore = 0;
                packStoreVerificationTicks = 0;
                if (probeBatchComplete) {
                    emit("organize_import_capacity_probe_batch_advanced", Map.of(
                            "reason", "packed_import_probe_batch_complete",
                            "destinations_tried", packStoreTriedDestinations.size(),
                            "registered_destinations", candidates.size(),
                            "remaining_destinations", Math.max(0,
                                    candidates.size() - packStoreTriedDestinations.size()),
                            "batch_size", MAX_PACKED_IMPORT_CAPACITY_PROBES,
                            "disposition", "continue_untried_imports"));
                }
                info("Import staging destination was unavailable; trying another registered import chest.");
                if (!knownFull) emit("organize_target_failed", Map.of(
                        "reason", reason, "retry_disposition", "alternate_import"));
                walkToPackedShulkerDestination(packDestination);
                return;
            }
            if (deferPackedImportShulkerForLaneRecovery(
                    "import_staging_capacity_exhausted")) {
                return;
            }
            emit("organize_import_capacity_exhausted", Map.of(
                    "reason", "all_registered_imports_rejected",
                    "destinations_tried", packStoreTriedDestinations.size(),
                    "registered_destinations", candidates.size(),
                    "disposition", "preserve_box_and_stop"));
            abortWithCargo("import_staging_full_with_cargo",
                    "All registered import inventories were checked and none can currently accept the reconciled shulker. It is preserved in inventory; free a slot or register another import, then resume.");
            return;
        }

        abortWithCargo("packed_shulker_destination_unavailable_with_cargo",
                "The permanent shulker destination remained unavailable; the reconciled shulker is preserved in inventory.");
    }

    static boolean packedImportProbeBudgetReached(int destinationsTried) {
        return destinationsTried >= MAX_PACKED_IMPORT_CAPACITY_PROBES;
    }

    private static boolean packedImportProbeBatchComplete(int destinationsTried) {
        return destinationsTried >= MAX_PACKED_IMPORT_CAPACITY_PROBES
                && destinationsTried % MAX_PACKED_IMPORT_CAPACITY_PROBES == 0;
    }

    /** Reconcile a collected box with player inventory before spending destination retries. */
    private void recoverPackedShulkerInventoryVisibility(String reason) {
        closeCurrentContainer();
        int attempt = ++packedShulkerInventorySyncRecoveries;
        if (attempt > MAX_PACKED_SHULKER_INVENTORY_SYNC_RECOVERIES) {
            abortWithCargo("packed_shulker_inventory_sync_unresolved",
                    "The packed shulker pickup could not be reconciled with inventory; its checkpoint is preserved.");
            return;
        }

        packStoreVerificationTicks = 0;
        packStoreMatchingShulkersBefore = 0;
        packDestinationOpenFailures = 0;
        temporaryShulkerOutstanding = true;
        shulkerRecoveryTrigger = PACKED_SHULKER_INVENTORY_SYNC_RECOVERY;
        state = State.SHULKER_PICKUP;
        shulkerTicks = 0;
        resetShulkerPickupSweep();
        emit("organize_target_failed", Map.of(
                "reason", reason,
                "retry_disposition", "pickup_inventory_recovery",
                "attempt", attempt,
                "max_attempts", MAX_PACKED_SHULKER_INVENTORY_SYNC_RECOVERIES,
                "pickup_packet_confirmed", temporaryShulkerPickupConfirmed));
        persistDurableCheckpoint(state);
    }

    /**
     * A lane-less packed output must not block lane-backed recovery cargo which can drain the
     * imports and create its destination slot. Keep the finished box aboard as an ordinary
     * handoff task, process the lane-backed batch, then retry this box before normal work.
     */
    private boolean deferPackedImportShulkerForLaneRecovery(String reason) {
        if (!mixedBatchConsolidationMode
                || !isImportStagingPack()
                || packItemId == null
                || packDestination == null
                || countItemInInventory(packItemId) > 0) {
            return false;
        }
        String shulkerItemId = packedShulkerItemIdInInventory(packItemId);
        if (shulkerItemId == null) return false;
        MoveTask capacityRecovery = takeImportCapacityRecoveryTask();
        if (capacityRecovery == null && !hasPendingAssignedInventoryCargo()) return false;

        BARITONE.stop();
        closeCurrentContainer();
        clearPendingQuickMove();
        MoveTask deferredHandoff = new MoveTask(
                currentPlayerPosition(),
                copyPos(packDestination),
                shulkerItemId,
                packItemId,
                true);
        taskQueue.addFirst(deferredHandoff);
        currentTask = null;
        consolidationSourcesInBatch = 0;
        packStoreTriedDestinations.clear();
        emit("organize_packed_shulker_deferred", Map.of(
                "reason", reason,
                "storage_class", packItemId,
                "disposition", capacityRecovery == null
                        ? "drain_lane_backed_import_cargo_then_retry"
                        : "compact_staged_import_cargo_then_retry"
        ));
        if (capacityRecovery != null) {
            startImportCapacityRecovery(capacityRecovery, reason);
        } else {
            advanceConsolidation();
        }
        return true;
    }

    /** Preserve a blocked packed box while staged cargo is compacted to create a real slot. */
    private boolean deferCurrentPackedImportHandoffForCapacityRecovery() {
        if (!isPackedImportHandoffTask(currentTask)) return false;
        MoveTask capacityRecovery = takeImportCapacityRecoveryTask();
        if (capacityRecovery == null) return false;

        MoveTask deferredHandoff = currentTask;
        if (!taskQueue.contains(deferredHandoff)) taskQueue.addFirst(deferredHandoff);
        currentTask = null;
        consolidationSourcesInBatch = 0;
        emit("organize_packed_shulker_deferred", Map.of(
                "reason", "import_handoff_capacity_exhausted",
                "storage_class", Objects.toString(
                        deferredHandoff.shulkerContentFilter(), "unknown"),
                "disposition", "compact_staged_import_cargo_then_retry"
        ));
        startImportCapacityRecovery(capacityRecovery,
                "deferred_packed_handoff_capacity_exhausted");
        return true;
    }

    /** Preserve loose packing cargo while one import batch is compacted to create room. */
    private boolean deferLoosePackingOverflowForCapacityRecovery() {
        if (!isLoosePackingOverflowTask()) return false;
        MoveTask capacityRecovery = takeImportCapacityRecoveryTask();
        if (capacityRecovery == null) return false;

        MoveTask blocked = currentTask;
        MoveTask deferred = blocked.alreadyInInventory() && blocked.mixedBatchConsolidation()
                ? blocked
                : MoveTask.mixedBatchInInventory(
                        currentPlayerPosition(), copyPos(blocked.destination()), blocked.itemId());
        taskQueue.addFirst(deferred);
        currentTask = null;
        consolidationSourcesInBatch = 0;
        stagingForPackingSupply = false;
        supplyStagingLedger.reset();
        emit("organize_loose_cargo_deferred", Map.of(
                "reason", "overflow_import_probe_budget_exhausted",
                "storage_class", blocked.itemId(),
                "cargo_units", taskCargo.remaining(),
                "disposition", "compact_staged_import_cargo_then_retry"));
        startImportCapacityRecovery(capacityRecovery,
                "deferred_loose_cargo_capacity_exhausted");
        return true;
    }

    private boolean isLoosePackingOverflowTask() {
        return stagingForPackingSupply
                && currentTask != null
                && currentTask.shulkerContentFilter() == null
                && !isShulkerBoxItem(currentTask.itemId())
                && taskCargo.remaining() > 0;
    }

    private void startImportCapacityRecovery(MoveTask task, String reason) {
        consolidationMode = true;
        mixedBatchConsolidationMode = true;
        emit("organize_import_capacity_recovery_started", Map.of(
                "reason", reason,
                "storage_class", task.itemId(),
                "source", posString(task.source()),
                "disposition", "compact_source_then_retry_deferred_handoff"
        ));
        startMixedStagedLaneCargo(task);
    }

    /** Keep same-class sources adjacent so one box can replace several loose import stacks. */
    private MoveTask takeImportCapacityRecoveryTask() {
        Deque<MoveTask> eligible = eligibleConsolidationTasks();
        MoveTask selected = eligible.stream()
                .filter(this::isImportCapacityRecoveryTask)
                .findFirst().orElse(null);
        if (selected == null) return null;

        consolidationQueue.remove(selected);
        List<MoveTask> siblings = consolidationQueue.stream()
                .filter(this::isImportCapacityRecoveryTask)
                .filter(task -> Objects.equals(task.itemId(), selected.itemId()))
                .toList();
        consolidationQueue.removeAll(siblings);
        for (int index = siblings.size() - 1; index >= 0; index--) {
            consolidationQueue.addFirst(siblings.get(index));
        }
        return selected;
    }

    private boolean isImportCapacityRecoveryTask(MoveTask task) {
        if (task == null || !task.mixedBatchConsolidation() || task.alreadyInInventory()) {
            return false;
        }
        int[] source = task.source();
        return source != null && index.isImportChest(source[0], source[1], source[2]);
    }

    private boolean shouldStartCurrentPackedImportCapacityRecovery(int destinationsTried) {
        if (destinationsTried < MAX_PACKED_IMPORT_CAPACITY_PROBES
                || !isPackedImportHandoffTask(currentTask)) {
            return false;
        }
        return eligibleConsolidationTasks().stream()
                .anyMatch(this::isImportCapacityRecoveryTask);
    }

    private boolean isPackedImportHandoffTask(MoveTask task) {
        if (task == null || !task.alreadyInInventory()
                || task.mixedDecomposition()
                || !isShulkerBoxItem(task.itemId())
                || task.shulkerContentFilter() == null
                || EMPTY_SHULKER_STAGING_FILTER.equals(task.shulkerContentFilter())) {
            return false;
        }
        int[] destination = task.destination();
        return destination != null
                && index.isImportChest(destination[0], destination[1], destination[2]);
    }

    private boolean shouldRollbackBlockedMixedShulker(int destinationsTried) {
        if (destinationsTried < MAX_PACKED_IMPORT_CAPACITY_PROBES
                || !mixedDecompositionMode
                || currentTask == null
                || !currentTask.mixedDecomposition()
                || mixedBoxDrained
                || !mixedCargoSlots.isEmpty()
                || Arrays.equals(currentTask.source(), currentTask.destination())) {
            return false;
        }
        ShulkerClassification classification = currentMixedShulkerClassificationInInventory();
        return classification != null
                && classification.kind() == ShulkerClassification.Kind.MIXED
                && currentTask.shulkerContentFilter().equals(classification.fingerprint());
    }

    /** Return an untouched mixed box to the source slot it vacated, then retry it once. */
    private boolean rollbackBlockedMixedShulkerToSource(String reason) {
        if (!shouldRollbackBlockedMixedShulker(MAX_PACKED_IMPORT_CAPACITY_PROBES)) return false;

        MoveTask blocked = currentTask;
        ShulkerClassification classification = currentMixedShulkerClassificationInInventory();
        if (classification == null) return false;
        boolean retryAtTail = !blocked.mandatoryInventoryDeposit();
        if (retryAtTail) {
            taskQueue.addLast(blocked.requireInventoryDeposit());
            totalTasks++;
        }

        currentTask = new MoveTask(
                copyPos(blocked.source()),
                copyPos(blocked.source()),
                blocked.itemId(),
                classification.fingerprint(),
                true,
                true,
                false,
                classification.contents(),
                true);
        taskCargo.reset(countCurrentTaskCargoUnitsInInventory());
        clearMixedDecompositionState();
        resetTemporaryShulkerState();
        currentRole = TargetRole.DESTINATION;
        walkTarget = copyPos(currentTask.destination());
        trackedWalkTargetKey = Long.MIN_VALUE;
        actionSlotIndex = 0;
        depositColumnIndex = 0;
        containerDataReceived = false;
        state = State.WALKING;
        emit("organize_mixed_shulker_rollback_started", Map.of(
                "reason", reason,
                "disposition", retryAtTail
                        ? "return_to_source_then_retry_tail_once"
                        : "return_to_source_then_quarantine",
                "cargo_preserved", true));
        persistDurableCheckpoint(state);
        return true;
    }

    private static boolean isMixedSourceRollbackTask(MoveTask task) {
        return task != null
                && task.alreadyInInventory()
                && task.mixedDecomposition()
                && task.mandatoryInventoryDeposit()
                && Arrays.equals(task.source(), task.destination());
    }

    private boolean hasDeferredPackedImportHandoff() {
        return taskQueue.stream().anyMatch(this::isPackedImportHandoffTask);
    }

    private boolean hasDeferredImportCapacityHandoff() {
        return taskQueue.stream().anyMatch(task ->
                isPackedImportHandoffTask(task) || isDeferredLooseImportHandoff(task));
    }

    private boolean isDeferredLooseImportHandoff(MoveTask task) {
        if (task == null || !task.alreadyInInventory()
                || !task.mixedBatchConsolidation()
                || isShulkerBoxItem(task.itemId())
                || task.destination() == null) {
            return false;
        }
        int[] destination = task.destination();
        return index.isImportChest(destination[0], destination[1], destination[2]);
    }

    private MoveTask deferredPackedImportHandoff(String storageClass) {
        if (storageClass == null) return null;
        return taskQueue.stream()
                .filter(this::isPackedImportHandoffTask)
                .filter(task -> ItemIdentifier.contentItemIdsMatch(
                        storageClass, task.shulkerContentFilter()))
                .findFirst().orElse(null);
    }

    private boolean hasPendingAssignedInventoryCargo() {
        return consolidationQueue.stream().anyMatch(task ->
                task.mixedBatchConsolidation()
                        && task.alreadyInInventory()
                        && columnAssignment.containsKey(task.itemId()));
    }

    private String packedShulkerItemIdInInventory(String storageClass) {
        if (storageClass == null) return null;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return null;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = player.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification classification = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            if (classification.kind() == ShulkerClassification.Kind.BULK
                    && ItemIdentifier.contentItemIdsMatch(
                            storageClass, classification.storageKey())) {
                return itemIdFromStack(stack);
            }
        }
        return null;
    }

    private void walkToPackedShulkerDestination(int[] destination) {
        packDestination = packingLaneExhausted ? copyNullablePos(destination)
                : packedLaneDestination(packItemId, columnAssignment, destination);
        currentRole = TargetRole.DESTINATION;
        walkTarget = packDestination;
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
        return importDestination;
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
        int attempt = sourceTaskFailures.recordFailure(currentTask);
        if (attempt < MAX_SOURCE_TASK_RETRIES) {
            info("Source task failed before moving cargo; requeueing at tail (" + attempt + "/"
                    + MAX_SOURCE_TASK_RETRIES + ").");
            Map<String, Object> details = openFailureDetails(reason);
            details.put("retry_disposition", "queue_tail");
            details.put("attempt", attempt);
            details.put("max_attempts", MAX_SOURCE_TASK_RETRIES);
            emit("organize_target_failed", details);
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

    private void abortWithCargo(String reason, String message) {
        flushImportCapacitySummary(reason);
        info(message);
        State failedState = state;
        rememberFailure(reason, failedState);
        BARITONE.stop();
        clearOwnedAutomation();
        closeCurrentContainer();
        restoreBaritoneBreaking();
        restorePlaceBlockSneak();
        // Keep the last transaction checkpoint. The user can repair the destination and use
        // /stash organize resume without replaying the completed portion of a multi-hour job.
        boolean checkpointPreserved = persistDurableCheckpoint(failedState)
                || hasDurableCheckpoint();
        state = State.FAILED;
        emit("organize_failed", Map.of(
                "reason", reason,
                "failed_state", failedState.name(),
                "terminal", true,
                "cargo_preserved", true,
                "checkpoint_preserved", checkpointPreserved,
                "task_cargo_acquired", taskCargo.acquired(),
                "task_cargo_deposited", taskCargo.deposited(),
                "task_cargo_remaining", taskCargo.remaining()
        ));
    }

    private void rememberFailure(String reason, State failedState) {
        lastFailure = new OrganizerJournalStore.Failure(reason,
                Objects.toString(failedState, "unknown"), System.currentTimeMillis());
    }

    private void markFailed(String reason, State failedState) {
        rememberFailure(reason, failedState);
        state = State.FAILED;
    }

    private void recordLiveContainerObservation() {
        ContainerEntry indexed = openIndexedContainer;
        openIndexedContainer = null;
        Container open = getLiveOpenContainer();
        if (indexed == null || open == null) return;
        int expectedSlots = indexed.isDouble() ? 54
                : indexed.blockType().endsWith("hopper") ? 5 : 27;
        if (getOpenContainerSlotCount(open) != expectedSlots) return;
        try {
            index.recordInventoryObservation(
                    ContainerReader.snapshotContents(open, indexed, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not snapshot organizer container {}", indexed.posString(), e);
            emit("organize_container_snapshot_failed", Map.of("reason", "live_snapshot_unavailable"));
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
        return open != null && open.getContainerId() == openContainerId ? open : null;
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

        inventoryKeepPolicy = policy;
        if (!refreshProtectedInventorySlots()) {
            info("Cannot organize safely: player inventory is unavailable for keep-list protection.");
            emit("organize_start_blocked", Map.of("reason", "inventory_unavailable_for_keep_list"));
            return false;
        }
        keepProtectionNeedsRefresh = false;
        if (!protectedInventorySlots.isEmpty()) {
            info("Protected " + protectedInventorySlots.size()
                    + " live inventory slot(s) for this organization job.");
        }
        emit("organize_keep_snapshot", Map.of(
                "protected_slots", protectedInventorySlots.size()
        ));
        return true;
    }

    private boolean refreshProtectedInventorySlots() {
        if (inventoryKeepPolicy.isEmpty()) {
            protectedInventorySlots.clear();
            return true;
        }
        Container liveOpen = getLiveOpenContainer();
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (liveOpen == null && player == null) return false;

        List<InventoryKeepPolicy.SlotStack> inventory = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                inventory.add(new InventoryKeepPolicy.SlotStack(
                        slot, itemIdFromStack(stack), stack.getAmount(),
                        ItemIdentifier.hasCustomPresentation(stack)));
            }
        }
        int movableCargoUnits = taskCargo.remaining();
        if (movableCargoUnits == 0 && durableRecoveryLoaded && currentTask != null) {
            // Journals written before cargo counts were persisted can still recover a finite
            // keep-list handoff from the inventory excess. This is the migration path for a
            // checkpoint that failed after accepting cargo but before staging it.
            movableCargoUnits = inventoryKeepPolicy.inferredMovableUnits(
                    currentTask.itemId(), inventory);
        }
        Set<Integer> refreshed = inventoryKeepPolicy.protectedSlots(
                inventory,
                currentTask == null ? null : currentTask.itemId(),
                movableCargoUnits);
        protectedInventorySlots.clear();
        protectedInventorySlots.addAll(refreshed);
        return true;
    }

    private boolean isProtectedInventorySlot(int rawSlot) {
        return protectedInventorySlots.contains(rawSlot);
    }

    // Settle the original source slot before a caller scans for another candidate. The live
    // update often drains that slot before the next organizer tick; tying verification to the
    // caller's newly selected slot strands the accepted move and can fill the bot inventory.
    private QuickMovePoll pollPendingQuickMove() {
        if (pendingQuickMove == null) return QuickMovePoll.none();

        Container open = getLiveOpenContainer();
        if (open == null || openContainerId < 0
                || pendingQuickMove.containerId != openContainerId
                || pendingQuickMove.slot < 0
                || pendingQuickMove.slot >= open.getSize()) {
            int lostSlot = pendingQuickMove.slot;
            recordQuickMoveFailure("inventory_transfer_window_lost", pendingQuickMove);
            pendingQuickMove = null;
            return new QuickMovePoll(QuickMoveOutcome.RETRYING, lostSlot, 0);
        }

        int slot = pendingQuickMove.slot;
        boolean completed = pendingQuickMove.request.isCompleted();
        boolean accepted = completed && pendingQuickMove.request.getNow();
        InventoryTransferEvidence evidence = pendingQuickMove.evidence;
        int observedMoved = evidence.moved(open);
        if (observedMoved > 0) {
            int requested = pendingQuickMove.amount;
            boolean taking = evidence.taking();
            int recoveredAttempts = quickMoveFailureAttempts;
            pendingQuickMove = null;
            quickMoveFailureKey = null;
            quickMoveFailureAttempts = 0;
            emit("organize_transfer_confirmed", Map.of(
                    "reason", taking ? "inventory_transfer_confirmed_from_player_window"
                            : "inventory_transfer_confirmed_from_player_decrease",
                    "evidence", taking ? "exact_stack_received" : "exact_stack_departed",
                    "container_id", openContainerId,
                    "slot", slot,
                    "requested_amount", requested,
                    "moved_amount", observedMoved,
                    "player_units_before", evidence.playerBefore(),
                    "player_units_now", evidence.playerUnits(open),
                    "receiver_observed_amount", evidence.received(open)
            ));
            if (recoveredAttempts > 0) {
                emit("organize_recovery_completed", Map.of(
                        "reason", "inventory_transfer_confirmed",
                        "attempts", recoveredAttempts + 1,
                        "container_id", openContainerId,
                        "slot", slot
                ));
            }
            return new QuickMovePoll(
                    observedMoved >= requested
                            ? QuickMoveOutcome.CONFIRMED_DRAINED
                            : QuickMoveOutcome.CONFIRMED_PARTIAL,
                    slot,
                    observedMoved);
        }
        InventoryTransferPolicy.Result result = InventoryTransferPolicy.assess(
                true,
                completed,
                accepted,
                false,
                false,
                pendingQuickMove.verificationTicks++,
                TRANSFER_VERIFICATION_TIMEOUT_TICKS);
        if (result == InventoryTransferPolicy.Result.WAIT) {
            return new QuickMovePoll(QuickMoveOutcome.WAITING, slot, 0);
        }
        recordQuickMoveFailure(accepted
                ? "inventory_transfer_unconfirmed"
                : "inventory_transfer_rejected", pendingQuickMove);
        pendingQuickMove = null;
        return new QuickMovePoll(QuickMoveOutcome.RETRYING, slot, 0);
    }

    private boolean submitQuickMove(int slot) {
        Container open = getLiveOpenContainer();
        if (pendingQuickMove != null || open == null || openContainerId < 0
                || slot < 0 || slot >= open.getSize()) {
            return false;
        }
        ItemStack source = open.getItemStack(slot);
        if (source == null || source.getAmount() <= 0) return false;

        try {
            var evidence = InventoryTransferEvidence.capture(open, getOpenContainerSlotCount(open), slot);
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(6000)
                    .actions(new ShiftClick(openContainerId, slot, ShiftClickItemAction.LEFT_CLICK))
                    .build());
            ownInventory(future);
            pendingQuickMove = new PendingQuickMove(
                    openContainerId, slot, source, evidence, future);
            if (future.isCompleted() && !future.getNow()) {
                recordQuickMoveFailure("inventory_transfer_rejected", pendingQuickMove);
                pendingQuickMove = null;
                return false;
            }
            return true;
        } catch (Exception e) {
            recordQuickMoveFailure("inventory_transfer_submission_failed", null);
            return false;
        }
    }

    private void recordQuickMoveFailure(String reason, PendingQuickMove transfer) {
        String key = openContainerId + ":" + (transfer == null ? -1 : transfer.slot)
                + ":" + state.name();
        if (!key.equals(quickMoveFailureKey)) {
            quickMoveFailureKey = key;
            quickMoveFailureAttempts = 0;
        }
        int attempt = ++quickMoveFailureAttempts;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("attempt", attempt);
        details.put("max_attempts", MAX_TRANSFER_RETRIES);
        details.put("container_id", openContainerId);
        details.put("slot", transfer == null ? -1 : transfer.slot);
        details.put("verification_ticks", transfer == null ? 0 : transfer.verificationTicks);
        Container open = getLiveOpenContainer();
        if (transfer != null) {
            details.put("requested_amount", transfer.amount);
            details.put("direction", transfer.evidence.taking() ? "take" : "deposit");
            details.put("receiver_units_before", transfer.evidence.receiverBefore());
            details.put("receiver_units_now", open == null ? -1 : transfer.evidence.receiverUnits(open));
            details.put("player_units_before", transfer.evidence.playerBefore());
            details.put("player_units_now", open == null ? -1 : transfer.evidence.playerUnits(open));
            details.put("request_completed", transfer.request.isCompleted());
            details.put("request_accepted", transfer.request.isCompleted() && transfer.request.getNow());
            details.put("free_inventory_slots", open == null ? -1 : countFreeUnprotectedInventorySlots());
        }
        emit("organize_target_failed", details);
        if (attempt < MAX_TRANSFER_RETRIES) return;

        if (temporaryShulkerOutstanding) {
            stopAfterShulkerRecovery = true;
            beginTemporaryShulkerRecovery(reason);
        } else {
            abortWithCargo("inventory_transfer_unavailable_with_cargo",
                    "An inventory transfer could not be confirmed after " + attempt
                            + " attempts; the job stopped with cargo preserved.");
        }
    }

    private void clearPendingQuickMove() {
        pendingQuickMove = null;
    }

    // Inventory Helpers
    private boolean hasInventoryRoom() {
        return countFreeUnprotectedInventorySlots() > 0;
    }

    private boolean canTakeWhilePreservingPackingHeadroom(
            Container open,
            int containerSlots,
            ItemStack incoming) {
        if (open == null || incoming == null || incoming.getAmount() <= 0) return false;
        ItemData incomingData = ItemRegistry.REGISTRY.get(incoming.getId());
        int maxStack = incomingData == null ? 1 : Math.max(1, incomingData.stackSize());
        int freeSlots = 0;
        int matchingHeadroom = 0;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int windowSlot = rawPlayerSlotToWindowSlot(containerSlots, rawSlot);
            if (windowSlot < 0 || windowSlot >= open.getSize()) continue;
            ItemStack target = open.getItemStack(windowSlot);
            if (target == null || target.getAmount() <= 0) {
                freeSlots++;
            } else if (target.getId() == incoming.getId()
                    && target.getAmount() < maxStack
                    && Objects.equals(target.getDataComponents(), incoming.getDataComponents())) {
                matchingHeadroom += maxStack - target.getAmount();
            }
        }
        return PackingHeadroomPolicy.canTakeWithoutConsumingReserve(
                freeSlots,
                matchingHeadroom,
                incoming.getAmount(),
                MixedShulkerPlaybook.reservedHeadroomSlots());
    }

    private void pauseCollectionForPackingHeadroom() {
        if (currentTask == null) return;
        MoveTask blockedTask = currentTask;
        if (!consolidationQueue.contains(blockedTask)) consolidationQueue.addFirst(blockedTask);
        boolean batchReady = hasCollectedPackingCargo();
        emit("organize_packing_headroom_preserved", Map.of(
                "reserved_slots", MixedShulkerPlaybook.reservedHeadroomSlots(),
                "disposition", batchReady
                        ? "pack_current_batch_then_retry_source"
                        : "recover_existing_inventory_then_retry_source"
        ));
        if (batchReady) {
            sourceVisitFailed = true;
            state = State.CLOSING_SOURCE;
            closeCurrentContainer();
            persistDurableCheckpoint(state);
            return;
        }

        if (queueInventoryDepositTasks(true)) {
            consolidationMode = false;
            mixedBatchConsolidationMode = false;
            closeCurrentContainer();
            advanceToNextTask();
            return;
        }
        consolidationQueue.removeFirstOccurrence(blockedTask);
        abortWithCargo("packing_headroom_unavailable",
                "The organizer could not reserve an inventory slot for its packing shulker. Cargo is preserved.");
    }

    private boolean hasCollectedPackingCargo() {
        // Source/visit counters can outlive a handoff; only outstanding units justify packing.
        return taskCargo.remaining() > 0;
    }

    /**
     * Repairs an older/full checkpoint by lending one exact cargo stack to the shulker source.
     * The continuation task is journaled before the click, so a restart can never lose track
     * of cargo which the server accepted while the proxy was stopping.
     */
    private boolean stageOneCargoStackForPackingHeadroom(
            Container open,
            int chestSlots) {
        if (open == null || currentTask == null || walkTarget == null) return false;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int windowSlot = rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            if (windowSlot < 0 || windowSlot >= open.getSize()) continue;
            ItemStack cargo = open.getItemStack(windowSlot);
            if (cargo == null || cargo.getAmount() <= 0 || !currentTaskOwnsStack(cargo)) continue;

            ContainerAdmission admission = inspectContainerAdmission(open, chestSlots, cargo);
            if (!admission.canAccept()) continue;
            queuePackingHeadroomContinuation(walkTarget);
            persistDurableCheckpoint(State.SHULKER_FETCH_TAKE);
            packingHeadroomTransferPending = submitQuickMove(windowSlot);
            if (packingHeadroomTransferPending) {
                emit("organize_packing_headroom_recovery_started", Map.of(
                        "reason", "inventory_full_before_shulker_fetch",
                        "storage_class", currentTask.itemId(),
                        "disposition", "stage_one_stack_then_fetch_shulker"
                ));
                actionCooldown = config.organizerClickCooldownTicks;
                return true;
            }
            // Submission rejection is already tracked by the normal bounded transfer gate.
            // Yield this tick so retries keep the same failure identity and cannot fan out
            // across several inventory slots.
            actionCooldown = config.organizerClickCooldownTicks;
            return true;
        }
        return false;
    }

    private void queuePackingHeadroomContinuation(int[] source) {
        boolean alreadyQueued = consolidationQueue.stream().anyMatch(task ->
                !task.alreadyInInventory()
                        && task.itemId().equals(currentTask.itemId())
                        && Arrays.equals(task.source(), source));
        if (alreadyQueued) return;

        MoveTask continuation = currentTask.mixedBatchConsolidation()
                ? MoveTask.mixedBatch(
                        copyPos(source), copyPos(currentTask.destination()), currentTask.itemId())
                : new MoveTask(
                        copyPos(source), copyPos(currentTask.destination()), currentTask.itemId());
        consolidationQueue.addFirst(continuation);
        totalTasks++;
        generatedLooseTasks++;
        emitProgressMilestoneIfCrossed();
    }

    private int countFreeUnprotectedInventorySlots() {
        int free = 0;
        for (int slot = HOTBAR_SIZE; slot < 45; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack == null || stack.getAmount() == 0) free++;
        }
        return free;
    }

    private int countOccupiedUnprotectedInventorySlots() {
        int occupied = 0;
        for (int slot = HOTBAR_SIZE; slot < 45; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack != null && stack.getAmount() > 0) occupied++;
        }
        return occupied;
    }

    private boolean admitMixedShulkerTake(ItemStack sourceStack) {
        int occupiedSourceSlots = ItemIdentifier.readShulkerOccupiedSlots(sourceStack);
        if (occupiedSourceSlots <= 0 && currentTask != null) {
            occupiedSourceSlots = Math.max(1,
                    MixedShulkerPlaybook.minimumStagingSlots(currentTask.mixedContents()));
        }
        MixedShulkerPlaybook.InventoryAdmission admission =
                MixedShulkerPlaybook.assessInventoryAdmission(
                        countFreeUnprotectedInventorySlots(),
                        countOccupiedUnprotectedInventorySlots(),
                        occupiedSourceSlots);
        if (admission.ready()) {
            inventoryRecoveryGuard.reset();
            return true;
        }

        String recoveryKey = currentTask.itemId() + "|" + Arrays.toString(currentTask.source())
                + "|" + Objects.toString(currentTask.shulkerContentFilter(), "");
        if (!inventoryRecoveryGuard.allowRecovery(recoveryKey,
                admission.existingCargoSlots(), admission.freeSlots())) {
            emit("organize_recovery_loop_detected", Map.of(
                    "reason", "inventory_recovery_no_progress",
                    "recovery_attempts", InventoryRecoveryGuard.MAX_ATTEMPTS,
                    "existing_cargo_slots", admission.existingCargoSlots(),
                    "free_slots", admission.freeSlots()));
            abortWithCargo("inventory_recovery_no_progress",
                    "Three inventory recoveries returned to the same mixed box without freeing space or "
                            + "finishing packing. Cargo and the job checkpoint are preserved for inspection.");
            return false;
        }

        MoveTask blockedTask = currentTask;
        taskQueue.addFirst(blockedTask);
        if (queueInventoryDepositTasks(true)) {
            emit("organize_inventory_recovery_started", Map.ofEntries(
                    Map.entry("reason", "mixed_shulker_admission"),
                    Map.entry("decision", admission.decision().name().toLowerCase(Locale.ROOT)),
                    Map.entry("free_slots", admission.freeSlots()),
                    Map.entry("required_free_slots", admission.requiredFreeSlots()),
                    Map.entry("existing_cargo_slots", admission.existingCargoSlots()),
                    Map.entry("source_occupied_slots", admission.sourceOccupiedSlots())
            ));
            advanceToNextTask();
            return false;
        }

        taskQueue.removeFirstOccurrence(blockedTask);
        abortWithCargo("mixed_inventory_headroom_unavailable",
                "A mixed shulker needs " + admission.requiredFreeSlots()
                        + " free inventory slots and no other unprotected cargo. "
                        + "The box was left in its source container.");
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
        List<int[]> liveOrder = orderedWritableImportDestinations();
        if (!liveOrder.isEmpty()) return copyPos(liveOrder.get(0));

        // Planning can ask before the runtime list is hydrated. Use slot-aware scanned
        // capacity rather than totalItems < 27*64, which is wrong for double chests and
        // non-stackable cargo.
        return index.getInRegion(config.pos1, config.pos2).stream()
                .filter(index::isImportChest)
                .map(ImportStagingPolicy::from)
                .filter(candidate -> candidate.estimatedFreeSlots() > 0)
                .max(Comparator
                        .comparingInt(ImportStagingPolicy.Candidate::estimatedFreeSlots)
                        .thenComparingInt(ImportStagingPolicy.Candidate::x)
                        .thenComparingInt(ImportStagingPolicy.Candidate::y)
                        .thenComparingInt(ImportStagingPolicy.Candidate::z))
                .map(ImportStagingPolicy.Candidate::position)
                .orElse(null);
    }

    private static int[] currentPlayerPosition() {
        return new int[]{
                (int) Math.floor(CACHE.getPlayerCache().getX()),
                (int) Math.floor(CACHE.getPlayerCache().getY()),
                (int) Math.floor(CACHE.getPlayerCache().getZ())
        };
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
                || resumableState == State.IDLE || resumableState == State.DONE || resumableState == State.FAILED
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
                            task.mixedContents(), task.mandatoryInventoryDeposit());
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
                taskCargo.acquired(),
                taskCargo.deposited(),
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
                generatedLooseTasks,
                mixedPendingSourceSlot,
                mixedPendingCargoSlot,
                new ArrayList<>(mixedCargoSlots),
                mixedStagingUsedDestinations.stream().map(StashOrganizer::copyPos).toList(),
                new ArrayList<>(protectedInventorySlots),
                stopAfterShulkerRecovery,
                shulkerRecoveryTrigger,
                new OrganizerJournalStore.PackingProgress(shulkerFillMovedUnits, zeroFillCycles,
                        packedAtMaximumCapacity, packingLaneExhausted), lastFailure,
                mixedStagingLedger.snapshot(),
                new OrganizerJournalStore.PackingSupply(packingSupplySearch.snapshot(),
                        consolidationQueue.stream().filter(supplyDeferredTasks::contains)
                                .map(this::ensureJournalTaskId).toList(),
                        stagingForPackingSupply, supplyStagingLedger.snapshot(),
                        new LinkedHashMap<>(supplyStagesWithoutDelivery)),
                inventoryRecoveryGuard.snapshot(), stationWalkRecovery.snapshot(),
                mixedShellVerificationActive ? new OrganizerJournalStore.MixedShellVerification(
                        mixedShellVerificationTicks, temporaryShulkerPickupConfirmed, temporaryShulkerPickupFingerprint,
                        mixedShellVerificationCargo.stream().map(this::ensureJournalTaskId).toList()) : null,
                packStoreTriedDestinations.stream().sorted().toList(),
                new OrganizerJournalStore.ShulkerPickupEvidence(
                        temporaryShulkerPickupConfirmed, temporaryShulkerPickupFingerprint,
                        temporaryShulkerPickupStorageKey, packedShulkerInventorySyncRecoveries));
    }

    private void syncJournalTaskCatalog() {
        if (currentTask != null) ensureJournalTaskId(currentTask);
        taskQueue.forEach(this::ensureJournalTaskId);
        consolidationQueue.forEach(this::ensureJournalTaskId);
        mixedShellVerificationCargo.forEach(this::ensureJournalTaskId);
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
        supplyDeferredTasks.clear();
        supplyStagesWithoutDelivery.clear();
        stagingForPackingSupply = false;
        recoverOnboardMixedAfterPackedDelivery = false;
        supplyStagingLedger.reset();
        packingSupplySearch.reset();
        inventoryRecoveryGuard.reset();
        stationWalkRecovery.reset();
        resetMixedShellVerification();
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizer_state", state.name());
        payload.put("completed_tasks", completedTasks);
        payload.put("total_tasks", totalTasks);
        // Put the actionable cause before verbose task context so bounded debug exports retain it.
        if (extraFields != null && !extraFields.isEmpty()) payload.putAll(extraFields);
        if (currentTask != null) {
            payload.put("item_id", currentTask.itemId());
            payload.put("source_position", posString(currentTask.source()));
            payload.put("destination_position", posString(currentTask.destination()));
            if (currentTask.shulkerContentFilter() != null) {
                payload.put("shulker_content_filter", currentTask.shulkerContentFilter());
            }
        }
        if (walkTarget != null) payload.put("walk_target", posString(walkTarget));
        LOGGER.info("{} | {}", event, payload);
        if (eventCallback != null) eventCallback.accept(event, payload);
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
            case MIXED_SHELL_VERIFY -> "Waiting for recovered empty shulker inventory update...";
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
            case FAILED            -> "Needs attention: " + Objects.toString(getLastFailureReason(), "organizer_failed");
        };
        if (totalTasks > 0) {
            detail += " [" + completedTasks + "/" + totalTasks + "]";
            if (decomposedMixedShulkers > 0 || generatedLooseTasks > 0) {
                detail += " [mixed boxes " + decomposedMixedShulkers
                        + "; loose tasks generated " + generatedLooseTasks + "]";
            }
        }
        return detail;
    }

    // Helper Methods
    private void resetTemporaryShulkerState() {
        resetMixedShellVerification();
        temporaryShulkerOutstanding = false;
        temporaryShulkerPickupConfirmed = false;
        temporaryShulkerPickupFingerprint = null;
        temporaryShulkerPickupStorageKey = null;
        temporaryShulkerPickupStack = null;
        packedShulkerInventorySyncRecoveries = 0;
        stopAfterShulkerRecovery = false;
        shulkerRecoveryTrigger = null;
        shulkerRecoveryBreakAttempts = 0;
        shulkerInventoryCountBeforePlacement = 0;
        compatibleShulkerCountBeforePlacement = 0;
        selectedShulkerHotbarRawSlot = -1;
        selectedShulkerRoutingKey = null;
        shulkerPlacePos = null;
        resetShulkerPlacementAttempt();
        shulkerPlaceRetries = 0;
        shulkerPlaceTargetCursor = 0;
        shulkerBreakFuture = null;
        shulkerBreakAttemptGate.clear();
        resetShulkerPickupSweep();
    }

    private void clearMixedDecompositionState() {
        mixedStagingLedger.reset();
        mixedDecompositionMode = false;
        mixedBoxDrained = false;
        mixedStagingCargoKey = null;
        mixedStagingCargoItemId = null;
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
                if (isCompatiblePartialBulkShulker(classification) && usablePackingShulker(stack)) return i;
                if (classification.kind() == ShulkerClassification.Kind.EMPTY && emptySlot < 0
                        && usablePackingShulker(stack)) emptySlot = i;
            }
        }
        return emptySlot;
    }

    private static boolean isEmptyPackingShell(ItemStack stack) {
        return stack != null && stack.getAmount() > 0 && isShulkerBoxItem(itemIdFromStack(stack))
                && ShulkerClassification.classify(ItemIdentifier.readShulkerContents(stack)).kind()
                        == ShulkerClassification.Kind.EMPTY;
    }

    private int countEmptyPackingShells(String itemId) {
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (isEmptyPackingShell(stack) && (itemId == null || itemId.equals(itemIdFromStack(stack)))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Accept one uniquely matching recovered shell even when another same-ID box exists. */
    private boolean recoveredEmptyShellAvailable(MoveTask mixedTask) {
        reconcileRecoveredEmptyShellInventoryCache(mixedTask);
        if (mixedTask != null && countEmptyPackingShells(mixedTask.itemId()) > 0) {
            return true;
        }
        if (mixedTask == null || !temporaryShulkerPickupConfirmed
                || !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)) {
            return false;
        }
        ShulkerClassification checkpointOwned = claimCheckpointOwnedMixedShulker();
        if (checkpointOwned != null
                && checkpointOwned.kind() == ShulkerClassification.Kind.EMPTY
                && countEmptyPackingShells(mixedTask.itemId()) > 0) {
            return true;
        }
        return claimOneIdenticalRecoveredEmptyShell(mixedTask);
    }

    /** Empty shells are fungible; release exactly one transaction-owned instance. */
    private boolean claimOneIdenticalRecoveredEmptyShell(MoveTask mixedTask) {
        if (mixedTask == null || !mixedTask.alreadyInInventory()
                || !mixedTask.mixedDecomposition()
                || !isEmptyShulkerFingerprint(mixedTask.shulkerContentFilter())
                || !temporaryShulkerPickupConfirmed
                || !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)
                || countShulkerBoxesInInventory()
                    < Math.max(1, shulkerInventoryCountBeforePlacement)) {
            return false;
        }
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) return false;

        int claimedSlot = -1;
        int matchingEmptyShells = 0;
        String claimedItemId = null;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack == null || stack.getAmount() != 1
                    || !isEmptyPackingShell(stack)) continue;
            matchingEmptyShells++;
            if (claimedSlot < 0 && protectedInventorySlots.contains(slot)) {
                claimedSlot = slot;
                claimedItemId = itemIdFromStack(stack);
            }
        }
        if (claimedSlot < 0 || claimedItemId == null) return false;

        protectedInventorySlots.remove(claimedSlot);
        currentTask = currentTask.withItemId(claimedItemId);
        emit("organize_mixed_shell_ownership_restored", Map.of(
                "reason", "authoritative_pickup_claimed_from_fungible_empty_shells",
                "slot", claimedSlot,
                "matching_empty_shells", matchingEmptyShells,
                "previous_item_id", mixedTask.itemId(),
                "claimed_item_id", claimedItemId,
                "released_shells", 1
        ));
        return true;
    }

    /** Repair a stale player-inventory slot from the authoritative collected item entity. */
    private boolean reconcileRecoveredEmptyShellInventoryCache(MoveTask mixedTask) {
        if (mixedTask == null || !mixedTask.mixedDecomposition() || !mixedBoxDrained
                || temporaryShulkerOutstanding || !temporaryShulkerPickupConfirmed
                || !isEmptyShulkerFingerprint(temporaryShulkerPickupFingerprint)
                || temporaryShulkerPickupStack != null
                    && !mixedTask.itemId().equals(itemIdFromStack(temporaryShulkerPickupStack))
                || countShulkerBoxesInInventory() < Math.max(1, shulkerInventoryCountBeforePlacement)) {
            return false;
        }
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) return false;

        int candidateSlot = -1;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack candidate = inventory.getItemStack(slot);
            if (candidate == null || candidate.getAmount() != 1
                    || !mixedTask.itemId().equals(itemIdFromStack(candidate))) continue;
            if (candidateSlot >= 0) return false;
            candidateSlot = slot;
        }
        if (candidateSlot < 0) return false;

        ItemStack cached = inventory.getItemStack(candidateSlot);
        boolean staleShape = !isEmptyPackingShell(cached);
        boolean staleProtection = isProtectedInventorySlot(candidateSlot);
        if (!staleShape && !staleProtection) return true;

        if (staleShape) {
            // The entity ItemStack is the only evidence that can replace a non-empty server
            // snapshot. A restored fingerprint alone can belong to a different nearby pickup;
            // synthesizing an empty box here can silently reuse an unfinished mixed shulker.
            if (temporaryShulkerPickupStack == null) return false;
            ItemStack authoritativeEmpty = temporaryShulkerPickupStack;
            inventory.setItemStack(candidateSlot, authoritativeEmpty);
            Container open = getLiveOpenContainer();
            if (open != null && open != inventory) {
                int windowSlot = rawPlayerSlotToWindowSlot(
                        getOpenContainerSlotCount(open), candidateSlot);
                if (windowSlot >= 0 && windowSlot < open.getSize()) {
                    open.setItemStack(windowSlot, authoritativeEmpty);
                }
            }
        }
        protectedInventorySlots.remove(candidateSlot);
        emit("organize_mixed_shell_inventory_reconciled", Map.of(
                "reason", "authoritative_pickup_repaired_stale_inventory_cache",
                "slot", candidateSlot,
                "stale_shape", staleShape,
                "stale_protection", staleProtection,
                "restored_evidence", temporaryShulkerPickupStack == null
        ));
        return true;
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
            if (matchesShulkerTaskFilter(currentTask, classification)) {
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

    private static int observedStackSlots(Map<String, Integer> observed, String itemId,
                                          int quantity, int physicalLimit) {
        Integer slots = observed == null ? null : observed.get(itemId);
        // Older scans have no physical stack summary. One slot per unit is deliberately
        // conservative until the chest is rescanned; a shulker itself has only 27 slots.
        return Math.min(physicalLimit, Math.max(1, slots == null ? quantity : slots));
    }

    private boolean usablePackingShulker(ItemStack box) {
        var capacity = ContainerCapacitySnapshot.fromShulker(box);
        if (capacity.isEmpty() || rejectedPackingShulkers.contains(capacity.get())) return false;
        List<ItemStack> cargo = new ArrayList<>();
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            ItemStack stack = getCurrentPlayerInventoryStack(rawSlot);
            if (stack != null && stack.getAmount() > 0) cargo.add(stack);
        }
        return packingShulkerCanAccept(box, packItemId, cargo);
    }

    static boolean packingShulkerCanAccept(ItemStack box, String storageClass, List<ItemStack> cargo) {
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        if (classification.kind() != ShulkerClassification.Kind.EMPTY
                && (classification.kind() != ShulkerClassification.Kind.BULK
                    || !Objects.equals(storageClass, classification.storageKey()))) return false;
        var capacity = ContainerCapacitySnapshot.fromShulker(box);
        return capacity.isPresent() && cargo.stream().anyMatch(stack ->
                stack != null && Objects.equals(storageClass, itemIdFromStack(stack))
                        && capacity.get().accepts(stack));
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
        // The compatible-box count is already transaction-specific: a partial compatible box
        // returns to its old count, while an empty box becomes the first compatible box. The
        // aggregate shulker count can lag behind the collection packet and must not veto this.
        return countCompatibleBulkShulkersInInventory(packItemId) >= expectedCompatible;
    }

    /** Accept only an empty or exact bulk box proven by the active fill transaction. */
    private boolean hasPackedShulkerForHandoff() {
        if (hasPackedShulkerInInventory()) return true;
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return inventory != null && packedShulkerTransactionSlot(
                inventory,
                -1,
                countCurrentTaskCargoUnitsInInventory(),
                isShulkerAtPosition(reconciliationWorksite)) >= 0;
    }

    private Map<String, Object> packedShulkerInventoryAudit() {
        List<String> candidates = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = getCurrentPlayerInventoryStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            ShulkerClassification shape = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(stack));
            int units = shape.contents().values().stream().mapToInt(Integer::intValue).sum();
            candidates.add(slot + ":" + itemIdFromStack(stack)
                    + ":" + shape.kind().name().toLowerCase(Locale.ROOT)
                    + ":" + Objects.toString(shape.storageKey(), "none")
                    + ":" + units
                    + ":" + shape.contents()
                    + (isProtectedInventorySlot(slot) ? ":protected" : ":movable"));
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("expected_storage_class", Objects.toString(packItemId, "none"));
        audit.put("candidate_count", candidates.size());
        audit.put("candidates", candidates.toString());
        audit.put("loose_task_units", countCurrentTaskCargoUnitsInInventory());
        return audit;
    }

    /** Console-triggered evidence for repairing a preserved legacy packing checkpoint. */
    public void emitPackedShulkerInventoryAudit() {
        emit("organize_packed_shulker_inventory_audit", packedShulkerInventoryAudit());
    }

    /** The collection packet can arrive one inventory-sync tick before the cached slot. */
    private boolean pickupEvidenceMatchesPackedShulker() {
        return pickupEvidenceMatchesPackedShulker(
                temporaryShulkerPickupConfirmed,
                packItemId,
                temporaryShulkerPickupStorageKey)
                || packedShulkerPickupTransactionProven(
                        temporaryShulkerPickupConfirmed,
                        mixedDecompositionMode,
                        currentTask != null && currentTask.mixedBatchConsolidation(),
                        shulkerFillMovedUnits,
                        taskCargo.acquired(),
                        taskCargo.deposited(),
                        countCurrentTaskCargoUnitsInInventory(),
                        packDestination != null,
                        isShulkerAtPosition(reconciliationWorksite))
                || packedShulkerTransactionProven(
                        countTransactionEligibleShulkersInInventory(),
                        countCurrentTaskCargoUnitsInInventory(),
                        isShulkerAtPosition(reconciliationWorksite));
    }

    static boolean pickupEvidenceMatchesPackedShulker(
            boolean collectionConfirmed,
            String expectedStorageKey,
            String collectedStorageKey) {
        return collectionConfirmed
                && expectedStorageKey != null
                && collectedStorageKey != null
                && ItemIdentifier.contentItemIdsMatch(
                        expectedStorageKey, collectedStorageKey);
    }

    private boolean packedShulkerTransactionProven(
            int candidateShulkers, int looseCargoUnits, boolean worksiteBlockPresent) {
        return packedShulkerTransactionProven(
                mixedDecompositionMode,
                currentTask != null && currentTask.mixedBatchConsolidation(),
                shulkerFillMovedUnits,
                taskCargo.acquired(),
                taskCargo.deposited(),
                looseCargoUnits,
                candidateShulkers,
                packItemId != null && packDestination != null,
                worksiteBlockPresent);
    }

    private boolean hasUnresolvedPackedShulkerTransaction() {
        return packedShulkerTransactionLedgerProven(
                mixedDecompositionMode,
                currentTask != null && currentTask.mixedBatchConsolidation(),
                shulkerFillMovedUnits,
                taskCargo.acquired(),
                taskCargo.deposited(),
                countCurrentTaskCargoUnitsInInventory(),
                packDestination != null,
                isShulkerAtPosition(reconciliationWorksite));
    }

    static boolean packedShulkerTransactionProven(
            boolean mixedDecomposition,
            boolean packingTask,
            int fillMovedUnits,
            int cargoAcquired,
            int cargoDeposited,
            int looseCargoUnits,
            int candidateShulkers,
            boolean destinationPresent,
            boolean worksiteBlockPresent) {
        return candidateShulkers == 1 && packedShulkerTransactionLedgerProven(
                mixedDecomposition, packingTask, fillMovedUnits, cargoAcquired,
                cargoDeposited, looseCargoUnits, destinationPresent, worksiteBlockPresent);
    }

    static boolean packedShulkerPickupTransactionProven(
            boolean collectionConfirmed,
            boolean mixedDecomposition,
            boolean packingTask,
            int fillMovedUnits,
            int cargoAcquired,
            int cargoDeposited,
            int looseCargoUnits,
            boolean destinationPresent,
            boolean worksiteBlockPresent) {
        return collectionConfirmed && packedShulkerTransactionLedgerProven(
                mixedDecomposition, packingTask, fillMovedUnits, cargoAcquired,
                cargoDeposited, looseCargoUnits, destinationPresent, worksiteBlockPresent);
    }

    private static boolean packedShulkerTransactionLedgerProven(
            boolean mixedDecomposition,
            boolean packingTask,
            int fillMovedUnits,
            int cargoAcquired,
            int cargoDeposited,
            int looseCargoUnits,
            boolean destinationPresent,
            boolean worksiteBlockPresent) {
        return !mixedDecomposition && packingTask
                && fillMovedUnits > 0 && cargoAcquired >= fillMovedUnits
                && cargoDeposited == 0 && looseCargoUnits == 0
                && destinationPresent && !worksiteBlockPresent;
    }

    private int countMovableShulkersInInventory() {
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return inventory == null ? 0 : countMovableShulkers(inventory, -1);
    }

    private int countMovableShulkersInOpenPlayerInventory(Container open, int chestSlots) {
        return countMovableShulkers(open, chestSlots);
    }

    private int countTransactionEligibleShulkersInInventory() {
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return inventory == null ? 0 : countTransactionEligibleShulkers(inventory, -1);
    }

    /** A fill transaction may repair a stale empty/bulk view, never an actual mixed box. */
    private int countTransactionEligibleShulkers(Container inventory, int chestSlots) {
        int count = 0;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int slot = chestSlots < 0 ? rawSlot : rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = inventory.getItemStack(slot);
            if (isTransactionEligibleShulker(stack)) count += stack.getAmount();
        }
        return count;
    }

    private boolean isTransactionEligibleShulker(ItemStack stack) {
        if (stack == null || stack.getAmount() != 1
                || !isShulkerBoxItem(itemIdFromStack(stack))) return false;
        ShulkerClassification classification = ShulkerClassification.classify(
                ItemIdentifier.readShulkerContents(stack));
        return classification.kind() == ShulkerClassification.Kind.EMPTY
                || isCompatiblePackedBulk(classification);
    }

    private boolean isCompatiblePackedBulk(ShulkerClassification classification) {
        return classification.kind() == ShulkerClassification.Kind.BULK
                && ItemIdentifier.contentItemIdsMatch(
                        packItemId, classification.storageKey());
    }

    private int countMovableShulkers(Container inventory, int chestSlots) {
        int count = 0;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int slot = chestSlots < 0 ? rawSlot : rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = inventory.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0
                    && isShulkerBoxItem(itemIdFromStack(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private int uniqueMovableShulkerSlot(Container open, int chestSlots) {
        int candidate = -1;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int slot = chestSlots < 0 ? rawSlot : rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getAmount() != 1
                    || !isShulkerBoxItem(itemIdFromStack(stack))) continue;
            if (candidate >= 0) return -1;
            candidate = rawSlot;
        }
        return candidate;
    }

    private int uniqueTransactionEligibleShulkerSlot(Container open, int chestSlots) {
        int candidate = -1;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            int slot = chestSlots < 0 ? rawSlot : rawPlayerSlotToWindowSlot(chestSlots, rawSlot);
            ItemStack stack = open.getItemStack(slot);
            if (!isTransactionEligibleShulker(stack)) continue;
            if (candidate >= 0) return -1;
            candidate = rawSlot;
        }
        return candidate;
    }

    /** Resolve an empty or exact bulk box produced by the active fill transaction. */
    private int packedShulkerTransactionSlot(
            Container inventory,
            int chestSlots,
            int looseCargoUnits,
            boolean worksiteBlockPresent) {
        int strictCandidate = uniqueTransactionEligibleShulkerSlot(inventory, chestSlots);
        if (strictCandidate >= 0 && packedShulkerTransactionProven(
                1, looseCargoUnits, worksiteBlockPresent)) {
            int strictSlot = chestSlots < 0
                    ? strictCandidate
                    : rawPlayerSlotToWindowSlot(chestSlots, strictCandidate);
            ShulkerClassification strictShape = ShulkerClassification.classify(
                    ItemIdentifier.readShulkerContents(inventory.getItemStack(strictSlot)));
            // An exact bulk box identifies itself. An empty box is only transaction-specific
            // when no second movable shulker can be mistaken for the collected worksite shell.
            if (isCompatiblePackedBulk(strictShape)
                    || uniqueMovableShulkerSlot(inventory, chestSlots) == strictCandidate) {
                return strictCandidate;
            }
        }

        return -1;
    }

    private void pathToShulkerDrop() {
        if (shulkerPlacePos == null) return;
        boolean pathActive = BARITONE.getCustomGoalProcess().isActive();
        if (!ShulkerPickupSweep.shouldIssuePath(
                shulkerTicks, shulkerPickupLastPathTick, pathActive)) return;

        for (int checked = 0; checked < ShulkerPickupSweep.targetCount(); checked++) {
            int[] target = ShulkerPickupSweep.target(shulkerPlacePos, shulkerPickupSweepAttempt++);
            if (!isSafeShulkerPickupTarget(target)) continue;
            shulkerPickupLastPathTick = shulkerTicks;
            shulkerPickupLastTarget = target;
            setBaritoneBreakingAllowed(false);
            ownCustomGoal(BARITONE.pathTo(new GoalBlock(new BlockPos(
                    target[0], target[1], target[2]))));
            return;
        }
        shulkerPickupLastPathTick = shulkerTicks;
    }

    private boolean isSafeShulkerPickupTarget(int[] target) {
        if (target == null || !World.isInWorldBounds(target[0], target[1], target[2])) return false;
        return BlockCompat.canReplace(World.getBlock(target[0], target[1], target[2]))
                && BlockCompat.canReplace(World.getBlock(target[0], target[1] + 1, target[2]))
                && BlockCompat.isSolid(target[0], target[1] - 1, target[2]);
    }

    private TemporaryShulkerRecoveryStatus.Assessment temporaryShulkerRecoveryStatus() {
        return TemporaryShulkerRecoveryStatus.assess(
                isShulkerAtPosition(shulkerPlacePos),
                shulkerInventoryCountBeforePlacement,
                countShulkerBoxesInInventory(),
                temporaryShulkerPickupConfirmed,
                hasRecoveredMixedShulkerInInventory()
                        || (!mixedDecompositionMode && hasPackedShulkerInInventory()));
    }

    /** Repair old checkpoints by recognizing the expected empty box, not any shulker count. */
    private boolean hasRecoveredMixedShulkerInInventory() {
        if (!mixedDecompositionMode || !mixedBoxDrained || currentTask == null) return false;
        String expectedItemId = currentTask.itemId();
        if (!isShulkerBoxItem(expectedItemId)) return false;

        Container playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return false;
        for (int slot = 9; slot <= 44; slot++) {
            if (isProtectedInventorySlot(slot)) continue;
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0
                    || !expectedItemId.equals(itemIdFromStack(stack))) {
                continue;
            }
            if (ItemIdentifier.readShulkerContents(stack).isEmpty()) return true;
        }
        return false;
    }

    private void resetShulkerPickupSweep() {
        shulkerPickupSweepAttempt = 0;
        shulkerPickupLastPathTick = -1;
        shulkerPickupLastTarget = null;
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

    private int countItemInOpenPlayerInventory(
            Container open, int containerSlots, String itemId) {
        if (open == null || itemId == null) return 0;
        int count = 0;
        for (int rawSlot = 9; rawSlot <= 44; rawSlot++) {
            if (isProtectedInventorySlot(rawSlot)) continue;
            ItemStack stack = open.getItemStack(rawPlayerSlotToWindowSlot(containerSlots, rawSlot));
            if (stack != null && stack.getAmount() > 0
                    && itemId.equals(itemIdFromStack(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    static boolean packedShulkerTransferConfirmed(
            int matchingBefore, int matchingAfter, int submittedTransfers) {
        return submittedTransfers > 0 && matchingBefore > matchingAfter;
    }

    static boolean shouldAwaitPackedShulkerInventorySync(
            int submittedTransfers, int elapsedTicks, int timeoutTicks) {
        return submittedTransfers == 0 && elapsedTicks < timeoutTicks;
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
        List<PackingSourceSelector.Candidate> candidates = packingSupplyCandidates(packItemId);
        boolean onboardBox = findPackingShulkerInInventory() >= 0;
        var selected = candidates.stream()
                .filter(c -> !onboardBox || c.rank() == 0)
                .filter(packingSupplySearch::canTry).findFirst().orElse(null);
        if (selected != null) {
            packingSupplySearch.record(selected);
            beginShulkerFetchWalk(selected.container());
            if (journalJobId != null && !persistDurableCheckpoint(state)) {
                abortWithCargo("packing_supply_checkpoint_unavailable",
                        "The supply-search checkpoint could not be saved; cargo remains in inventory.");
                return;
            }
            emit("organize_shulker_source_selected", Map.of(
                    "candidate_kind", selected.kind(),
                    "remaining_candidates", candidates.size() - 1,
                    "sources_tried", packingSupplySearch.tried().size()));
            return;
        }
        if (onboardBox) {
            resumePackingSelection();
            return;
        }
        if (packingCargoAbsenceConfirmed
                && retireMissingInventoryPackingCargo(
                        "packing_supply_live_window", true)) {
            return;
        }
        stagingForPackingSupply = true;
        supplyStagingLedger.reset();
        emit("organize_packing_supply_deferred", Map.of(
                "reason", candidates.isEmpty() ? "no_known_packing_stock" : "packing_search_budget_reached",
                "sources_tried", packingSupplySearch.tried().size(),
                "max_candidates", PackingSupplySearch.MAX_CANDIDATES,
                "max_speculative_candidates", PackingSupplySearch.MAX_SPECULATIVE_CANDIDATES,
                "disposition", "stage_cargo_until_box_supply_changes"));
        startOverflow();
        persistDurableCheckpoint(state);
    }

    private List<PackingSourceSelector.Candidate> packingSupplyCandidates(String storageClass) {
        return packingSourceSelector.candidates(
                index.getAll().stream().filter(this::isManagedSourceContainer).toList(), storageClass,
                packingSupplySearch.tried(), CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(),
                CACHE.getPlayerCache().getZ(), System.currentTimeMillis());
    }

    private void rememberPackingSourceMiss(Container open) {
        ContainerEntry entry = openIndexedContainer;
        if (entry != null) {
            packingSourceSelector.recordMiss(ContainerReader.snapshotContents(
                    open, entry, System.currentTimeMillis()), packItemId, System.currentTimeMillis());
        }
        emit("organize_shulker_source_miss", Map.of(
                "reason", "no_usable_packing_box", "sources_tried", packingSupplySearch.tried().size()));
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
            ItemStack selected = getPlayerInventoryStack(slot);
            if (selected == null || selected.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(selected))) return false;
            var builder = InventoryActionRequest.builder().owner(this).priority(6000);
            if (slot >= 36 && slot <= 44) {
                selectedShulkerHotbarRawSlot = slot;
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                int hotbarIndex = preferredUnprotectedHotbarIndex();
                if (hotbarIndex < 0) return false;
                selectedShulkerHotbarRawSlot = 36 + hotbarIndex;
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.from(hotbarIndex)),
                    new SetHeldItem(hotbarIndex)
                );
            }
            selectedShulkerRoutingKey = cargoRoutingKey(selected);
            RequestFuture future = INVENTORY.submit(builder.build());
            ownInventory(future);
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            selectedShulkerHotbarRawSlot = -1;
            selectedShulkerRoutingKey = null;
            info("Failed to move shulker to hotbar: " + e.getMessage());
            return false;
        }
    }

    private boolean selectedShulkerReadyForPlacement() {
        if (selectedShulkerHotbarRawSlot < 36 || selectedShulkerHotbarRawSlot > 44
                || selectedShulkerRoutingKey == null) return false;
        int heldHotbarIndex = CACHE.getPlayerCache().getHeldItemSlot();
        ItemStack held = getPlayerInventoryStack(selectedShulkerHotbarRawSlot);
        return hotbarSelectionMatches(
                selectedShulkerHotbarRawSlot,
                selectedShulkerRoutingKey,
                heldHotbarIndex,
                36 + heldHotbarIndex,
                held == null ? null : cargoRoutingKey(held));
    }

    /** Submit one vanilla right click without allowing Baritone to reselect by base item id. */
    private ShulkerPlacementSubmission placeSelectedShulkerExact() {
        if (!selectedShulkerReadyForPlacement() || shulkerPlacePos == null
                || packShulkerItemData == null) {
            return new ShulkerPlacementSubmission(
                    InputRequestFuture.rejected, null, "none", -1, 0);
        }

        // rotationToPlaceTarget reads this shared setting when calculating eye height. Keep
        // it aligned with the standing pose used by the exact input request.
        setPlaceBlockSneak(false);
        var target = new InteractWithProcess.PlaceBlock(
                shulkerPlacePos[0], shulkerPlacePos[1], shulkerPlacePos[2], packShulkerItemData);
        var placeTargets = target.findPlaceTargets();
        int targetCount = placeTargets.size();
        int start = targetCount == 0 ? 0 : Math.floorMod(shulkerPlaceTargetCursor, targetCount);
        for (int offset = 0; offset < targetCount; offset++) {
            int ordinal = (start + offset) % targetCount;
            var placeTarget = placeTargets.get(ordinal);
            var rotation = target.rotationToPlaceTarget(placeTarget);
            if (rotation == null) continue;
            var support = placeTarget.supportingBlockState();
            int[] supportPos = {support.x(), support.y(), support.z()};
            Input input = Input.builder()
                    .hand(Hand.MAIN_HAND)
                    .clickRequiresRotation(true)
                    .clickTarget(new ClickTarget.BlockPosition(
                            support.x(), support.y(), support.z()))
                    .sneaking(false)
                    .rightClick(true)
                    .build();
            InputRequestFuture future = INPUTS.submit(InputRequest.builder()
                    .owner(this)
                    .input(input)
                    .yaw(rotation.yaw())
                    .pitch(rotation.pitch())
                    .priority(6001)
                    .build());
            shulkerPlaceTargetCursor = (ordinal + 1) % targetCount;
            return new ShulkerPlacementSubmission(
                    future,
                    supportPos,
                    placeTarget.direction().toString().toLowerCase(Locale.ROOT),
                    ordinal,
                    targetCount);
        }
        return new ShulkerPlacementSubmission(
                InputRequestFuture.rejected, null, "none", -1, targetCount);
    }

    static boolean hotbarSelectionMatches(
            int expectedRawSlot,
            String expectedRoutingKey,
            int heldHotbarIndex,
            int observedRawSlot,
            String observedRoutingKey) {
        return heldHotbarIndex >= 0 && heldHotbarIndex < HOTBAR_SIZE
                && expectedRawSlot == observedRawSlot
                && expectedRoutingKey != null
                && expectedRoutingKey.equals(observedRoutingKey);
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
