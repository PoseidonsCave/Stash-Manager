package com.zenith.plugin.stashmanager;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientLoginFailedEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.client.ClientStartConnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.event.module.AutoReconnectEvent;
import com.zenith.event.module.AutoEatOutOfFoodEvent;
import com.zenith.event.module.HealthAutoDisconnectEvent;
import com.zenith.event.player.PlayerLoginEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.PathInput;
import com.zenith.feature.pathfinder.PlayerContext;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.pathfinder.movement.IMovement;
import com.zenith.feature.pathfinder.movement.movements.MovementDiagonal;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.food.FoodRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.kit.KitFulfillmentJournalStore;
import com.zenith.plugin.stashmanager.kit.KitInventoryReconciliationBoundary;
import com.zenith.plugin.stashmanager.kit.KitInventoryAppleOwnership;
import com.zenith.plugin.stashmanager.kit.KitFulfillmentPlan;
import com.zenith.plugin.stashmanager.kit.KitCapacity;
import com.zenith.plugin.stashmanager.kit.KitExportAudit;
import com.zenith.plugin.stashmanager.kit.KitEmptyShellOwnershipAudit;
import com.zenith.plugin.stashmanager.kit.KitCraftingPlan;
import com.zenith.plugin.stashmanager.kit.KitLostSourcePolicy;
import com.zenith.plugin.stashmanager.kit.KitLostSourceIndexPolicy;
import com.zenith.plugin.stashmanager.kit.KitNoCargoKeepAudit;
import com.zenith.plugin.stashmanager.kit.KitCargoAudit;
import com.zenith.plugin.stashmanager.kit.KitResumeCadence;
import com.zenith.plugin.stashmanager.kit.KitResumePolicy;
import com.zenith.plugin.stashmanager.kit.KitRetainedShell;
import com.zenith.plugin.stashmanager.kit.KitScanAdmission;
import com.zenith.plugin.stashmanager.kit.KitSourceGuiAudit;
import com.zenith.plugin.stashmanager.kit.KitAppleExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitSourceDiagnostics;
import com.zenith.plugin.stashmanager.kit.KitSourceReturnPolicy;
import com.zenith.plugin.stashmanager.kit.KitVariantExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitVariantFingerprint;
import com.zenith.plugin.stashmanager.kit.KitVariantShellRebaseline;
import com.zenith.plugin.stashmanager.kit.KitWorksiteRecovery;
import java.io.IOException;
import java.nio.file.Path;
import com.zenith.plugin.stashmanager.debug.DebugRecorder;
import com.zenith.plugin.stashmanager.debug.PoseTraceRecorder;
import com.zenith.plugin.stashmanager.debug.WorldGeometrySnapshot;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.orchestration.CooperativePreemptionGate;
import com.zenith.plugin.stashmanager.orchestration.ConnectionRecoveryTracker;
import com.zenith.plugin.stashmanager.orchestration.FoodContingencyCloseGate;
import com.zenith.plugin.stashmanager.orchestration.FoodContingencyPolicy;
import com.zenith.plugin.stashmanager.orchestration.FoodContingencyRetryPolicy;
import com.zenith.plugin.stashmanager.orchestration.KeepReequipSelection;
import com.zenith.plugin.stashmanager.orchestration.JobContinuanceManager;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.MultiRegionImportRoutePolicy;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.ContainerAccessPlanner;
import com.zenith.plugin.stashmanager.orchestration.ContainerOpenReach;
import com.zenith.plugin.stashmanager.orchestration.AlternateApproachProgress;
import com.zenith.plugin.stashmanager.orchestration.OpenRetryCadence;
import com.zenith.plugin.stashmanager.orchestration.ScanChestOpenFallback;
import com.zenith.plugin.stashmanager.orchestration.ScanPathRetryCadence;
import com.zenith.plugin.stashmanager.orchestration.ScanCompletionPolicy;
import com.zenith.plugin.stashmanager.orchestration.ScanQueueCursor;
import com.zenith.plugin.stashmanager.orchestration.RegionCapacitySelector;
import com.zenith.plugin.stashmanager.orchestration.ScanNavigationPolicy;
import com.zenith.plugin.stashmanager.orchestration.ScanWalkProgress;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.orchestration.TailRetryTracker;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.plugin.stashmanager.organizer.KitExporter;
import com.zenith.plugin.stashmanager.organizer.KitFulfillmentCoordinator;
import com.zenith.plugin.stashmanager.organizer.KitInventoryAdmissionPolicy;
import com.zenith.plugin.stashmanager.organizer.KitInventoryDispositionPolicy;
import com.zenith.plugin.stashmanager.organizer.KitPacker;
import com.zenith.plugin.stashmanager.retriever.StashRetriever;
import com.zenith.plugin.stashmanager.scanner.ContainerReader;
import com.zenith.plugin.stashmanager.scanner.RegionScanner;
import com.zenith.plugin.stashmanager.scanner.RegionScanner.ContainerLocation;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.travel.tunnel.network.sync.SyncWorker;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

// Tick-driven container scanning state machine. YIELDED temporarily releases Zenith's shared
// automation resources and later resumes from a safe checkpoint.
public class StashManagerModule extends Module {

    public enum ScanState {
        IDLE,
        ZONE_SCANNING,
        WALKING,
        APPROACHING,
        OPENING,
        READING,
        CLOSING,
        WALKING_TO_ZONE,
        RETURNING,
        YIELDED,
        DONE
    }

    private enum OwnedBaritoneProcess {
        NONE,
        CUSTOM_GOAL,
        INTERACTION
    }

    private enum ScanResumeMode {
        RETRY_CURRENT,
        ADVANCE_CURRENT,
        RESCAN_ZONE,
        RETURN_TO_START
    }

    private final StashManagerConfig config;
    private final ContainerIndex index;
    private final RegionScanner regionScanner;
    private ContainerReader containerReader;
    private final StashManagerNotifications notifications;
    private int foodSafetyProbeTicks;
    private static final String KEEP_REEQUIP_REQUEST = "reequip:keep";
    private static final String KEEP_REEQUIP_FOOD_REQUEST = "reequip:food";
    private boolean reequipActive;
    private boolean reequipRetrievalDone;
    private boolean reequipFoodPhase;
    private boolean reequipPartial;
    private Map<String, Integer> reequipRemainingRequests = Map.of();
    private String reequipRequestName = KEEP_REEQUIP_REQUEST;
    private String lastReequipError;
    private String reequipRetrievalResult = "none";
    private Set<String> reequipEquipment = Set.of();
    private RequestFuture reequipSwap;
    private int reequipSwapTarget = -1;
    private String reequipSwapItem;
    private int reequipVerifyTicks;
    private int reequipWaitTicks;
    private int reequipEquipped;

    // Organizer integration
    private StashOrganizer organizer;
    private final StashRetriever retriever;
    private final KitFulfillmentJournalStore kitJournal = KitFulfillmentJournalStore.defaultStore();
    private final KitPacker kitPacker = new KitPacker();
    private final KitExporter kitExporter = new KitExporter();
    private final KitFulfillmentCoordinator kitFulfillment;
    private final KitWorksiteRecovery kitRecovery = new KitWorksiteRecovery(kitJournal);
    private final KitResumeCadence kitResumeCadence = new KitResumeCadence(40);
    private long lastKitResumeWaitDiagnosticEpochMilli;

    private volatile ScanState state = ScanState.IDLE;
    private List<ContainerLocation> pendingContainers = new ArrayList<>();
    private int currentContainerIndex = 0;
    private int tickCounter = 0;
    private int openTimeoutCounter = 0;
    private int openInteractionAttempts = 0;
    private int lastOpenInteractionTick = -1;
    private @Nullable BlockPos scanOpenClickTarget;
    private @Nullable BlockPos scanAlternateGoal;
    private final AlternateApproachProgress scanAlternateProgress = new AlternateApproachProgress();
    private final Map<Long, Set<Long>> rejectedScanStances = new HashMap<>();
    private final Map<Long, Integer> scanAlternateAttempts = new HashMap<>();
    private static final int MAX_SCAN_ALTERNATE_STANCES = 5;
    private boolean containerDataReceived = false;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    // Cooperative ownership of Zenith's global Baritone and InventoryManager. Futures let us
    // distinguish scanner work from a request submitted by PearlPlus or another plugin.
    private CooperativePreemptionGate scannerPreemptionGate;
    private CooperativePreemptionGate organizerPreemptionGate;
    private final JobContinuanceManager jobContinuanceManager = new JobContinuanceManager();
    private final ConnectionRecoveryTracker connectionRecoveryTracker =
            new ConnectionRecoveryTracker();
    private @Nullable PathingRequestFuture ownedBaritoneRequest;
    private OwnedBaritoneProcess ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
    private @Nullable RequestFuture ownedInventoryRequest;
    private ScanResumeMode scanResumeMode = ScanResumeMode.RETRY_CURRENT;
    private int lateOpenQuarantineTicks = 0;
    private int scanPreemptionCount = 0;
    private int organizerPreemptionCount = 0;
    private boolean organizerPickupRecoveryDeferred = false;
    private String lastOrganizerRecoveryBlocker;
    private static final String FOOD_CONTINGENCY_REQUEST = "stash_food_contingency";
    private volatile boolean foodContingencyRequested = false;
    private boolean foodContingencyActive = false;
    private boolean foodContingencyBlocked = false;
    private int foodContingencyVerificationTicks = 0;
    private int foodContingencyBlockedRecheckTicks = 0;
    private int foodContingencyBlockedElapsedTicks = 0;
    private int foodContingencyBlockedRetryCount = 0;
    private int foodContingencyStartRetryTicks = 0;
    private boolean foodContingencyCleanupPending = false;
    private boolean foodContingencyRetrievalStarted = false;
    private boolean autoEatWarningMuted = false;
    private boolean autoEatWarningBeforeMute = false;
    private final FoodContingencyCloseGate foodContingencyCloseGate =
            new FoodContingencyCloseGate();
    private volatile String foodContingencyTrigger = "none";
    private String foodContingencyTerminalReason = "none";
    private JobContinuanceManager.Job foodContingencyJob = JobContinuanceManager.Job.NONE;
    private volatile @Nullable String controllingPlayerName;
    private static final int SCAN_PREEMPTION_QUIET_TICKS = 40;
    private static final int LATE_OPEN_QUARANTINE_TICKS = 100;
    private static final int FOOD_CONTINGENCY_START_RETRY_TICKS = 40;

    // Starting position — used for return-to-start
    private double startX, startY, startZ;
    private boolean hasStartPosition = false;
    private boolean finishScanAfterReturn = false;
    private boolean resumeScanAfterReturn = false;
    private ScanResumeMode returnResumeMode = ScanResumeMode.RETRY_CURRENT;
    private @Nullable String abortScanAfterReturnReason;
    private int returnPathAttempts = 0;
    private static final double SCAN_HORIZONTAL_RECOVERY_MARGIN = 32.0;
    private static final double SCAN_VERTICAL_RECOVERY_MARGIN = 6.0;
    private static final int ZONE_WAYPOINT_RANGE_SQ = 16;

    // A scanner target owns no inventory cargo, so a transient failure can safely move the
    // untouched target to the tail. Counts stay tied to physical containers, not attempts.
    private final Set<Long> discoveredContainerKeys = new HashSet<>();
    private final Set<Long> settledContainerKeys = new HashSet<>();
    private static final int MAX_CONTAINER_ATTEMPTS = 3;
    private final TailRetryTracker<Long> containerRetries =
        new TailRetryTracker<>(MAX_CONTAINER_ATTEMPTS);
    private static final int MIN_CONTAINER_OPEN_TIMEOUT_TICKS = 400;
    private static final int CONTAINER_OPEN_RETRY_INTERVAL_TICKS = 20;
    private static final int CONTAINER_INTERACTION_ATTEMPT_TIMEOUT_TICKS = 60;

    // Baritone config — saved/restored around scans
    private boolean savedAllowBreak = true;
    private boolean baritoneConfigSaved = false;

    // Walking retry counter
    private int walkRetryCount = 0;
    private static final int MAX_WALK_RETRIES = 3;
    private int walkingTickCount = 0;
    private int lastContainerPathRequestTick = 0;
    private final ScanWalkProgress scanWalkProgress = new ScanWalkProgress();
    private static final int PATH_RETRY_SETTLE_TICKS = 20;
    private static final int DOOR_OPEN_TIMEOUT_TICKS = 60;
    private final Set<BlockPos> attemptedScanDoors = new HashSet<>();
    private @Nullable BlockPos scanDoorOpeningPos;
    private boolean scanDoorInteractionDispatched = false;
    private int scanDoorOpeningTicks = 0;

    // Statistics
    private int containersFound = 0;
    private int containersIndexed = 0;
    private int containersFailed = 0;

    // Database integration
    private DatabaseManager database;
    private long currentScanId = -1;
    private boolean latestScanTrusted = false;
    private @Nullable int[] activeScanPos1;
    private @Nullable int[] activeScanPos2;
    private @Nullable String activeScanRegionName;
    private final List<DatabaseManager.SavedRegion> pendingScanRegions = new ArrayList<>();
    private int scanRegionHandoffWaitTicks;
    private boolean scanTransitActive;
    private double scanTransitX, scanTransitY, scanTransitZ;
    private static final double NAMED_SCAN_START_HORIZONTAL_MARGIN = 128.0;
    private static final double NAMED_SCAN_START_VERTICAL_MARGIN = 48.0;

    // Tunnel network sync
    private final SyncWorker tunnelNetworkSyncWorker;

    // Exportable troubleshooting log (see /stash debug)
    private final DebugRecorder debugRecorder = new DebugRecorder();
    private final DebugRecorder geometryRecorder = new DebugRecorder(128, 16000);
    private final PoseTraceRecorder poseTraceRecorder = new PoseTraceRecorder();
    private int poseTraceTicks;
    private static final int POSE_TRACE_SAMPLE_TICKS = 10;

    public StashManagerModule(StashManagerConfig config, ContainerIndex index) {
        this.config = config;
        this.notifications = new StashManagerNotifications(config);
        this.index = index;
        this.scannerPreemptionGate = newScannerPreemptionGate();
        this.organizerPreemptionGate = newOrganizerPreemptionGate();
        this.regionScanner = new RegionScanner();
        this.containerReader = new ContainerReader(index);
        this.retriever = new StashRetriever();
        this.retriever.setEventCallback(this::handleAutomationEvent);
        this.kitFulfillment = new KitFulfillmentCoordinator(
                retriever, kitPacker, kitExporter, kitJournal);
        this.kitFulfillment.setEventCallback(this::handleAutomationEvent);
        this.kitPacker.setEventCallback(this::handleAutomationEvent);
        this.kitExporter.setEventCallback(this::handleAutomationEvent);
        this.kitRecovery.setEventCallback(this::handleAutomationEvent);
        this.tunnelNetworkSyncWorker = new SyncWorker(config);
    }

    public void setDatabase(DatabaseManager database) {
        this.database = database;
        this.tunnelNetworkSyncWorker.setDatabase(database);
    }

    public void setOrganizer(StashOrganizer organizer) {
        this.organizer = organizer;
        if (this.organizer != null) {
            this.organizer.setEventCallback(this::handleAutomationEvent);
        }
    }

    public StashOrganizer getOrganizer() {
        return organizer;
    }

    public LaneCapacityReport getLaneCapacityReport() {
        if (organizer == null) {
            return LaneCapacityReport.unavailable(LaneCapacityReport.Status.NO_SCANNED_CONTAINERS);
        }
        var selection = selectLaneCapacityRegion();
        return switch (selection.result()) {
            case SELECTED -> organizer.calculateLaneCapacity(selection.pos1(), selection.pos2());
            case NO_REGION -> LaneCapacityReport.unavailable(LaneCapacityReport.Status.REGION_NOT_DEFINED);
            case NO_STORAGE_REGION -> LaneCapacityReport.unavailable(LaneCapacityReport.Status.NO_STORAGE_REGION);
            case AMBIGUOUS -> LaneCapacityReport.unavailable(LaneCapacityReport.Status.MULTIPLE_STORAGE_REGIONS);
        };
    }

    public @Nullable String getLaneCapacityRegionName() {
        return selectLaneCapacityRegion().name();
    }

    public boolean hasLaneCapacityImportStaging() {
        return getLaneCapacityImportChestBlockCount() > 0;
    }

    public int getLaneCapacityImportChestBlockCount() {
        var selection = selectLaneCapacityRegion();
        if (!selection.selected()) return 0;
        if (organizer != null && organizer.isActive()
                && Arrays.equals(selection.pos1(), config.pos1)
                && Arrays.equals(selection.pos2(), config.pos2)) {
            return organizer.scopedImportChestBlockCount();
        }
        return (int) index.getImportChests().stream().filter(position ->
                inBounds(position, selection.pos1(), selection.pos2())).count();
    }

    private static boolean inBounds(int[] position, int[] pos1, int[] pos2) {
        for (int axis = 0; axis < 3; axis++) {
            if (position[axis] < Math.min(pos1[axis], pos2[axis])
                    || position[axis] > Math.max(pos1[axis], pos2[axis])) return false;
        }
        return true;
    }

    private RegionCapacitySelector.Selection selectLaneCapacityRegion() {
        if (database != null && database.isInitialized()) {
            try {
                return RegionCapacitySelector.select(database.listRegions(), config.pos1, config.pos2);
            } catch (Exception e) {
                warn("Cannot select storage region for lane capacity: {}", e.getMessage());
                return new RegionCapacitySelector.Selection(
                        RegionCapacitySelector.Result.NO_STORAGE_REGION, null, null, null);
            }
        }
        return RegionCapacitySelector.select(List.of(), config.pos1, config.pos2);
    }

    public StashRetriever getRetriever() {
        return retriever;
    }

    public KitFulfillmentCoordinator getKitFulfillment() { return kitFulfillment; }
    public KitWorksiteRecovery getKitRecovery() { return kitRecovery; }

    /** Read-only live evidence for a blocked kit; indexed stock is advisory, not proof of contents. */
    public String diagnoseKitSources() {
        final KitFulfillmentJournalStore.Snapshot snapshot;
        try {
            snapshot = kitJournal.load().orElse(null);
        } catch (IOException e) {
            return "Kit checkpoint could not be read: " + e.getMessage();
        }
        if (snapshot == null) return "No kit checkpoint to diagnose.";
        String item = snapshot.currentItem();
        if (item == null || item.isBlank()) {
            return "Kit checkpoint has no current retrieval item (phase " + snapshot.state() + ").";
        }

        boolean connected = Proxy.getInstance().isConnected();
        var player = CACHE.getPlayerCache();
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        StringBuilder out = new StringBuilder(2200);
        out.append("Checkpoint: ").append(snapshot.kitName()).append(' ')
                .append(snapshot.completedBoxes()).append('/').append(snapshot.requestedBoxes())
                .append(" delivered; state=").append(snapshot.state())
                .append("; blocker=").append(snapshot.blocker())
                .append("\nNext item: ").append(item)
                .append(" x").append(snapshot.currentQuantity());
        out.append("\nBot: ").append(connected ? "connected" : "disconnected")
                .append(Proxy.getInstance().hasActivePlayer() ? ", client attached" : ", no client")
                .append(connected ? ", position=" + String.format(java.util.Locale.ROOT,
                        "%.2f,%.2f,%.2f", px, py, pz) : ", position not trusted");

        var inventory = player.getInventoryCache();
        int shellSlot = snapshot.ownedShellSlot();
        Container playerInventory = connected ? inventory.getPlayerInventory() : null;
        ItemStack shell = playerInventory == null || shellSlot < 9 || shellSlot > 44
                ? null : playerInventory.getItemStack(shellSlot);
        out.append("\nKit shell: journal slot ").append(shellSlot);
        if (!connected) out.append("; live identity unavailable while disconnected");
        else if (shell == null || shell.getAmount() != 1) out.append("; no single live item in slot");
        else out.append("; live item=").append(ItemIdentifier.baseItemId(
                        ItemIdentifier.getItemId(shell)))
                .append("; contents match journal=")
                .append(snapshot.packedContents().equals(ItemIdentifier.readShulkerContents(shell)));
        out.append("\nBorrowed sources pending return=").append(snapshot.sourceBoxesToReturn().size())
                .append("; written off=").append(snapshot.writtenOffSources().size());

        List<ContainerEntry> entries = index.getAll().stream()
                .filter(entry -> config.pos1 == null || config.pos2 == null
                        || inConfiguredRegion(entry))
                .toList();
        List<KitSourceDiagnostics.Source> sources = KitSourceDiagnostics.find(
                entries, item, px, py, pz, 6);
        out.append("\nExact indexed sources (advisory, up to 6): ")
                .append(sources.size());
        for (var source : sources) {
            ContainerEntry entry = source.entry();
            out.append("\n- ").append(entry.posString()).append(' ')
                    .append(entry.readableBlockType()).append(": total=")
                    .append(source.total()).append(" direct=").append(source.direct())
                    .append(" nested=").append(source.nested())
                    .append(" in ").append(source.matchingShulkers()).append(" physical shulker(s)")
                    .append("; ").append(kitTargetAccessSummary(
                            entry.x(), entry.y(), entry.z(), connected, px, py, pz));
            if (index.isBlacklistedChest(entry) || index.isExportChest(entry)) {
                out.append("; reserved/blacklisted");
            }
        }
        out.append("\nNo container was opened and no item, path, or checkpoint was changed.");
        return out.toString();
    }

    /** Probe one block's cached geometry without pathing or interacting with it. */
    public String diagnoseKitTarget(int x, int y, int z) {
        if (!Proxy.getInstance().isConnected()) return "Bot is disconnected; live geometry is unavailable.";
        if (!World.isInWorldBounds(x, y, z)) return "Target is outside world bounds.";
        var player = CACHE.getPlayerCache();
        try {
            String detail = WorldGeometrySnapshot.capture(x, y, z, null,
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch());
            geometryRecorder.record("kit_manual_probe", detail);
            return detail.length() <= 3200 ? detail
                    : detail.substring(0, 3200) + "… Full snapshot: /stash debug geometry export.";
        } catch (RuntimeException e) {
            return "Live geometry probe failed: " + e.getClass().getSimpleName();
        }
    }

    private static String kitTargetAccessSummary(int x, int y, int z, boolean connected,
                                                 double px, double py, double pz) {
        if (!connected) return "live block unavailable";
        if (!World.isChunkLoadedBlockPos(x, z)
                || World.getChunkSection(x, y, z) == null) return "chunk/section not loaded";
        try {
            var block = World.getBlock(x, y, z);
            return "live=" + (block == null ? "unknown" : block.name())
                    + " distance=" + String.format(java.util.Locale.ROOT, "%.1f",
                            Math.sqrt(Math.pow(px - (x + 0.5), 2)
                                    + Math.pow(py - (y + 0.5), 2)
                                    + Math.pow(pz - (z + 0.5), 2)))
                    + " access=" + ContainerApproach.isAtAccessPosition(px, py, pz, x, y, z)
                    + " reach=" + ContainerApproach.isWithinVanillaReach(px, py, pz, x, y, z)
                    + " visible=" + ContainerAccessPlanner.visible(px, py, pz, x, y, z);
        } catch (RuntimeException e) {
            return "live geometry unavailable (" + e.getClass().getSimpleName() + ')';
        }
    }

    /** Inspect the currently open GUI only; never clicks, moves, or journals an item. */
    public String inspectOpenKitSource() {
        final KitFulfillmentJournalStore.Snapshot snapshot;
        try {
            snapshot = kitJournal.load().orElse(null);
        } catch (IOException e) {
            return "Checkpoint could not be read: " + e.getMessage();
        }
        if (snapshot == null || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().size() != 1) {
            return "No checkpoint with exactly one journaled borrowed source box.";
        }
        var inventory = CACHE.getPlayerCache().getInventoryCache();
        int openId = inventory.getOpenContainerId();
        Container open = inventory.getOpenContainer();
        if (openId <= 0 || open == null || open.getContainerId() != openId) {
            return "No live container GUI is open. Open the candidate chest and retry.";
        }
        int chestSlots = open.getSize() - 36;
        if (chestSlots != 27 && chestSlots != 54) {
            return "The open GUI has " + chestSlots + " container slots, not a chest.";
        }
        var source = snapshot.sourceBoxesToReturn().getFirst();
        List<KitSourceGuiAudit.Box> boxes = new ArrayList<>();
        int readable = 0;
        for (int slot = 0; slot < chestSlots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            if (!ItemIdentifier.baseItemId(itemId).contains("shulker_box")) continue;
            Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
            if (!contents.isEmpty()) readable++;
            boxes.add(new KitSourceGuiAudit.Box(slot, itemId, stack.getAmount(), contents));
        }
        List<Integer> matches = KitSourceGuiAudit.exactMatches(
                boxes, source.itemId(), source.contents());
        List<KitSourceGuiAudit.Candidate> nearest = matches.isEmpty()
                ? KitSourceGuiAudit.nearestCandidates(boxes, source.itemId(), source.contents(), 5)
                : List.of();
        String nearestReport = nearest.isEmpty() ? ""
                : " Closest contents (not verified identities): " + nearest.stream()
                .map(candidate -> "slot " + candidate.slot() + " [shared " + candidate.shared()
                        + ", missing " + candidate.missing() + ", extra " + candidate.extra()
                        + ", same box item " + candidate.sameBoxItem() + "]")
                .collect(java.util.stream.Collectors.joining("; ")) + ".";
        String crystalReport = "";
        Container player = inventory.getPlayerInventory();
        int shellRaw = snapshot.ownedShellSlot();
        ItemStack shell = player == null || shellRaw < 9 || shellRaw > 44
                ? null : player.getItemStack(shellRaw);
        if (shell != null && shell.getAmount() == 1
                && snapshot.packedContents().equals(ItemIdentifier.readShulkerContents(shell))) {
            List<ItemStack> crystals = new ArrayList<>();
            int crystalTotal = 0;
            for (ItemStack stack : ItemIdentifier.readShulkerPhysicalStacks(shell)) {
                if ("end_crystal".equals(ItemIdentifier.getItemId(stack))) {
                    crystals.add(stack);
                    crystalTotal += stack.getAmount();
                }
            }
            if (crystals.size() == 5 && crystalTotal == 256) {
                ItemStack exemplar = crystals.getFirst();
                int targetInShell = 0;
                for (ItemStack stack : crystals) {
                    if (stack.getId() == exemplar.getId()
                            && java.util.Objects.equals(stack.getDataComponents(),
                                    exemplar.getDataComponents())) {
                        targetInShell += stack.getAmount();
                    }
                }
                if (targetInShell == 113) {
                    List<String> candidates = new ArrayList<>();
                    int matchingTotal = 0;
                    int matchingSlots = 0;
                    for (int slot = 0; slot < chestSlots; slot++) {
                        ItemStack stack = open.getItemStack(slot);
                        if (stack == null || stack.getAmount() <= 0) continue;
                        List<ItemStack> physical = "end_crystal".equals(ItemIdentifier.getItemId(stack))
                                ? List.of(stack) : ItemIdentifier.readShulkerPhysicalStacks(stack);
                        int matching = 0;
                        for (ItemStack inner : physical) {
                            if (inner.getId() == exemplar.getId()
                                    && java.util.Objects.equals(inner.getDataComponents(),
                                            exemplar.getDataComponents())) {
                                matching += inner.getAmount();
                            }
                        }
                        if (matching > 0) {
                            matchingTotal += matching;
                            matchingSlots++;
                            if (candidates.size() < 15) candidates.add("slot " + slot + " x" + matching);
                        }
                    }
                    crystalReport = " Live component-exact matches for the kit shell's 113-crystal "
                            + "variant: " + matchingTotal + " in " + matchingSlots
                            + " slot(s), first " + candidates.size() + ": "
                            + candidates + ". These are read-only GUI observations, not reserved stock.";
                } else {
                    crystalReport = " Kit crystal variant totals changed; no source comparison made.";
                }
            } else {
                crystalReport = " Kit crystal stack count changed; no source comparison made.";
            }
        } else {
            crystalReport = " Owned kit shell did not match the checkpoint; no source comparison made.";
        }
        return "Read-only inspection of " + chestSlots + " GUI slots: " + boxes.size()
                + " shulker stacks, " + readable + " with readable contents. "
                + (matches.isEmpty() ? "No exact journaled source match."
                : "Exact journaled source match at zero-based chest slot(s) " + matches + ".")
                + nearestReport
                + crystalReport
                + " No items or checkpoint state were changed.";
    }

    /** Read-only comparison of a live shulker window with the journaled kit worksite. */
    public String inspectOpenKitWorksite() {
        final KitFulfillmentJournalStore.Snapshot snapshot;
        try {
            snapshot = kitJournal.load().orElse(null);
        } catch (IOException e) {
            return "Checkpoint could not be read: " + e.getMessage();
        }
        if (snapshot == null || snapshot.kitWorksite() == null) {
            return "No journaled kit worksite exists.";
        }
        int[] position = snapshot.kitWorksite();
        if (!World.isChunkLoadedBlockPos(position[0], position[2])
                || !World.getBlock(position[0], position[1], position[2])
                        .name().contains("shulker_box")) {
            return "The journaled worksite is not a loaded placed shulker.";
        }
        var inventory = CACHE.getPlayerCache().getInventoryCache();
        int openId = inventory.getOpenContainerId();
        Container open = inventory.getOpenContainer();
        if (openId <= 0 || open == null || open.getContainerId() != openId
                || open.getSize() - 36 != 27) {
            return "Open the placed kit shulker before inspecting its live contents.";
        }
        if (!ContainerApproach.isAtAccessPosition(CACHE.getPlayerCache().getX(),
                CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ(),
                position[0], position[1], position[2])) {
            return "Bot is not at the journaled worksite; the open GUI cannot be associated with it.";
        }
        Map<String, Integer> observed = new LinkedHashMap<>();
        int occupied = 0;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            occupied++;
            observed.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
        }
        Map<String, Integer> expected = snapshot.packedContents();
        Map<String, Integer> missing = new LinkedHashMap<>();
        Map<String, Integer> extra = new LinkedHashMap<>();
        java.util.Set<String> ids = new java.util.TreeSet<>(expected.keySet());
        ids.addAll(observed.keySet());
        for (String id : ids) {
            int difference = observed.getOrDefault(id, 0) - expected.getOrDefault(id, 0);
            if (difference < 0) missing.put(id, -difference);
            if (difference > 0) extra.put(id, difference);
        }
        return "Journaled worksite=" + position[0] + "," + position[1] + "," + position[2]
                + "; open window=" + openId + " (association inferred from pose and placed block)"
                + "; occupied slots=" + occupied + "/27; exact item-ID totals match="
                + (missing.isEmpty() && extra.isEmpty())
                + "; missing=" + missing + "; extra=" + extra
                + "; observed=" + observed + ". No inventory or checkpoint action was taken.";
    }

    /** Explicit operator write-off after the misplaced borrowed box cannot be found. */
    public @Nullable String acknowledgeLostKitSource(int declaredUnits) {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be online, alive, and free of client control";
        }
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            return "another movement or inventory job is active";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || cursor != null && cursor.getAmount() > 0) {
            return "close the container and clear the cursor first";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || snapshot.kitWorksite() == null) {
                return "no journaled kit worksite to reconcile";
            }
            int[] site = snapshot.kitWorksite();
            boolean loaded = World.isChunkLoadedBlockPos(site[0], site[2])
                    && World.getChunkSection(site[0], site[1], site[2]) != null;
            boolean air = loaded && BlockCompat.isAir(World.getBlock(site[0], site[1], site[2]));
            int sourceMatches = -1;
            if (snapshot.sourceBoxesToReturn() != null
                    && snapshot.sourceBoxesToReturn().size() == 1) {
                var source = snapshot.sourceBoxesToReturn().getFirst();
                sourceMatches = 0;
                Container player = inventoryCache.getPlayerInventory();
                if (player == null) sourceMatches = -1;
                else for (int raw = 9; raw <= 44; raw++) {
                    ItemStack stack = player.getItemStack(raw);
                    if (stack != null && stack.getAmount() > 0
                            && source.itemId().equals(ItemIdentifier.getItemId(stack))
                            && source.contents().equals(ItemIdentifier.readShulkerContents(stack))) {
                        sourceMatches += stack.getAmount();
                    }
                }
            }
            String unsafe = KitLostSourcePolicy.blocker(snapshot, declaredUnits,
                    loaded, air, KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot),
                    KitFulfillmentCoordinator.uniqueCurrentLooseCargoSlot(snapshot),
                    sourceMatches, kitRecovery.blocker());
            if (unsafe != null || !kitRecovery.canAcknowledgeAbsentKitBox()) {
                return unsafe == null ? "worksite recovery state changed" : unsafe;
            }
            var source = snapshot.sourceBoxesToReturn().getFirst();
            var writtenOff = new ArrayList<>(snapshot.writtenOffSources());
            writtenOff.add(source);
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null, null,
                    Map.of(), "lost_borrowed_source_operator_attested",
                    System.currentTimeMillis(), null, 0, List.of(),
                    snapshot.auditedCargoSlot(), writtenOff, snapshot.auditedCargoSlots(),
                    snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots()));
            kitRecovery.acknowledgeAbsentKitBox();
            kitResumeCadence.reset();
            debugRecorder.record("kit_borrowed_source_written_off",
                    "kit=" + snapshot.kitName() + ", source=" + Arrays.toString(source.sourcePosition())
                            + ", item=" + source.itemId() + ", units=" + declaredUnits
                            + ", completed_boxes=" + snapshot.completedBoxes());
            return null;
        } catch (Exception e) {
            return "lost-source reconciliation failed: " + e.getMessage();
        }
    }

    /** Pin one operator-identified, otherwise unowned empty source shell at the
     * already-packed boundary. This never admits other shulkers or moves inventory. */
    public @Nullable String auditRetainedKitShell(int rawSlot) {
        if (rawSlot < 9 || rawSlot > 44) return "inventory slot is outside the player inventory";
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)) {
            return "another stash movement is active";
        }
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
            return "bot must be online in-world with no client controller attached";
        }
        if (config.pos1 == null || config.pos2 == null
                || database == null || !database.isInitialized()) {
            return "stash region or database is unavailable";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        var cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || (cursor != null && cursor.getAmount() > 0)
                || INVENTORY.hasActiveRequest()) {
            return "inventory transaction is open; close the container and clear the cursor";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (!KitResumePolicy.canAuditUnjournaledBorrowedSource(snapshot)
                    || snapshot.retainedShell() != null || snapshot.auditedCargoSlot() >= 0
                    || !snapshot.auditedCargoSlots().isEmpty()) {
                return "checkpoint is not the exact packed-box recycled-shell boundary";
            }
            var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
            if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                    scan.found(), scan.indexed(), scan.failed())) {
                return "fresh trusted scan is required before kit recovery";
            }
            int owned = KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot);
            if (owned < 0 || !KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                    snapshot.displacedAppleSlots())) {
                return "owned kit shell or protected apples no longer match the checkpoint";
            }
            Set<Integer> admitted = new HashSet<>(snapshot.displacedAppleSlots().keySet());
            admitted.add(owned);
            var admission = KitInventoryAdmissionPolicy.inspectLive(
                    database.loadKeepItems(), admitted);
            if (admission == null || admission.unexpectedSlots().size() != 1
                    || admission.unexpectedSlots().getFirst() != rawSlot) {
                return "selected shell is not the only unexpected inventory item";
            }
            Container player = inventoryCache.getPlayerInventory();
            var shell = player == null ? null : player.getItemStack(rawSlot);
            var reserved = KitRetainedShell.evidence(rawSlot, shell);
            if (reserved == null) return "selected slot is not one verified empty shulker";
            Map<Integer, ItemStack> live = new HashMap<>();
            for (int raw = 9; raw <= 44; raw++) live.put(raw, player.getItemStack(raw));
            if (KitRetainedShell.uniqueSlot(reserved, live, owned) != rawSlot) {
                return "empty shulker identity is ambiguous in live inventory";
            }
            long now = System.currentTimeMillis();
            long due = now + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    owned, snapshot.exportPosition(), null, null, null, Map.of(),
                    "resume_verified_recycled_shell", now, "NEED_CARGO", due,
                    List.of(), -1, snapshot.writtenOffSources(), Map.of(),
                    snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing(), snapshot.displacedAppleSlots(), reserved));
            kitResumeCadence.reset();
            debugRecorder.record("kit_recycled_shell_audited", "kit=" + snapshot.kitName()
                    + ", raw_slot=" + rawSlot + ", cooldown_seconds="
                    + Math.max(1, config.scanPreemptionCooldownSeconds));
            return null;
        } catch (Exception e) {
            return "could not audit recycled shell: " + e.getMessage();
        }
    }

    /** Attest one absent borrowed source without claiming that loose cargo was lost. */
    public @Nullable String acknowledgeLostBorrowedKitSource(int declaredLastKnownUnits) {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be online, alive, and free of client control";
        }
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            return "another movement or inventory job is active";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || cursor != null && cursor.getAmount() > 0) {
            return "close the container and clear the cursor first";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || snapshot.borrowedSourceWorksite() == null
                    || snapshot.borrowedSourceItemId() == null) {
                return "no journaled borrowed source worksite to reconcile";
            }
            int[] site = snapshot.borrowedSourceWorksite();
            boolean loaded = World.isChunkLoadedBlockPos(site[0], site[2])
                    && World.getChunkSection(site[0], site[1], site[2]) != null;
            boolean air = loaded && BlockCompat.isAir(World.getBlock(site[0], site[1], site[2]));
            int sourceMatches = -1;
            Container player = inventoryCache.getPlayerInventory();
            if (player != null) {
                sourceMatches = 0;
                for (int raw = 9; raw <= 45; raw++) {
                    ItemStack stack = player.getItemStack(raw);
                    if (stack != null && stack.getAmount() > 0
                            && snapshot.borrowedSourceItemId().equals(
                                    ItemIdentifier.getItemId(stack))
                            && snapshot.borrowedSourceContents().equals(
                                    ItemIdentifier.readShulkerContents(stack))) {
                        sourceMatches += stack.getAmount();
                    }
                }
            }
            ContainerEntry origin = KitLostSourcePolicy.borrowedOrigin(
                    index.getAll().stream().filter(this::inConfiguredRegion).toList(),
                    snapshot.borrowedSourceItemId(), snapshot.borrowedSourceContents());
            if (origin != null && (index.isExportChest(origin)
                    || !World.isChunkLoadedBlockPos(origin.x(), origin.z())
                    || World.getChunkSection(origin.x(), origin.y(), origin.z()) == null
                    || !World.getBlock(origin.x(), origin.y(), origin.z()).name()
                            .contains("chest"))) {
                origin = null;
            }
            String unsafe = KitLostSourcePolicy.borrowedBlocker(snapshot,
                    declaredLastKnownUnits, loaded, air,
                    kitFulfillment.hasVerifiedOwnedShell(snapshot), sourceMatches,
                    kitRecovery.blocker(), origin);
            if (unsafe != null || !kitRecovery.canAcknowledgeAbsentBorrowedSource()) {
                return unsafe == null ? "worksite recovery state changed" : unsafe;
            }
            int[] sourcePosition = {origin.x(), origin.y(), origin.z()};
            var writtenOff = new ArrayList<>(snapshot.writtenOffSources());
            writtenOff.add(new KitFulfillmentJournalStore.BorrowedBox(sourcePosition,
                    snapshot.borrowedSourceItemId(), snapshot.borrowedSourceContents()));
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null, null,
                    Map.of(), "lost_borrowed_source_operator_attested_needs_cargo_audit",
                    System.currentTimeMillis(), null, 0, List.of(), -1, writtenOff,
                    Map.of(), snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots()));
            kitRecovery.acknowledgeAbsentBorrowedSource();
            kitResumeCadence.reset();
            debugRecorder.record("kit_borrowed_source_written_off",
                    "kit=" + snapshot.kitName() + ", indexed_source="
                            + Arrays.toString(sourcePosition) + ", item="
                            + snapshot.borrowedSourceItemId() + ", last_known_units="
                            + declaredLastKnownUnits + ", completed_boxes="
                            + snapshot.completedBoxes() + ", loose_cargo_not_audited=true");
            return null;
        } catch (Exception e) {
            return "borrowed-source loss reconciliation failed: " + e.getMessage();
        }
    }

    /** Operator-attested no-cargo boundary after incomplete retrieval or a lost
     * borrowed source. Every same-item stack is explicitly kept supply, not newly
     * owned kit cargo. This never transfers items or clears source-loss records. */
    public @Nullable String scheduleKitLostBorrowedNoCargoAudit(int keptSlot, int keptCount) {
        return scheduleKitLostBorrowedNoCargoAudit(Map.of(keptSlot, keptCount));
    }

    public @Nullable String scheduleKitLostBorrowedNoCargoBatchAudit(String slots) {
        if (slots == null || slots.isBlank()) return "use slot:count pairs, for example 12:8 16:64";
        Map<Integer, Integer> declared = new LinkedHashMap<>();
        try {
            for (String token : slots.trim().split("\\s+")) {
                String[] pair = token.split(":", -1);
                if (pair.length != 2) return "use slot:count pairs, for example 12:8 16:64";
                int raw = Integer.parseInt(pair[0]);
                int count = Integer.parseInt(pair[1]);
                if (declared.putIfAbsent(raw, count) != null) return "duplicate inventory slot: " + raw;
            }
        } catch (NumberFormatException e) {
            return "use numeric slot:count pairs, for example 12:8 16:64";
        }
        return scheduleKitLostBorrowedNoCargoAudit(declared);
    }

    private @Nullable String scheduleKitLostBorrowedNoCargoAudit(Map<Integer, Integer> declaredKept) {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be online, alive, and free of client control";
        }
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            return "another movement or inventory job is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            boolean lostBorrowedSource = KitResumePolicy.canAuditLostBorrowedNoCargo(snapshot);
            if (!lostBorrowedSource
                    && !KitResumePolicy.canAuditKeptNoCargoRetrieval(snapshot)) {
                return "checkpoint is not at an exact kept-stock no-cargo boundary";
            }
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0)) {
                return "close the container and clear the cursor before auditing";
            }
            if (!kitFulfillment.hasVerifiedOwnedShell(snapshot)) {
                return "packed kit shulker no longer matches the checkpoint";
            }
            Container player = inventoryCache.getPlayerInventory();
            if (player == null) return "player inventory unavailable";
            Map<Integer, KitCargoAudit.Stack> observed = new HashMap<>();
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack != null && stack.getAmount() > 0) observed.put(raw,
                        new KitCargoAudit.Stack(ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
            Map<String, Integer> keepRules = database.loadKeepItems();
            var admission = KitInventoryAdmissionPolicy.inspectLive(
                    keepRules, Set.of(snapshot.ownedShellSlot()));
            if (admission == null) return "inventory keep-policy check unavailable";
            if (!admission.accepted()) {
                return "unexpected inventory slots " + admission.unexpectedSlots()
                        + "; " + kitUnexpectedInventoryDisposition(admission);
            }
            if (!KitNoCargoKeepAudit.matches(observed, snapshot.currentItem(),
                    snapshot.ownedShellSlot(), declaredKept)) {
                return "declared kept slots do not exactly match every live same-item stack";
            }
            long due = System.currentTimeMillis()
                    + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null, null,
                    Map.of(), lostBorrowedSource
                            ? "resume_retrieve_after_attested_no_cargo"
                            : "resume_retrieve_after_kept_stock_audit",
                    System.currentTimeMillis(), "NEED_CARGO", due, List.of(), -1,
                    snapshot.writtenOffSources(), Map.of(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots()));
            kitResumeCadence.reset();
            debugRecorder.record(lostBorrowedSource
                            ? "kit_lost_borrowed_no_cargo_audited"
                            : "kit_kept_stock_no_cargo_audited",
                    "kit=" + snapshot.kitName() + ", kept_slots=" + declaredKept
                            + ", source_writeoffs="
                            + snapshot.writtenOffSources().size()
                            + ", next=NEED_CARGO");
            return null;
        } catch (Exception e) {
            return "could not audit no-cargo boundary: " + e.getMessage();
        }
    }

    /** Pin only an exactly witnessed apple exchange; no inventory item is moved by this audit. */
    public @Nullable String scheduleKitAppleOverflowAudit(int keptSlot, int keptCount,
                                                           String displacedDeclaration) {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive())
            return "bot must be online, alive, and free of client control";
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive())
            return "another movement or inventory job is active";
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (!KitResumePolicy.canAuditAppleOverflow(snapshot))
                return "checkpoint is not at the exact packed-apple overflow boundary";
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0))
                return "close the container and clear the cursor before auditing";
            if (!KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot))
                return "packed kit shulker no longer matches the checkpoint";
            Container player = inventoryCache.getPlayerInventory();
            if (player == null || keptSlot < 9 || keptSlot > 44 || keptSlot == snapshot.ownedShellSlot())
                return "kept apple slot is invalid";
            ItemStack kept = player.getItemStack(keptSlot);
            var keptEvidence = KitInventoryAppleOwnership.evidence(kept);
            if (keptEvidence == null || keptEvidence.amount() != keptCount)
                return "declared kept apple stack does not match the live slot";
            Map<Integer, KitInventoryAppleOwnership.Slot> displaced = new LinkedHashMap<>();
            for (String part : displacedDeclaration.split(",")) {
                String[] pair = part.trim().split(":", -1);
                if (pair.length != 2) return "expected displaced slots as raw:count,raw:count";
                int raw = Integer.parseInt(pair[0].trim());
                int count = Integer.parseInt(pair[1].trim());
                if (raw < 9 || raw > 44 || raw == keptSlot
                        || raw == snapshot.ownedShellSlot() || displaced.containsKey(raw))
                    return "displaced apple slot is invalid or repeated";
                var evidence = KitInventoryAppleOwnership.evidence(player.getItemStack(raw));
                if (evidence == null || !evidence.customPresentation() || evidence.amount() != count)
                    return "declared displaced apple stack does not match live slot " + raw;
                displaced.put(raw, evidence);
            }
            if (displaced.isEmpty()) return "no displaced apple slots declared";
            Set<Integer> declared = new HashSet<>(displaced.keySet());
            declared.add(keptSlot);
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack != null && stack.getAmount() > 0
                        && "enchanted_golden_apple".equals(ItemIdentifier.getItemId(stack))
                        && !declared.contains(raw))
                    return "undeclared apple stack at raw slot " + raw;
            }
            Set<Integer> admitted = new HashSet<>(displaced.keySet());
            admitted.add(snapshot.ownedShellSlot());
            var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(), admitted);
            if (admission == null || !admission.accepted())
                return "inventory admission still rejects "
                        + (admission == null ? "unavailable inventory" : admission.unexpectedSlots());
            long due = System.currentTimeMillis()
                    + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null, null,
                    Map.of(), "resume_verified_apple_overflow", System.currentTimeMillis(),
                    "NEED_CARGO", due, List.of(), -1, snapshot.writtenOffSources(),
                    Map.of(), snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing(), displaced));
            kitResumeCadence.reset();
            debugRecorder.record("kit_apple_overflow_audited", "kit=" + snapshot.kitName()
                    + ", kept=" + keptSlot + ":" + keptCount
                    + ", displaced=" + displaced.keySet() + ", next=NEED_CARGO");
            return null;
        } catch (Exception e) {
            return "apple overflow audit failed: " + e.getMessage();
        }
    }

    /** Reconcile non-kept player cargo before a new kit, or while a reviewed kit journal
     * retains its owned shell. The organizer uses its own durable inventory-only
     * transaction and never plans scanned stash source moves. */
    public @Nullable String startKitInventoryReconciliation() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be online, alive, and free of client control";
        }
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || organizer == null || organizer.isActive() || organizer.hasDurableCheckpoint()
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            return "another automation or organizer checkpoint is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            var boundary = KitInventoryReconciliationBoundary.select(snapshot);
            if (boundary == null) {
                return "kit checkpoint is not at the attested borrowed-source no-cargo review boundary";
            }
            boolean reviewedKitBoundary = boundary.hasOwnedShell();
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0)) {
                return "close the container and clear the cursor before reconciliation";
            }
            if (reviewedKitBoundary && !kitFulfillment.hasVerifiedOwnedShell(snapshot)) {
                return "owned kit shell no longer matches the saved checkpoint";
            }
            if (!organizer.hasScopedImportStaging()) {
                return "no registered, indexed import chest is available for reconciliation";
            }
            Set<Integer> pinnedSlots = boundary.pinnedSlots();
            var admission = KitInventoryAdmissionPolicy.inspectLive(
                    database.loadKeepItems(), pinnedSlots);
            if (admission == null) return "inventory keep-policy check unavailable";
            if (admission.accepted()) return "inventory already contains only kept or kit-owned items";
            if (admission.unexpectedSlots().stream().anyMatch(raw -> raw < 9 || raw > 44)) {
                return "crafting-grid or non-transferable cargo requires manual review";
            }
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionCount = 0;
            organizerPickupRecoveryDeferred = false;
            lastOrganizerRecoveryBlocker = null;
            if (!organizer.startInventoryReconciliation(pinnedSlots)) {
                return "scoped inventory reconciliation could not start; see organizer diagnostics";
            }
            requestFoodContingency("organize_started");
            debugRecorder.record("kit_inventory_reconciliation_started",
                    "kit=" + (reviewedKitBoundary ? snapshot.kitName() : "none")
                            + ", owned_shell=" + (reviewedKitBoundary
                            ? snapshot.ownedShellSlot() : "none")
                            + ", unexpected_slots=" + admission.unexpectedSlots());
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_inventory_reconciliation_start_error",
                    "reason=" + e.getMessage(), e);
            return "inventory reconciliation preflight failed: " + e.getMessage();
        }
    }

    /** Stage the abandoned, partially packed shell intact before retiring its checkpoint.
     * This uses the organizer's durable inventory-only transaction; it never plans moves
     * from the old stash scan and leaves configured keep supplies in inventory. */
    public @Nullable String startKitRetirementStaging() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be online, alive, and free of client control";
        }
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || organizer == null || organizer.isActive() || organizer.hasDurableCheckpoint()
                || (state != ScanState.IDLE && state != ScanState.DONE)
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            return "another automation or organizer checkpoint is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || !(KitResumePolicy.canAuditLostBorrowedNoCargo(snapshot)
                    || ("BLOCKED".equals(snapshot.state())
                    && "kit_retirement_inventory_staging".equals(snapshot.blocker())
                    && snapshot.resumeState() == null))) {
                return "kit is not at the attested no-cargo retirement boundary";
            }
            if (snapshot.borrowedSourceWorksite() != null || snapshot.kitWorksite() != null
                    || !snapshot.sourceBoxesToReturn().isEmpty()) {
                return "a borrowed source or worksite still requires recovery";
            }
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0)) {
                return "close the container and clear the cursor before staging";
            }
            if (!kitFulfillment.hasVerifiedOwnedShell(snapshot)
                    || KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot)
                    != snapshot.ownedShellSlot()) {
                return "the exact owned kit shell is not unique at its journaled slot";
            }
            if (!organizer.hasScopedImportStaging()) {
                return "no registered, indexed import chest is available for staging";
            }
            var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(),
                    Set.of());
            if (admission == null || !admission.unexpectedSlots().contains(snapshot.ownedShellSlot())) {
                return "keep policy would protect the owned shell from inventory staging";
            }
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionCount = 0;
            organizerPickupRecoveryDeferred = false;
            lastOrganizerRecoveryBlocker = null;
            if (!organizer.startInventoryReconciliation(Set.of())) {
                return "inventory-only staging could not start; see organizer diagnostics";
            }
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), snapshot.kitWorksite(),
                    snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), "kit_retirement_inventory_staging",
                    System.currentTimeMillis(), null, 0L, snapshot.sourceBoxesToReturn(),
                    snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                    snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots())
                    .withRetainedShell(snapshot.retainedShell()));
            requestFoodContingency("organize_started");
            debugRecorder.record("kit_retirement_staging_started",
                    "kit=" + snapshot.kitName() + ", shell=" + snapshot.ownedShellSlot());
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_retirement_staging_error", "reason=" + e.getMessage(), e);
            return "retirement staging preflight failed: " + e.getMessage();
        }
    }

    /** Read-only preview: no inventory click, pathing, or checkpoint mutation. */
    public String previewKitInventoryReconciliation() {
        try {
            var snapshot = kitJournal.load().orElse(null);
            var boundary = KitInventoryReconciliationBoundary.select(snapshot);
            if (boundary == null) {
                return "The kit is not at the guarded no-cargo reconciliation boundary.";
            }
            boolean reviewedKitBoundary = boundary.hasOwnedShell();
            if (reviewedKitBoundary && !kitFulfillment.hasVerifiedOwnedShell(snapshot)) {
                return "The kit-owned shell does not match its saved slot and contents.";
            }
            Set<Integer> pinnedSlots = boundary.pinnedSlots();
            var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(),
                    pinnedSlots);
            if (admission == null) return "The live inventory is unavailable.";
            var decisions = KitInventoryDispositionPolicy.plan(admission.unexpectedItems(),
                    database.loadColumnAssignments(), database.loadImportChests());
            Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
            StringBuilder detail = new StringBuilder();
            if (reviewedKitBoundary) {
                detail.append("Kit-owned shell raw ").append(snapshot.ownedShellSlot())
                        .append(" remains pinned; ");
            } else {
                detail.append("No kit checkpoint; ");
            }
            detail.append(decisions.size()).append(" unexpected inventory slots.\n");
            for (var decision : decisions) {
                detail.append("raw ").append(decision.rawSlot()).append(' ')
                        .append(decision.itemId()).append(" -> ")
                        .append(decision.route());
                ItemStack stack = player == null ? null : player.getItemStack(decision.rawSlot());
                if (stack != null && decision.route()
                        == KitInventoryDispositionPolicy.Route.SHULKER_IDENTITY_REVIEW) {
                    detail.append(" contents=")
                            .append(ItemIdentifier.readShulkerContents(stack))
                            .append(" (bulk: saved lane if assigned, otherwise import; "
                                    + "mixed/empty: import intact)");
                }
                detail.append('\n');
            }
            detail.append("Lane-less loose stacks require same-item shulker packing before import; "
                    + "all transfers require live identity and destination verification.");
            return detail.toString();
        } catch (Exception e) {
            return "Read-only reconciliation preview failed: " + e.getMessage();
        }
    }

    public boolean stopKitFulfillment() {
        if (kitFulfillment.isActive()) {
            kitFulfillment.interruptForRecovery("manual_stop");
            return true;
        }
        return cancelKitScheduledResume();
    }

    public String kitFulfillmentStatus() {
        if (kitFulfillment.isActive()) {
            return "Building " + kitFulfillment.kitName() + ": "
                    + kitFulfillment.deliveredBoxes() + "/"
                    + kitFulfillment.requestedBoxes() + " delivered ("
                    + kitFulfillment.completedBoxes() + " complete, "
                    + kitFulfillment.incompleteBoxes() + " incomplete), phase "
                    + kitFulfillment.state().name().toLowerCase();
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null) return "No kit fulfillment job or checkpoint.";
            return "Checkpoint for " + snapshot.kitName() + ": "
                    + (snapshot.completedBoxes() + snapshot.incompleteBoxes()) + "/"
                    + snapshot.requestedBoxes() + " delivered ("
                    + snapshot.completedBoxes() + " complete, "
                    + snapshot.incompleteBoxes() + " incomplete), last phase "
                    + snapshot.state().toLowerCase()
                    + (snapshot.missingTotals().isEmpty() ? ""
                        : ", missing to fill: " + snapshot.missingTotals())
                    + (snapshot.writtenOffSources().isEmpty() ? ""
                        : ", operator-attested lost source boxes "
                            + snapshot.writtenOffSources().size())
                    + (snapshot.resumeState() == null ? ""
                        : snapshot.resumeNotBeforeEpochMilli() == 0
                            ? ", paused from " + snapshot.resumeState().toLowerCase()
                                + (KitResumePolicy.blocker(snapshot) == null
                                    ? "; use /stash kit resume after review"
                                    : "; manual cargo/worksite review required ("
                                        + KitResumePolicy.blocker(snapshot) + ")")
                            : ", resume scheduled from " + snapshot.resumeState().toLowerCase()
                                + " in " + Math.max(0L,
                                    (snapshot.resumeNotBeforeEpochMilli()
                                        - System.currentTimeMillis() + 999) / 1000)
                                + " seconds (then a quiet window)")
                    + ", recovery " + kitRecovery.state().name().toLowerCase()
                    + (kitRecovery.blocker() == null ? "" : " (" + kitRecovery.blocker() + ")")
                    + ". Inspect inventory and export chest before clearing the checkpoint.";
        } catch (Exception e) {
            return "Kit checkpoint cannot be read: " + e.getMessage();
        }
    }

    public boolean retryKitRecovery() {
        return !kitFulfillment.isActive() && !retriever.isActive()
                && (organizer == null || !organizer.isActive())
                && (state == ScanState.IDLE || state == ScanState.DONE)
                && kitRecovery.retry();
    }

    public @Nullable String scheduleKitFulfillmentResume() {
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()) {
            return "kit movement or worksite recovery is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null) return "no kit checkpoint exists";
            String resumeState = snapshot.resumeState();
            boolean retryNoCargo = KitResumePolicy.canRetryNoCargoRetrieval(snapshot)
                    && KitFulfillmentCoordinator.hasNoUnclaimedCurrentCargo(snapshot);
            boolean retryBorrowedInventory = !retryNoCargo
                    && KitResumePolicy.canRetryBorrowedInventoryCargo(snapshot);
            boolean retryAuditedMultiSource =
                    KitResumePolicy.canRetryAuditedMultiSourcePreflight(snapshot);
            boolean resumeAuditedCargo = KitResumePolicy.canResumeAuditedCargo(snapshot)
                    || KitResumePolicy.canResumeReconciledLoss(snapshot)
                    || retryAuditedMultiSource;
            boolean resumeMainHeadroom = KitResumePolicy.canRetryAfterMainHeadroom(snapshot);
            boolean resumePackedBatch = KitResumePolicy.canResumeRecoveredPackedBatch(snapshot);
            boolean resumeSourceReturn = KitResumePolicy.canRerouteSourceReturn(snapshot);
            boolean verifyCompletedReturn =
                    KitResumePolicy.canAuditCompletedSourceReturn(snapshot);
            boolean resumeAuditedExport = KitResumePolicy.canResumeAuditedExport(snapshot);
            if (verifyCompletedReturn) {
                if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
                    return "bot must be online with no client controller attached";
                }
                var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
                var cursor = inventoryCache.getMouseStack();
                if (inventoryCache.getOpenContainerId() > 0
                        || (cursor != null && cursor.getAmount() > 0)
                        || INVENTORY.hasActiveRequest()) {
                    return "close the container and clear the cursor before auditing the return";
                }
                if (!kitFulfillment.hasVerifiedOwnedShell(snapshot)
                        || KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot) >= 0) {
                    return "owned shell is unverified or the borrowed box remains in inventory";
                }
                if (config.pos1 == null || config.pos2 == null
                        || database == null || !database.isInitialized()) {
                    return "stash region or database is unavailable";
                }
                var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
                if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                        scan.found(), scan.indexed(), scan.failed())) {
                    return "fresh trusted scan required for the return chest";
                }
                var source = snapshot.sourceBoxesToReturn().getFirst();
                List<ContainerEntry> scanned = new ArrayList<>();
                for (ContainerEntry entry : database.getAllContainers()) {
                    if (inConfiguredRegion(entry)
                            && entry.timestamp() >= scan.startedAtEpochMilli()) {
                        scanned.add(entry);
                    }
                }
                if (!KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(scanned,
                        index.getImportChests(), getReservedContainerKeys(), config.pos1,
                        config.pos2, source.sourcePosition(), snapshot.exportPosition(),
                        source.returnPosition())) {
                    return "saved return chest is not a freshly scanned registered import";
                }
                resumeState = "VERIFY_RETURN";
            }
            if (resumeAuditedExport) {
                if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
                    return "bot must be online with no client controller attached";
                }
                var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
                var cursor = inventoryCache.getMouseStack();
                if (inventoryCache.getOpenContainerId() > 0
                        || (cursor != null && cursor.getAmount() > 0)) {
                    return "close the export chest and clear the cursor before resuming";
                }
                resumeState = "NEED_SHELL";
            }
            if (KitResumePolicy.canRetryUnverifiedCargo(snapshot)
                    || KitResumePolicy.canRetryAfterHotbarClear(snapshot)
                    || resumeMainHeadroom
                    || retryBorrowedInventory || retryNoCargo || resumeAuditedCargo
                    || resumePackedBatch) {
                var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
                var cursor = inventoryCache.getMouseStack();
                if (inventoryCache.getOpenContainerId() > 0
                        || (cursor != null && cursor.getAmount() > 0)) {
                    return "inventory transaction is still open; inspect the cursor and container";
                }
                if (!kitFulfillment.hasVerifiedOwnedShell(snapshot)) {
                    return "packed kit shulker no longer matches the checkpoint";
                }
                if (KitResumePolicy.canRetryAfterHotbarClear(snapshot)
                        && KitFulfillmentCoordinator.freeHotbarSlots() == 0) {
                    return "free a hotbar slot before retrying the kit";
                }
                if (retryBorrowedInventory
                        && KitFulfillmentCoordinator.uniqueBorrowedInventorySourceSlot(snapshot) < 0) {
                    return "borrowed source shulker is missing or ambiguous, or loose cargo needs review";
                }
                if (resumeAuditedCargo
                        && !KitFulfillmentCoordinator.verifiedAuditedCargo(snapshot)) {
                    return "recovered cargo is missing or ambiguous; inspect inventory before resuming";
                }
                if (retryAuditedMultiSource) {
                    if (!Proxy.getInstance().isConnected()
                            || Proxy.getInstance().hasActivePlayer()) {
                        return "bot must be online with no client controller attached";
                    }
                    if (!KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                            snapshot.displacedAppleSlots())
                            || KitFulfillmentCoordinator.verifiedPendingSourceSlots(snapshot)
                                    .size() != snapshot.sourceBoxesToReturn().size()) {
                        return "pending borrowed boxes or displaced apples are missing or ambiguous";
                    }
                }
                resumeState = resumeAuditedCargo ? "WAIT_CARGO" : "NEED_CARGO";
            }
            if (resumeSourceReturn) {
                var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
                var cursor = inventoryCache.getMouseStack();
                if (inventoryCache.getOpenContainerId() > 0
                        || (cursor != null && cursor.getAmount() > 0)
                        || INVENTORY.hasActiveRequest()) {
                    return "inventory transaction is still open; inspect the cursor and container";
                }
                if (!kitFulfillment.hasVerifiedOwnedShell(snapshot)
                        || KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot) < 0) {
                    return "packed kit shell or borrowed source shulker no longer matches the checkpoint";
                }
                resumeState = "RETURNING_SOURCE";
            }
            String unsafe = resumeAuditedCargo || resumeSourceReturn || verifyCompletedReturn
                    ? null : KitResumePolicy.blocker(snapshot, resumeState);
            if (unsafe != null) return "checkpoint needs manual review: " + unsafe;
            long due = System.currentTimeMillis()
                    + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), snapshot.kitWorksite(),
                    snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), resumeAuditedCargo
                            ? "resume_audited_inventory_cargo" : retryBorrowedInventory
                                ? "resume_borrowed_inventory_source" : retryNoCargo
                                    ? "resume_retrieve_after_no_cargo" : resumePackedBatch
                                    ? "resume_verified_packed_batch" : resumeSourceReturn
                                    ? "resume_source_return" : verifyCompletedReturn
                                    ? "resume_audit_completed_source_return" : resumeAuditedExport
                                    ? "resume_audited_export" : resumeMainHeadroom
                                    ? "resume_main_headroom" : "resume_requested",
                    System.currentTimeMillis(),
                    resumeState, due, snapshot.sourceBoxesToReturn(),
                    snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                    snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots())
                    .withRetainedShell(snapshot.retainedShell()));
            kitResumeCadence.reset();
            debugRecorder.record("kit_resume_scheduled", "kit=" + snapshot.kitName()
                    + ", phase=" + resumeState
                    + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
            return null;
        } catch (Exception e) {
            return "could not schedule kit resume: " + e.getMessage();
        }
    }

    /** Journal one exact full-shell crystal exchange; no inventory action happens here. */
    public @Nullable String scheduleKitVariantRepairToImport(int[] target) {
        if (target == null || target.length != 3) return "import target is invalid";
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)) {
            return "another stash movement is active";
        }
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be alive and online with no client controller attached";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        var cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || (cursor != null && cursor.getAmount() > 0)
                || INVENTORY.hasActiveRequest()) {
            return "close the container and clear the cursor before scheduling repair";
        }
        if (config.pos1 == null || config.pos2 == null
                || database == null || !database.isInitialized()) {
            return "stash region or database is unavailable";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                    || !"kit_packer_start_failed".equals(snapshot.blocker())
                    || snapshot.resumeState() != null || snapshot.variantRepair() != null
                    || snapshot.kitWorksite() != null
                    || snapshot.borrowedSourceWorksite() != null
                    || !"diamond_helmet".equals(
                            ItemIdentifier.baseItemId(snapshot.currentItem()))
                    || snapshot.currentQuantity() != 1
                    || snapshot.packedContents().getOrDefault("end_crystal", 0) != 256
                    || snapshot.completedBoxes() >= snapshot.requestedBoxes()) {
                return "checkpoint is not at the exact full-shell helmet boundary";
            }
            if (!KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot)
                    || !KitFulfillmentCoordinator.verifiedVariantPendingSources(snapshot)
                    || !KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                            snapshot.displacedAppleSlots())) {
                return "owned shell, borrowed source boxes, or protected apples changed";
            }
            int cargoRaw = KitFulfillmentCoordinator.uniqueCurrentLooseCargoSlot(snapshot);
            if (cargoRaw < 9 || cargoRaw > 44) return "loose helmet cargo is not unique";
            Container player = inventoryCache.getPlayerInventory();
            ItemStack shell = player == null ? null : player.getItemStack(snapshot.ownedShellSlot());
            ItemStack cargo = player == null ? null : player.getItemStack(cargoRaw);
            if (shell == null || cargo == null || cargo.getAmount() != 1) {
                return "owned shell or exact helmet cargo is missing";
            }
            var shellSlots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
            if (shellSlots.size() != 27) return "kit shell is not physically full";
            var physical = shellSlots.entrySet().stream()
                    .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                    .toList();
            var opportunity = KitVariantExchangePlan.shellOpportunity(physical,
                    "end_crystal", 256, 26);
            if (opportunity == null || opportunity.amount() != 15
                    || opportunity.slotsAfter() != 26) {
                return "no unique 15-crystal exchange frees one physical slot";
            }
            Set<Integer> owned = new HashSet<>(
                    KitFulfillmentCoordinator.variantPendingSourceSlots(snapshot));
            if (owned.size() != snapshot.sourceBoxesToReturn().size()) {
                return "borrowed source box identities are ambiguous";
            }
            owned.add(snapshot.ownedShellSlot());
            owned.add(cargoRaw);
            owned.addAll(snapshot.displacedAppleSlots().keySet());
            if (snapshot.retainedShell() != null) {
                int retainedSlot = KitFulfillmentCoordinator.uniqueRetainedShellSlot(snapshot);
                if (retainedSlot < 0) return "reserved empty kit shell is missing";
                owned.add(retainedSlot);
            }
            var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(), owned);
            if (admission == null || !admission.accepted()) {
                return "unexpected inventory slots: "
                        + (admission == null ? "unavailable" : admission.unexpectedSlots());
            }
            var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
            if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                    scan.found(), scan.indexed(), scan.failed())) {
                return "fresh trusted scan required for source and import footprints";
            }
            List<ContainerEntry> scanned = database.getAllContainers().stream()
                    .filter(this::inConfiguredRegion)
                    .filter(entry -> entry.timestamp() >= scan.startedAtEpochMilli())
                    .toList();
            if (!KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(scanned,
                    index.getImportChests(), getReservedContainerKeys(), config.pos1,
                    config.pos2, null, snapshot.exportPosition(), target)) {
                return "target is not an eligible scanned and fully registered import chest";
            }
            ContainerEntry entry = scanned.stream()
                    .filter(e -> e.x() == target[0] && e.y() == target[1]
                            && e.z() == target[2])
                    .findFirst().orElse(null);
            if (entry == null) return "import footprint is missing from the trusted scan";
            String shellHash = KitVariantFingerprint.canonicalShell(shellSlots);
            String cargoHash = KitVariantFingerprint.canonicalVariant(cargo);
            String sourceHash = KitVariantFingerprint.canonicalVariant(opportunity.mergeTargetStack());
            String outgoingHash = KitVariantFingerprint.canonicalVariant(opportunity.outgoingStack());
            if (shellHash == null || cargoHash == null || sourceHash == null
                    || outgoingHash == null) return "physical component fingerprint unavailable";
            var repair = new KitFulfillmentJournalStore.VariantRepair(target,
                    entry.isDouble() ? 54 : 27, cargoRaw, cargoHash, "end_crystal",
                    opportunity.amount(), opportunity.outgoingSlot(),
                    opportunity.mergeTargetSlot(), sourceHash, outgoingHash, shellHash,
                    -1, null, null, 0, 2);
            long now = System.currentTimeMillis();
            long due = now + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null,
                    snapshot.borrowedSourceItemId(), snapshot.borrowedSourceContents(),
                    "resume_variant_exchange", now, "VARIANT_NEED_SOURCE", due,
                    snapshot.sourceBoxesToReturn(), snapshot.auditedCargoSlot(),
                    snapshot.writtenOffSources(), snapshot.auditedCargoSlots(),
                    snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing(), snapshot.displacedAppleSlots(),
                    snapshot.retainedShell(), repair));
            kitResumeCadence.reset();
            debugRecorder.record("kit_variant_repair_scheduled", "kit=" + snapshot.kitName()
                    + ", amount=15, import=" + Arrays.toString(target)
                    + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_variant_repair_schedule_failed",
                    "target=" + Arrays.toString(target), e);
            return "could not schedule variant repair: " + e.getMessage();
        }
    }

    /** Read-only guard breakdown for a stopped variant exchange; no recovery is scheduled. */
    public String diagnoseKitVariantSourceCargo(int incomingRaw) {
        if (incomingRaw < 9 || incomingRaw > 35) return "Incoming raw slot must be 9-35.";
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || snapshot.variantRepair() == null)
                return "No variant-repair checkpoint exists.";
            if (!Proxy.getInstance().isConnected()) return "Bot disconnected; live inventory unavailable.";
            var repair = snapshot.variantRepair();
            var audited = snapshot.withVariantRepair(repair.withAuditedIncoming(incomingRaw));
            String failure = KitFulfillmentCoordinator.variantReadyBoundaryFailure(audited);
            boolean protectedApples = KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                    audited.displacedAppleSlots());
            Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
            String canonicalMatch = "unavailable";
            String legacyIncomingMatch = "unavailable";
            String liveCanonicalShell = "unavailable";
            if (player != null) {
                ItemStack shell = player.getItemStack(audited.ownedShellSlot());
                ItemStack incoming = player.getItemStack(incomingRaw);
                if (shell != null) liveCanonicalShell = KitVariantFingerprint.canonicalShell(
                        ItemIdentifier.readShulkerPhysicalSlotStacks(shell));
                if (shell != null && incoming != null && incoming.getAmount() > 0) {
                    ItemStack mergeTarget = ItemIdentifier.readShulkerPhysicalSlotStacks(shell)
                            .get(repair.mergeTargetSlot());
                    if (mergeTarget != null && repair.itemId().equals(
                            ItemIdentifier.getItemId(incoming))) {
                        canonicalMatch = Boolean.toString(Objects.equals(
                                KitVariantFingerprint.canonicalVariant(mergeTarget),
                                KitVariantFingerprint.canonicalVariant(incoming)));
                        legacyIncomingMatch = Boolean.toString(repair.sourceHash().equals(
                                KitVariantFingerprint.variant(incoming,
                                        repair.fingerprintVersion())));
                    }
                }
            }
            return "Checkpoint " + audited.completedBoxes() + "/" + audited.requestedBoxes()
                    + "; first failed guard=" + (failure == null ? "none" : failure)
                    + "; protected apples=" + protectedApples
                    + "; incoming canonical match to merge target=" + canonicalMatch
                    + "; incoming legacy hash match=" + legacyIncomingMatch
                    + "; borrowed helmet comparison="
                    + compareBorrowedVariantHelmet(audited, player)
                    + "; guarded canonical migration eligible="
                    + (canonicalizeLegacyVariantRepair(snapshot, incomingRaw, null, null) != null)
                    + "; migration guards="
                    + diagnoseVariantMigrationGuards(snapshot, player, incomingRaw)
                    + "; saved legacy shell=" + repair.shellHash()
                    + "; live canonical shell=" + liveCanonicalShell
                    + "; no checkpoint or inventory change.";
        } catch (IOException | RuntimeException e) {
            return "Variant diagnosis unavailable: " + e.getClass().getSimpleName()
                    + "; no checkpoint or inventory change.";
        }
    }

    /** Compare physical components without opening the borrowed box or trusting its item totals. */
    private static String compareBorrowedVariantHelmet(
            KitFulfillmentJournalStore.Snapshot snapshot, Container player) {
        if (player == null || snapshot.currentItem() == null
                || snapshot.variantRepair() == null) return "unavailable";
        var repair = snapshot.variantRepair();
        ItemStack cargo = player.getItemStack(repair.cargoRawSlot());
        if (cargo == null || cargo.getAmount() != 1
                || !snapshot.currentItem().equals(ItemIdentifier.getItemId(cargo))) {
            return "loose helmet missing or changed";
        }
        Set<Integer> pending = KitFulfillmentCoordinator.variantPendingSourceSlots(snapshot);
        if (pending.size() != snapshot.sourceBoxesToReturn().size()) {
            return "borrowed box ledger not uniquely verified";
        }
        List<Integer> helmetBoxes = pending.stream().sorted()
                .filter(raw -> ItemIdentifier.readShulkerContents(player.getItemStack(raw))
                        .getOrDefault(snapshot.currentItem(), 0) > 0)
                .toList();
        if (helmetBoxes.size() != 1) return "helmet source box not unique";
        int boxRaw = helmetBoxes.getFirst();
        Map<Integer, ItemStack> inner = ItemIdentifier.readShulkerPhysicalSlotStacks(
                player.getItemStack(boxRaw));
        if (inner.isEmpty()) return "helmet source physical slots unreadable";
        String liveLegacy = KitVariantFingerprint.variant(cargo);
        String canonical = KitVariantFingerprint.canonicalVariant(cargo);
        var cargoComponents = cargo.getDataComponents() == null ? Map.of()
                : cargo.getDataComponents().getDataComponents();
        int sameItemStacks = 0;
        int sameComponents = 0;
        int sameCanonical = 0;
        int sameLegacy = 0;
        int journalLegacy = 0;
        for (ItemStack stack : inner.values()) {
            if (!snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) continue;
            sameItemStacks++;
            var sourceComponents = stack.getDataComponents() == null ? Map.of()
                    : stack.getDataComponents().getDataComponents();
            if (cargo.getId() == stack.getId()
                    && cargoComponents.equals(sourceComponents)) sameComponents++;
            String sourceCanonical = KitVariantFingerprint.canonicalVariant(stack);
            if (Objects.equals(canonical, sourceCanonical)) sameCanonical++;
            String sourceLegacy = KitVariantFingerprint.variant(stack);
            if (Objects.equals(liveLegacy, sourceLegacy)) sameLegacy++;
            if (repair.cargoHash().equals(sourceLegacy)) journalLegacy++;
        }
        return "source raw " + boxRaw + ", matching-ID physical stacks=" + sameItemStacks
                + ", exact components=" + sameComponents
                + ", canonical fingerprint=" + sameCanonical
                + ", live legacy fingerprint=" + sameLegacy
                + ", checkpoint legacy fingerprint=" + journalLegacy
                + ", loose helmet checkpoint legacy="
                + repair.cargoHash().equals(liveLegacy);
    }

    /** Upgrade only a stopped pre-click legacy checkpoint with independent physical evidence.
     * The old hash must not identify a different helmet still in the verified source box. */
    private static KitFulfillmentJournalStore.VariantRepair canonicalizeLegacyVariantRepair(
            KitFulfillmentJournalStore.Snapshot snapshot, int incomingRaw,
            String acknowledgedLegacyShell, String acknowledgedCanonicalShell) {
        var repair = snapshot.variantRepair();
        if (repair == null || repair.fingerprintVersion() != 0
                || repair.displacedRawSlot() != -1
                || repair.auditedIncomingRawSlot() != 0
                || snapshot.kitWorksite() != null || snapshot.borrowedSourceWorksite() != null
                || !KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot)
                || KitFulfillmentCoordinator.uniqueCurrentLooseCargoSlot(snapshot)
                        != repair.cargoRawSlot()) return null;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return null;
        ItemStack cargo = player.getItemStack(repair.cargoRawSlot());
        ItemStack incoming = player.getItemStack(incomingRaw);
        ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
        if (cargo == null || cargo.getAmount() != 1
                || !snapshot.currentItem().equals(ItemIdentifier.getItemId(cargo))
                || incoming == null || incoming.getAmount() != repair.amount()
                || !repair.itemId().equals(ItemIdentifier.getItemId(incoming))
                || repair.cargoHash().equals(KitVariantFingerprint.variant(cargo))) return null;
        Set<Integer> pending = KitFulfillmentCoordinator.variantPendingSourceSlots(snapshot);
        if (pending.size() != snapshot.sourceBoxesToReturn().size()) return null;
        List<Integer> helmetBoxes = pending.stream()
                .filter(raw -> ItemIdentifier.readShulkerContents(player.getItemStack(raw))
                        .getOrDefault(snapshot.currentItem(), 0) > 0).toList();
        if (helmetBoxes.size() != 1) return null;
        Map<Integer, ItemStack> sourceSlots = ItemIdentifier.readShulkerPhysicalSlotStacks(
                player.getItemStack(helmetBoxes.getFirst()));
        if (sourceSlots.isEmpty()) return null;
        if (!KitVariantFingerprint.witnessedLegacyCargo(
                cargo, repair.cargoHash(), sourceSlots.values())) return null;
        Map<Integer, ItemStack> shellSlots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
        if (shellSlots.size() != KitCapacity.MAX_SLOTS) return null;
        boolean oldShellMatches = repair.shellHash().equals(
                KitVariantFingerprint.shell(shellSlots));
        if (acknowledgedLegacyShell != null && oldShellMatches) return null;
        if (!oldShellMatches && !KitVariantShellRebaseline.matches(shellSlots,
                snapshot.packedContents(), snapshot.recipe(), repair.shellHash(),
                acknowledgedLegacyShell, acknowledgedCanonicalShell))
            return null;
        var physical = shellSlots.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        var opportunity = KitVariantExchangePlan.shellOpportunity(physical,
                repair.itemId(), snapshot.packedContents().getOrDefault(repair.itemId(), 0),
                KitCapacity.MAX_SLOTS - 1);
        if (opportunity == null || opportunity.amount() != repair.amount()
                || opportunity.outgoingSlot() != repair.outgoingSlot()
                || opportunity.mergeTargetSlot() != repair.mergeTargetSlot()
                || !repair.sourceHash().equals(KitVariantFingerprint.variant(
                        opportunity.mergeTargetStack()))
                || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                        opportunity.outgoingStack()))
                || !repair.sourceHash().equals(KitVariantFingerprint.variant(incoming))
                || !KitVariantFingerprint.sameComponents(incoming,
                        opportunity.mergeTargetStack())) return null;
        return repair.withCanonicalEvidence(
                KitVariantFingerprint.canonicalVariant(cargo),
                KitVariantFingerprint.canonicalVariant(opportunity.mergeTargetStack()),
                KitVariantFingerprint.canonicalVariant(opportunity.outgoingStack()),
                KitVariantFingerprint.canonicalShell(shellSlots));
    }

    private static String diagnoseVariantMigrationGuards(
            KitFulfillmentJournalStore.Snapshot snapshot, Container player, int incomingRaw) {
        if (player == null || snapshot.variantRepair() == null) return "unavailable";
        var repair = snapshot.variantRepair();
        ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
        Map<Integer, ItemStack> slots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
        var physical = slots.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        var opportunity = KitVariantExchangePlan.shellOpportunity(physical,
                repair.itemId(), snapshot.packedContents().getOrDefault(repair.itemId(), 0),
                KitCapacity.MAX_SLOTS - 1);
        ItemStack incoming = player.getItemStack(incomingRaw);
        return "shell=" + repair.shellHash().equals(KitVariantFingerprint.shell(slots))
                + ", opportunity=" + (opportunity != null
                    && opportunity.amount() == repair.amount()
                    && opportunity.outgoingSlot() == repair.outgoingSlot()
                    && opportunity.mergeTargetSlot() == repair.mergeTargetSlot())
                + ", source=" + (opportunity != null && repair.sourceHash().equals(
                    KitVariantFingerprint.variant(opportunity.mergeTargetStack())))
                + ", outgoing=" + (opportunity != null && repair.outgoingHash().equals(
                    KitVariantFingerprint.variant(opportunity.outgoingStack())))
                + ", incomingComponents=" + (opportunity != null
                    && KitVariantFingerprint.sameComponents(incoming,
                            opportunity.mergeTargetStack()));
    }

    /** Audit the exact 15 crystals already retrieved by a stopped variant exchange.
     * This journals a pack-only continuation and must never repeat retrieval. */
    public @Nullable String auditKitVariantSourceCargo(int incomingRaw) {
        return auditKitVariantSourceCargo(incomingRaw, null, null);
    }

    /** Explicitly acknowledge a changed legacy shell representation at this one boundary. */
    public @Nullable String auditKitVariantSourceCargoRebaseline(int incomingRaw,
            String savedLegacyShell, String liveCanonicalShell) {
        if (savedLegacyShell == null || liveCanonicalShell == null
                || !savedLegacyShell.matches("[0-9a-f]{64}")
                || !liveCanonicalShell.matches("[0-9a-f]{64}")) {
            return "both shell fingerprints must be lowercase SHA-256 values";
        }
        return auditKitVariantSourceCargo(incomingRaw, savedLegacyShell, liveCanonicalShell);
    }

    private @Nullable String auditKitVariantSourceCargo(int incomingRaw,
            String savedLegacyShell, String liveCanonicalShell) {
        if (incomingRaw < 9 || incomingRaw > 35) return "source cargo slot is invalid";
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())
                || (state != ScanState.IDLE && state != ScanState.DONE)) {
            return "another stash movement is active";
        }
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            return "bot must be alive and online with no client controller attached";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        var cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || (cursor != null && cursor.getAmount() > 0)
                || INVENTORY.hasActiveRequest()) {
            return "close the container and clear the cursor before auditing cargo";
        }
        if (config.pos1 == null || config.pos2 == null
                || database == null || !database.isInitialized()) {
            return "stash region or database is unavailable";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                    || !"kit_variant_source_cargo_unverified".equals(snapshot.blocker())
                    || snapshot.resumeState() != null || snapshot.variantRepair() == null
                    || snapshot.variantRepair().auditedIncomingRawSlot() != 0
                    || snapshot.variantRepair().displacedRawSlot() != -1
                    || snapshot.kitWorksite() != null
                    || snapshot.borrowedSourceWorksite() != null) {
                return "checkpoint is not at the stopped variant-source boundary";
            }
            var repair = snapshot.variantRepair();
            if (savedLegacyShell != null && (repair.fingerprintVersion() != 0
                    || !repair.shellHash().equals(savedLegacyShell)
                    || repair.shellHash().equals(liveCanonicalShell))) {
                return "legacy shell attestation does not match this checkpoint";
            }
            if (repair.fingerprintVersion() == 0
                    && "legacy_cargo_fingerprint".equals(
                            KitFulfillmentCoordinator.variantRepairBoundaryFailure(snapshot))) {
                repair = canonicalizeLegacyVariantRepair(snapshot, incomingRaw,
                        savedLegacyShell, liveCanonicalShell);
                if (repair == null) return "legacy fingerprint migration lacks exact source evidence";
            }
            repair = repair.withAuditedIncoming(incomingRaw);
            var audited = snapshot.withVariantRepair(repair);
            String variantFailure = KitFulfillmentCoordinator.variantReadyBoundaryFailure(audited);
            if (variantFailure != null) return "variant boundary: " + variantFailure;
            if (!KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                    audited.displacedAppleSlots())) return "protected apple slots changed";
            Set<Integer> owned = new HashSet<>(
                    KitFulfillmentCoordinator.variantPendingSourceSlots(audited));
            if (owned.size() != audited.sourceBoxesToReturn().size()) {
                return "borrowed source box identities are ambiguous";
            }
            owned.add(audited.ownedShellSlot());
            owned.add(repair.cargoRawSlot());
            owned.add(incomingRaw);
            owned.addAll(audited.displacedAppleSlots().keySet());
            if (audited.retainedShell() != null) {
                int retainedSlot = KitFulfillmentCoordinator.uniqueRetainedShellSlot(audited);
                if (retainedSlot < 0) return "reserved empty kit shell is missing";
                owned.add(retainedSlot);
            }
            var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(), owned);
            if (admission == null || !admission.accepted()) {
                return "unexpected inventory slots: "
                        + (admission == null ? "unavailable" : admission.unexpectedSlots());
            }
            var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
            if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                    scan.found(), scan.indexed(), scan.failed())) {
                return "fresh trusted scan required for source and import footprints";
            }
            List<ContainerEntry> scanned = database.getAllContainers().stream()
                    .filter(this::inConfiguredRegion)
                    .filter(entry -> entry.timestamp() >= scan.startedAtEpochMilli())
                    .toList();
            if (!KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(scanned,
                    index.getImportChests(), getReservedContainerKeys(), config.pos1,
                    config.pos2, null, audited.exportPosition(), repair.importPosition())) {
                return "import chest is no longer eligible and freshly indexed";
            }
            long now = System.currentTimeMillis();
            long due = now + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, audited.kitName(), "BLOCKED",
                    audited.recipe(), audited.packedContents(), audited.requestedBoxes(),
                    audited.completedBoxes(), audited.currentItem(), audited.currentQuantity(),
                    audited.ownedShellSlot(), audited.exportPosition(), null, null,
                    audited.borrowedSourceItemId(), audited.borrowedSourceContents(),
                    "resume_variant_source_cargo", now, "VARIANT_WAIT_SOURCE", due,
                    audited.sourceBoxesToReturn(), audited.auditedCargoSlot(),
                    audited.writtenOffSources(), audited.auditedCargoSlots(),
                    audited.incompleteBoxes(), audited.missingTotals(),
                    audited.currentBoxMissing(), audited.displacedAppleSlots(),
                    audited.retainedShell(), repair));
            kitResumeCadence.reset();
            debugRecorder.record("kit_variant_source_cargo_audited", "kit=" + audited.kitName()
                    + ", raw=" + incomingRaw + ", amount=" + repair.amount()
                    + ", shell_rebaseline=" + (savedLegacyShell != null)
                    + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_variant_source_cargo_audit_failed",
                    "raw=" + incomingRaw, e);
            return "could not audit variant source cargo: " + e.getMessage();
        }
    }

    /** Schedule a single operator-selected source return. The saved index establishes
     * the import role and footprint; KitExporter must prove live free capacity. */
    public @Nullable String scheduleKitSourceReturnToCapacityImport(int[] target) {
        if (target == null || target.length != 3) return "return target is invalid";
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())) {
            return "another stash movement is active";
        }
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
            return "bot must be online with no client controller attached";
        }
        var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
        var cursor = inventoryCache.getMouseStack();
        if (inventoryCache.getOpenContainerId() > 0
                || (cursor != null && cursor.getAmount() > 0)
                || INVENTORY.hasActiveRequest()) {
            return "close the container and clear the cursor before returning a source";
        }
        if (config.pos1 == null || config.pos2 == null
                || database == null || !database.isInitialized()) {
            return "stash region or database is unavailable";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            boolean unjournaled = KitResumePolicy.canAuditUnjournaledBorrowedSource(snapshot);
            boolean failedDepositAudit = KitResumePolicy.canAuditFailedSourceReturn(snapshot);
            if (!unjournaled && !failedDepositAudit
                    && !KitResumePolicy.canScheduleLiveCapacityImportReturn(snapshot)) {
                return "checkpoint is not at the no-capacity-import source-return boundary";
            }
            int ownedShellSlot = KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot);
            if (ownedShellSlot < 0 || (!unjournaled
                    && KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot) < 0)) {
                return "packed kit shell or borrowed source shulker no longer matches the checkpoint";
            }
            KitFulfillmentJournalStore.BorrowedBox source;
            if (unjournaled) {
                if (!KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                        snapshot.displacedAppleSlots())) {
                    return "protected apple slots no longer match the checkpoint";
                }
                var admission = KitInventoryAdmissionPolicy.inspectLive(
                        database.loadKeepItems(), java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(ownedShellSlot),
                                snapshot.displacedAppleSlots().keySet().stream())
                                .collect(java.util.stream.Collectors.toSet()));
                if (admission == null || admission.unexpectedSlots().size() != 1) {
                    return "exactly one unowned source box must be the only unexpected slot";
                }
                int raw = admission.unexpectedSlots().getFirst();
                Container player = inventoryCache.getPlayerInventory();
                ItemStack stack = player == null || raw < 9 || raw > 44
                        ? null : player.getItemStack(raw);
                if (stack == null || stack.getAmount() != 1
                        || !ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                                .contains("shulker_box")) {
                    return "the sole unexpected slot is not one physical shulker box";
                }
                Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
                if (contents.isEmpty() || contents.equals(snapshot.packedContents())) {
                    return "unowned source box contents are empty or match the kit shell";
                }
                source = new KitFulfillmentJournalStore.BorrowedBox(
                        null, ItemIdentifier.getItemId(stack), contents);
            } else {
                source = snapshot.sourceBoxesToReturn().getFirst();
            }
            var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
            if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                    scan.found(), scan.indexed(), scan.failed())) {
                return "fresh trusted scan required for the import footprint";
            }
            List<ContainerEntry> scanned = database.getAllContainers().stream()
                    .filter(this::inConfiguredRegion)
                    .filter(entry -> entry.timestamp() >= scan.startedAtEpochMilli())
                    .toList();
            if (!KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(scanned,
                    index.getImportChests(), getReservedContainerKeys(), config.pos1,
                    config.pos2, source.sourcePosition(), snapshot.exportPosition(), target)) {
                return "target is not an eligible scanned and fully registered import chest";
            }
            var selected = new KitFulfillmentJournalStore.BorrowedBox(
                    source.sourcePosition(), source.itemId(), source.contents(), target);
            long now = System.currentTimeMillis();
            long due = now + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    ownedShellSlot, snapshot.exportPosition(), snapshot.kitWorksite(),
                    snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), failedDepositAudit
                            ? "resume_audit_failed_source_return"
                            : "resume_source_return_exact_import",
                    now, failedDepositAudit ? "VERIFY_FAILED_RETURN" : "RETURNING_SOURCE",
                    due, List.of(selected),
                    snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                    snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots())
                    .withRetainedShell(snapshot.retainedShell()));
            kitResumeCadence.reset();
            debugRecorder.record("kit_source_return_exact_import_scheduled",
                    "kit=" + snapshot.kitName() + ", target=" + Arrays.toString(target)
                    + ", audited_unjournaled_box=" + unjournaled
                    + ", verify_failed_deposit=" + failedDepositAudit
                    + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_source_return_exact_import_schedule_failed",
                    "target=" + Arrays.toString(target), e);
            return "could not schedule exact import return: " + e.getMessage();
        }
    }

    /** Resume one observed loose stack without mistaking identical kept supplies for cargo. */
    public @Nullable String scheduleKitCargoAudit(int rawSlot, int quantity) {
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()) {
            return "kit movement or worksite recovery is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            boolean partialCargo = snapshot != null
                    && "kit_cargo_retrieval_incomplete".equals(snapshot.blocker());
            boolean attestedLostBorrowedCargo =
                    KitResumePolicy.canAuditLostBorrowedCargo(snapshot, rawSlot, quantity);
            boolean swappedBorrowedShell = snapshot != null
                    && "kit_preexisting_inventory_changed".equals(snapshot.blocker())
                    && snapshot.sourceBoxesToReturn() != null
                    && !snapshot.sourceBoxesToReturn().isEmpty()
                    && quantity == snapshot.currentQuantity();
            if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                    || (!partialCargo && !swappedBorrowedShell
                        && !attestedLostBorrowedCargo)
                    || snapshot.resumeState() != null
                    || snapshot.borrowedSourceWorksite() != null
                    || snapshot.kitWorksite() != null
                    || snapshot.currentItem() == null
                    || quantity < 1 || quantity > snapshot.currentQuantity()
                    || quantity > snapshot.recipe().getOrDefault(snapshot.currentItem(), 0)
                            - snapshot.packedContents().getOrDefault(snapshot.currentItem(), 0)) {
                return "checkpoint is not at a recoverable partial-cargo boundary";
            }
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            var cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0)) {
                return "close the container and clear the cursor before auditing cargo";
            }
            int verifiedShellSlot = swappedBorrowedShell
                    ? KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot)
                    : snapshot.ownedShellSlot();
            if (verifiedShellSlot < 0 || (partialCargo
                    && !kitFulfillment.hasVerifiedOwnedShell(snapshot))) {
                return "packed kit shulker no longer matches the checkpoint";
            }
            if (attestedLostBorrowedCargo
                    && (verifiedShellSlot != snapshot.ownedShellSlot()
                        || !kitFulfillment.hasVerifiedOwnedShell(snapshot)
                        || KitFulfillmentCoordinator.uniqueCurrentLooseCargoSlot(snapshot)
                            != rawSlot)) {
                return "attested-loss cargo is not the unique exact loose task stack";
            }
            var audited = new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), quantity,
                    verifiedShellSlot, snapshot.exportPosition(), null, null,
                    null, Map.of(), "resume_audited_inventory_cargo",
                    System.currentTimeMillis(), "WAIT_CARGO",
                    System.currentTimeMillis()
                            + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L,
                    snapshot.sourceBoxesToReturn(), rawSlot, snapshot.writtenOffSources(),
                    snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots());
            if (swappedBorrowedShell
                    && KitFulfillmentCoordinator.uniqueSourceReturnSlot(audited) < 0) {
                return "borrowed source shulker is missing or ambiguous";
            }
            if (KitFulfillmentCoordinator.uniqueAuditedCargoSlot(audited) != rawSlot) {
                return "slot does not contain the exact audited item and count";
            }
            kitJournal.save(audited);
            kitResumeCadence.reset();
            debugRecorder.record("kit_partial_cargo_audited",
                    "kit=" + snapshot.kitName() + ", item=" + snapshot.currentItem()
                            + ", quantity=" + quantity + ", raw_slot=" + rawSlot);
            return null;
        } catch (Exception e) {
            return "could not schedule audited cargo recovery: " + e.getMessage();
        }
    }

    /** Inventory evidence for a blocked batch; this does not mutate the checkpoint. */
    public String diagnoseKitCargo() {
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null) return "No kit checkpoint exists.";
            if (!Proxy.getInstance().isConnected()) return "Bot disconnected; live cargo unavailable.";
            var cache = CACHE.getPlayerCache().getInventoryCache();
            Container player = cache.getPlayerInventory();
            if (player == null) return "Player inventory unavailable.";
            boolean shellMatches = KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot);
            int uniqueShellSlot = KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot);
            StringBuilder out = new StringBuilder("Kit cargo: ")
                    .append(snapshot.kitName()).append(' ')
                    .append(snapshot.completedBoxes()).append('/')
                    .append(snapshot.requestedBoxes()).append("; blocker=")
                    .append(snapshot.blocker()).append("; expected=")
                    .append(snapshot.currentItem()).append(" x")
                    .append(snapshot.currentQuantity()).append("; shell matches=")
                    .append(shellMatches)
                    .append("; unique shell slot=").append(uniqueShellSlot)
                    .append("; open container=").append(cache.getOpenContainerId());
            if (shellMatches && snapshot.ownedShellSlot() >= 9
                    && snapshot.ownedShellSlot() <= 44) {
                ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
                out.append("; shell occupied slots=")
                        .append(ItemIdentifier.readShulkerOccupiedSlots(shell))
                        .append("/27; packed minimum slots=")
                        .append(KitCapacity.requiredSlots(snapshot.packedContents()))
                        .append("; recipe minimum slots=")
                        .append(KitCapacity.requiredSlots(snapshot.recipe()))
                        .append("; physical stack slots by item=")
                        .append(ItemIdentifier.readShulkerStackSlots(shell))
                        .append("; enchanted golden apple stacks=")
                        .append(ItemIdentifier.readShulkerStackDetails(shell, "enchanted_golden_apple"))
                        .append("; end crystal stacks=")
                        .append(ItemIdentifier.readShulkerStackDetails(shell, "end_crystal"));
                Integer appleRequired = snapshot.recipe().get("enchanted_golden_apple");
                if (appleRequired != null && appleRequired > 0) {
                    var applePlan = com.zenith.plugin.stashmanager.kit.KitAppleExchangePlan
                            .choose(ItemIdentifier.readShulkerPhysicalStacks(shell), List.of(),
                                    appleRequired,
                                    KitCapacity.requiredSlots(Map.of("enchanted_golden_apple",
                                            appleRequired)));
                    out.append("; apple component plan without surveyed sources=")
                            .append(applePlan.choice()).append(" retained=")
                            .append(applePlan.retained()).append(" replace=")
                            .append(applePlan.toRemove()).append(" shortfall=")
                            .append(applePlan.shortfall());
                }
            }
            var cursor = cache.getMouseStack();
            out.append("; cursor count=").append(cursor == null ? 0 : cursor.getAmount());
            int total = 0;
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack != null && stack.getAmount() > 0
                        && snapshot.currentItem() != null
                        && snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) {
                    out.append("; slot ").append(raw).append('=')
                            .append(stack.getAmount());
                    total += stack.getAmount();
                }
            }
            out.append("; total loose=").append(total);
            if ("kit_preexisting_inventory_changed".equals(snapshot.blocker())
                    && kitFulfillment.lastBaselineMismatch() != null) {
                out.append("; baseline mismatch=")
                        .append(kitFulfillment.lastBaselineMismatch());
            }
            if (snapshot.borrowedSourceWorksite() != null) {
                out.append("; journaled borrowed box=")
                        .append(snapshot.borrowedSourceItemId()).append(' ')
                        .append(snapshot.borrowedSourceContents());
            }
            int candidates = 0;
            for (int raw = 9; raw <= 45; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack == null || stack.getAmount() <= 0) continue;
                String id = ItemIdentifier.getItemId(stack);
                if (!ItemIdentifier.baseItemId(id).contains("shulker_box")) continue;
                Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
                if (snapshot.currentItem() == null
                        || !contents.containsKey(snapshot.currentItem())) continue;
                if (++candidates <= 12) {
                    out.append("; carried source slot ").append(raw).append('=')
                            .append(id).append(' ').append(contents);
                }
            }
            if (candidates > 12) out.append("; +").append(candidates - 12)
                    .append(" more carried candidates");
            int shulkers = 0;
            for (int raw = 9; raw <= 45; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack == null || stack.getAmount() != 1) continue;
                String id = ItemIdentifier.getItemId(stack);
                if (!ItemIdentifier.baseItemId(id).contains("shulker_box")) continue;
                shulkers++;
                if (shulkers <= 12) {
                    out.append("; shulker raw ").append(raw).append('=')
                            .append(id).append(' ')
                            .append(ItemIdentifier.readShulkerContents(stack));
                }
            }
            if (shulkers > 12) out.append("; +").append(shulkers - 12)
                    .append(" more carried shulkers");
            if (snapshot.kitWorksite() != null && cache.getOpenContainerId() > 0
                    && cache.getOpenContainer() != null
                    && cache.getOpenContainer().getSize() - 36 == 27) {
                out.append("; ").append(inspectOpenKitWorksite());
            }
            return out.toString();
        } catch (Exception e) {
            return "Kit cargo inspection failed: " + e.getMessage();
        }
    }

    /**
     * Reconcile the narrow case where the pre-placement journal named the owned kit shell,
     * while the actual chest-borrowed shulker was recovered into inventory. All physical
     * identities and the operator-observed source delta must agree before any write.
     * Syntax: sourceSlot originX originY originZ sourceBefore sourceTaken slot:count ...
     */
    public @Nullable String auditMismatchedBorrowedSource(String evidence) {
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())) {
            return "another automation or recovery operation is active";
        }
        try {
            String[] tokens = evidence == null ? new String[0] : evidence.trim().split("\\s+");
            if (tokens.length == 2 && "recycled".equals(tokens[0])) {
                return auditRetainedKitShell(Integer.parseInt(tokens[1]));
            }
            if (tokens.length < 7) {
                return "use sourceSlot originX originY originZ sourceBefore sourceTaken slot:count ...";
            }
            int sourceSlot = Integer.parseInt(tokens[0]);
            int[] origin = {Integer.parseInt(tokens[1]), Integer.parseInt(tokens[2]),
                    Integer.parseInt(tokens[3])};
            int sourceBefore = Integer.parseInt(tokens[4]);
            int sourceTaken = Integer.parseInt(tokens[5]);
            if (sourceSlot < 9 || sourceSlot > 44 || sourceBefore < 1 || sourceBefore > 1728
                    || sourceTaken < 1 || sourceTaken >= sourceBefore) {
                return "borrowed source slot or item-count evidence is out of range";
            }
            Map<Integer, Integer> pinned = new java.util.LinkedHashMap<>();
            for (int i = 6; i < tokens.length; i++) {
                String[] pair = tokens[i].split(":", -1);
                if (pair.length != 2) return "cargo evidence must use slot:count pairs";
                int slot = Integer.parseInt(pair[0]);
                int count = Integer.parseInt(pair[1]);
                if (slot < 9 || slot > 44 || count < 1 || count > 64
                        || pinned.putIfAbsent(slot, count) != null) {
                    return "cargo slot, count, or uniqueness is invalid";
                }
            }
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                    || !"borrowed_source_contents_unverified".equals(snapshot.blocker())
                    || snapshot.resumeState() != null || snapshot.kitWorksite() != null
                    || snapshot.borrowedSourceWorksite() == null
                    || !snapshot.sourceBoxesToReturn().isEmpty()
                    || snapshot.currentItem() == null || snapshot.currentQuantity() <= 0
                    || sourceSlot == snapshot.ownedShellSlot()) {
                return "checkpoint is not at the mismatched borrowed-source boundary";
            }
            if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
                return "bot must be in-world with no controller attached";
            }
            var cache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = cache.getMouseStack();
            if (cache.getOpenContainerId() > 0 || INVENTORY.hasActiveRequest()
                    || (cursor != null && cursor.getAmount() > 0)) {
                return "inventory transaction is still open";
            }
            if (!KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot)) {
                return "owned kit shell does not match the checkpoint";
            }
            Container player = cache.getPlayerInventory();
            ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
            if (!snapshot.borrowedSourceItemId().equals(ItemIdentifier.getItemId(shell))
                    || !snapshot.borrowedSourceContents().equals(
                            ItemIdentifier.readShulkerContents(shell))) {
                return "journaled borrowed identity is not the intact owned kit shell";
            }
            ItemStack source = player.getItemStack(sourceSlot);
            if (source == null || source.getAmount() != 1) {
                return "borrowed source is absent from the specified inventory slot";
            }
            String sourceId = ItemIdentifier.getItemId(source);
            Map<String, Integer> sourceContents = ItemIdentifier.readShulkerContents(source);
            if (!ItemIdentifier.baseItemId(sourceId).contains("shulker_box")
                    || sourceId.equals(snapshot.borrowedSourceItemId())
                    || sourceContents.size() != 1
                    || sourceContents.getOrDefault(snapshot.currentItem(), 0)
                            != sourceBefore - sourceTaken) {
                return "carried source identity or observed withdrawal does not match";
            }
            int[] worksite = snapshot.borrowedSourceWorksite();
            if (!World.isChunkLoadedBlockPos(worksite[0], worksite[2])
                    || World.getChunkSection(worksite[0], worksite[1], worksite[2]) == null
                    || !BlockCompat.isAir(World.getBlock(worksite[0], worksite[1], worksite[2]))) {
                return "journaled worksite is not loaded and empty";
            }
            ContainerEntry originEntry = index.get(origin[0], origin[1], origin[2]);
            if (originEntry == null || index.isExportChest(originEntry)
                    || !World.isChunkLoadedBlockPos(origin[0], origin[2])
                    || !World.getBlock(origin[0], origin[1], origin[2]).name().contains("chest")) {
                return "original source chest is not a live indexed input chest";
            }
            var returning = List.of(new KitFulfillmentJournalStore.BorrowedBox(
                    origin, sourceId, sourceContents));
            var audited = new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), null, null, null,
                    Map.of(), "resume_audited_inventory_cargo", System.currentTimeMillis(),
                    "WAIT_CARGO", System.currentTimeMillis()
                            + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L,
                    returning, -1, snapshot.writtenOffSources(), pinned,
                    snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots());
            if (!KitFulfillmentCoordinator.verifiedAuditedCargo(audited)
                    || KitFulfillmentCoordinator.uniqueSourceReturnSlot(audited) != sourceSlot
                    || KitResumePolicy.blocker(audited) != null) {
                return "pinned cargo or borrowed source is missing or ambiguous";
            }
            kitJournal.save(audited);
            kitResumeCadence.reset();
            debugRecorder.record("kit_mismatched_borrowed_source_audited",
                    "kit=" + snapshot.kitName() + ", source_slot=" + sourceSlot
                            + ", source=" + sourceId + " " + sourceContents
                            + ", before=" + sourceBefore + ", taken=" + sourceTaken
                            + ", return=" + java.util.Arrays.toString(origin)
                            + ", cargo=" + pinned);
            return null;
        } catch (NumberFormatException e) {
            return "source, origin, and cargo counts must be integers";
        } catch (Exception e) {
            return "borrowed source audit failed: " + e.getMessage();
        }
    }

    /** Reconcile one fully delivered box only after inspecting the exact live output GUI. */
    public @Nullable String auditKitExportDelivery(List<int[]> targetedChest, int expectedFullBoxes) {
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()
                || (organizer != null && organizer.isActive())) {
            return "another inventory or recovery job is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || snapshot.exportPosition() == null
                    || targetedChest == null || targetedChest.stream().noneMatch(
                            pos -> java.util.Arrays.equals(pos, snapshot.exportPosition()))) {
                return "the targeted chest is not this kit's saved output chest";
            }
            if (!Proxy.getInstance().isConnected()) return "bot is disconnected";
            var cache = CACHE.getPlayerCache().getInventoryCache();
            Container open = cache.getOpenContainer();
            int openId = cache.getOpenContainerId();
            if (openId <= 0 || open == null || open.getContainerId() != openId) {
                return "open the saved output chest and leave its GUI open while auditing";
            }
            int chestSlots = open.getSize() - 36;
            if (chestSlots != 27 && chestSlots != 54) {
                return "the open GUI is not a chest";
            }
            int fullBoxes = 0;
            for (int slot = 0; slot < chestSlots; slot++) {
                ItemStack stack = open.getItemStack(slot);
                if (stack != null && stack.getAmount() == 1
                        && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                                .contains("shulker_box")
                        && ItemIdentifier.readShulkerContents(stack).equals(snapshot.recipe())) {
                    fullBoxes++;
                }
            }
            Container player = cache.getPlayerInventory();
            if (player == null) return "player inventory is unavailable";
            int matchingPlayerBoxes = 0;
            int currentItemLooseCount = 0;
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack != null && snapshot.currentItem() != null
                        && snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) {
                    currentItemLooseCount += stack.getAmount();
                }
                if (stack != null && stack.getAmount() == 1
                        && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                                .contains("shulker_box")
                        && ItemIdentifier.readShulkerContents(stack).equals(snapshot.recipe())) {
                    matchingPlayerBoxes++;
                }
            }
            ItemStack cursor = cache.getMouseStack();
            String blocker = KitExportAudit.blocker(snapshot, expectedFullBoxes, fullBoxes,
                    matchingPlayerBoxes, currentItemLooseCount,
                    cursor == null ? 0 : cursor.getAmount());
            if (blocker != null) return blocker + " (observed " + fullBoxes + " exact boxes)";
            kitJournal.save(KitExportAudit.reconciledSnapshot(snapshot, System.currentTimeMillis()));
            kitResumeCadence.reset();
            debugRecorder.record("kit_export_delivery_audited", "kit=" + snapshot.kitName()
                    + ", completed_boxes=" + (snapshot.completedBoxes() + 1)
                    + ", exact_boxes_in_chest=" + fullBoxes
                    + ", chest=" + java.util.Arrays.toString(snapshot.exportPosition()));
            return null;
        } catch (Exception e) {
            return "export audit failed: " + e.getMessage();
        }
    }

    /** Human-pinned multi-stack recovery; no inventory action occurs before live verification. */
    public @Nullable String scheduleKitCargoBatchAudit(String slots) {
        if (kitFulfillment.isActive() || retriever.isActive() || kitRecovery.isActive()) {
            return "kit movement or worksite recovery is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (KitEmptyShellOwnershipAudit.canAudit(snapshot)) {
                return scheduleKitEmptyShellOwnershipAudit(snapshot, slots);
            }
            boolean unverifiedBorrowed =
                    KitResumePolicy.canAuditUnverifiedBorrowedCargo(snapshot);
            boolean recoveredBorrowed =
                    KitResumePolicy.canAuditRecoveredBorrowedCargo(snapshot);
            boolean changedBaselineAudit =
                    KitResumePolicy.canAuditChangedBaselineCargo(snapshot);
            boolean keptAppleAudit = KitResumePolicy.canAuditKeptAppleCargo(snapshot);
            boolean partialBorrowedAppleAudit =
                    KitResumePolicy.canAuditPartialBorrowedAppleCargo(snapshot);
            boolean packerPreflightRetry = KitResumePolicy.canRetryPackerPreflight(snapshot);
            keptAppleAudit |= partialBorrowedAppleAudit || packerPreflightRetry;
            partialBorrowedAppleAudit |= packerPreflightRetry;
            if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                    || (!"recovered_box_requires_cargo_audit".equals(snapshot.blocker())
                        && !unverifiedBorrowed && !keptAppleAudit && !changedBaselineAudit)
                    || snapshot.currentItem() == null || snapshot.currentQuantity() < 2
                    || snapshot.kitWorksite() != null
                    || snapshot.borrowedSourceWorksite() != null
                    || (!unverifiedBorrowed && !recoveredBorrowed
                        && !partialBorrowedAppleAudit
                        && !changedBaselineAudit
                        && !snapshot.sourceBoxesToReturn().isEmpty())) {
                return "checkpoint is not at a recovered-box cargo audit boundary";
            }
            if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
                return "bot must be online with no client controller attached";
            }
            var cache = CACHE.getPlayerCache().getInventoryCache();
            var cursor = cache.getMouseStack();
            if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)) {
                return "close the container and clear the cursor before auditing cargo";
            }
            Container player = cache.getPlayerInventory();
            if (player == null) return "player inventory is unavailable";
            int ownedShellSlot = KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot)
                    ? snapshot.ownedShellSlot()
                    : KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot);
            if (ownedShellSlot < 9 || ownedShellSlot > 44) {
                return "packed kit shulker no longer matches the checkpoint";
            }
            if (changedBaselineAudit
                    && (!KitFulfillmentCoordinator.verifiedPendingSourceInventory(snapshot)
                        || !KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                                snapshot.displacedAppleSlots()))) {
                return "pending borrowed boxes or displaced apples are missing or ambiguous";
            }
            if ((unverifiedBorrowed || recoveredBorrowed || partialBorrowedAppleAudit)
                    && KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot) < 0) {
                return "borrowed source shulker is missing or ambiguous";
            }
            if (recoveredBorrowed
                    && !KitFulfillmentCoordinator.verifiedPendingSourceInventory(snapshot)) {
                return "pending borrowed source identity or shulker inventory is ambiguous";
            }
            Map<Integer, Integer> pinned = new java.util.LinkedHashMap<>();
            for (String token : slots.trim().split("\\s+")) {
                String[] parts = token.split(":", -1);
                if (parts.length != 2) return "use slot:count pairs, for example 26:64 33:64";
                int raw = Integer.parseInt(parts[0]);
                int count = Integer.parseInt(parts[1]);
                if (pinned.putIfAbsent(raw, count) != null) return "duplicate inventory slot: " + raw;
            }
            if (packerPreflightRetry && !pinned.equals(snapshot.auditedCargoSlots())) {
                return "preflight retry must pin the same audited cargo slots and counts";
            }
            if (changedBaselineAudit) {
                if (database == null || !database.isInitialized()) {
                    return "inventory keep policy is unavailable";
                }
                Set<Integer> admitted = new HashSet<>(pinned.keySet());
                admitted.add(ownedShellSlot);
                admitted.addAll(snapshot.displacedAppleSlots().keySet());
                for (int raw = 9; raw <= 44; raw++) {
                    ItemStack stack = player.getItemStack(raw);
                    if (stack != null && stack.getAmount() > 0
                            && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                                    .contains("shulker_box")) admitted.add(raw);
                }
                var admission = KitInventoryAdmissionPolicy.inspectLive(
                        database.loadKeepItems(), admitted);
                if (admission == null || !admission.accepted()) {
                    return "unassigned inventory remains outside the audited cargo and keep policy"
                            + (admission == null ? "" : ": " + admission.unexpectedSlots());
                }
            }
            int auditedQuantity = snapshot.currentQuantity();
            if (partialBorrowedAppleAudit) {
                auditedQuantity = pinned.values().stream().mapToInt(Integer::intValue).sum();
                if (auditedQuantity < 2 || auditedQuantity > snapshot.currentQuantity()) {
                    return "partial apple cargo exceeds the saved batch";
                }
                ItemStack exemplar = null;
                for (int raw : pinned.keySet()) {
                    ItemStack stack = player.getItemStack(raw);
                    if (stack == null || !"enchanted_golden_apple".equals(
                            ItemIdentifier.getItemId(stack))) {
                        return "pinned slot is not a live enchanted golden apple stack";
                    }
                    if (exemplar != null
                            && !KitAppleExchangePlan.samePhysicalStack(exemplar, stack)) {
                        return "partial apple cargo spans different named or regular variants";
                    }
                    exemplar = stack;
                }
                for (int raw = 9; raw <= 44; raw++) {
                    if (pinned.containsKey(raw)) continue;
                    ItemStack stack = player.getItemStack(raw);
                    if (stack != null && exemplar != null
                            && KitAppleExchangePlan.samePhysicalStack(exemplar, stack)) {
                        return "same-variant apples remain outside the pinned cargo slots";
                    }
                }
            }
            var audited = new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), auditedQuantity,
                    ownedShellSlot, snapshot.exportPosition(), null, null,
                    snapshot.borrowedSourceItemId(), snapshot.borrowedSourceContents(),
                    keptAppleAudit ? "resume_audited_kept_apple_cargo"
                            : "resume_audited_inventory_cargo",
                    System.currentTimeMillis(), "WAIT_CARGO",
                    System.currentTimeMillis()
                            + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L,
                    snapshot.sourceBoxesToReturn(), -1, snapshot.writtenOffSources(), pinned,
                    snapshot.incompleteBoxes(), snapshot.missingTotals(),
                    snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots());
            if (!KitFulfillmentCoordinator.verifiedAuditedCargo(audited)) {
                return "pinned slots do not contain the exact item and counts; use /stash kit cargo";
            }
            if (changedBaselineAudit
                    && !KitFulfillmentCoordinator.verifiedPendingSourceInventory(audited)) {
                return "borrowed source identities changed before the audit was saved";
            }
            if (keptAppleAudit && !KitFulfillmentCoordinator.auditedAppleFitsSlots(audited)) {
                return "selected apples do not fit the recipe's physical apple slots";
            }
            kitJournal.save(audited);
            kitResumeCadence.reset();
            debugRecorder.record("kit_batch_cargo_audited", "kit=" + snapshot.kitName()
                    + ", item=" + snapshot.currentItem() + ", slots=" + pinned);
            return null;
        } catch (NumberFormatException e) {
            return "use numeric slot:count pairs, for example 26:64 33:64";
        } catch (Exception e) {
            return "could not audit batch cargo: " + e.getMessage();
        }
    }

    /** An operator-selected empty shell is pinned only after a complete live inventory audit. */
    private @Nullable String scheduleKitEmptyShellOwnershipAudit(
            KitFulfillmentJournalStore.Snapshot snapshot, String evidence) throws IOException {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                || state != ScanState.IDLE || organizer != null && organizer.isActive()
                || INVENTORY.hasActiveRequest()) {
            return "bot must be online, idle, and free of client control";
        }
        var cache = CACHE.getPlayerCache().getInventoryCache();
        var cursor = cache.getMouseStack();
        Container player = cache.getPlayerInventory();
        if (player == null || cache.getOpenContainerId() > 0
                || cursor != null && cursor.getAmount() > 0) {
            return "close the GUI and clear the cursor before auditing empty shell ownership";
        }
        String[] tokens = evidence == null ? new String[0] : evidence.trim().split("\\s+");
        if (tokens.length < 3 || !tokens[0].startsWith("owned=")
                || !tokens[1].startsWith("borrowed=")) {
            return "use owned=<raw> borrowed=<raw> followed by task slot:count pairs";
        }
        int ownedRaw = Integer.parseInt(tokens[0].substring(6));
        int borrowedRaw = Integer.parseInt(tokens[1].substring(9));
        if (ownedRaw < 9 || ownedRaw > 44 || borrowedRaw < 9 || borrowedRaw > 44
                || ownedRaw == borrowedRaw) return "invalid distinct empty-shell slots";
        ItemStack owned = player.getItemStack(ownedRaw);
        ItemStack borrowed = player.getItemStack(borrowedRaw);
        String marker = KitEmptyShellOwnershipAudit.marker(
                ownedRaw, borrowedRaw, owned, borrowed);
        if (marker == null) return "empty-shell fingerprints are missing or indistinguishable";
        Map<Integer, Integer> pinned = new LinkedHashMap<>();
        for (int i = 2; i < tokens.length; i++) {
            String[] pair = tokens[i].split(":", -1);
            if (pair.length != 2) return "invalid task slot:count pair";
            int raw = Integer.parseInt(pair[0]);
            int count = Integer.parseInt(pair[1]);
            if (raw < 9 || raw > 44 || raw == ownedRaw || raw == borrowedRaw
                    || count <= 0 || pinned.putIfAbsent(raw, count) != null) {
                return "invalid or duplicate task cargo slot";
            }
        }
        if (pinned.values().stream().mapToInt(Integer::intValue).sum()
                != snapshot.currentQuantity()) return "pinned task cargo total differs from checkpoint";
        String physicalItem = null;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0
                    || !snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) continue;
            String variant = KitVariantFingerprint.canonicalVariant(stack);
            if (physicalItem == null) physicalItem = variant;
            else if (!physicalItem.equals(variant))
                return "current item has mixed physical variants in bot inventory";
        }
        var audited = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                ownedRaw, snapshot.exportPosition(), null, null,
                snapshot.borrowedSourceItemId(), snapshot.borrowedSourceContents(),
                marker, System.currentTimeMillis(), "WAIT_CARGO",
                System.currentTimeMillis()
                        + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L,
                snapshot.sourceBoxesToReturn(), -1, snapshot.writtenOffSources(), pinned,
                snapshot.incompleteBoxes(), snapshot.missingTotals(),
                snapshot.currentBoxMissing())
                .withDisplacedAppleSlots(snapshot.displacedAppleSlots());
        if (!KitEmptyShellOwnershipAudit.verify(audited, player)) {
            return "live named and unnamed empty boxes do not match the complete checkpoint ledger";
        }
        if (!KitFulfillmentCoordinator.verifiedAuditedCargo(audited)) {
            return "pinned task slots do not contain the exact item and counts";
        }
        kitJournal.save(audited);
        kitResumeCadence.reset();
        debugRecorder.record("kit_empty_shell_ownership_audited",
                "owned_raw=" + ownedRaw + ", borrowed_raw=" + borrowedRaw
                        + ", cargo_slots=" + pinned.keySet());
        return null;
    }

    public @Nullable String discardKitFulfillmentCheckpoint() {
        if (kitFulfillment.isActive() || kitRecovery.isActive() || retriever.isActive()
                || organizer == null || organizer.isActive() || organizer.hasDurableCheckpoint()
                || INVENTORY.hasActiveRequest()) {
            return "kit movement or recovery is active";
        }
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null) return "no kit checkpoint exists";
            if (snapshot.borrowedSourceWorksite() != null || snapshot.kitWorksite() != null) {
                return "a journaled shulker worksite is unresolved; recover or inspect it first";
            }
            if (snapshot.ownedShellSlot() >= 9 && snapshot.ownedShellSlot() <= 44) {
                if (!"kit_retirement_inventory_staging".equals(snapshot.blocker())) {
                    return "stage the owned shell through /stash kit retire stage first";
                }
                if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
                    return "bot must be online and free of client control for retirement audit";
                }
                var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
                ItemStack cursor = inventoryCache.getMouseStack();
                if (inventoryCache.getOpenContainerId() > 0
                        || (cursor != null && cursor.getAmount() > 0)) {
                    return "close the container and clear the cursor before retirement";
                }
                if (KitFulfillmentCoordinator.uniqueOwnedShellSlot(snapshot) >= 0) {
                    return "the owned kit shell remains in inventory";
                }
                var admission = KitInventoryAdmissionPolicy.inspectLive(database.loadKeepItems(),
                        Set.of());
                if (admission == null || !admission.accepted()) {
                    return "inventory still contains non-kept cargo; finish staging before retirement";
                }
            }
            kitJournal.clear();
            debugRecorder.record("kit_checkpoint_retired", "kit=" + snapshot.kitName()
                    + ", completed=" + snapshot.completedBoxes()
                    + ", requested=" + snapshot.requestedBoxes());
            return null;
        } catch (Exception e) {
            return "cannot clear kit checkpoint: " + e.getMessage();
        }
    }

    private void armRestoredKitCheckpoint() {
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || "BLOCKED".equals(snapshot.state())) return;
            if ("COMPLETE".equals(snapshot.state())) return;
            long due = System.currentTimeMillis()
                    + Math.max(1L, config.scanPreemptionCooldownSeconds) * 1000L;
            kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), snapshot.kitWorksite(),
                    snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), "process_restarted", System.currentTimeMillis(),
                    snapshot.state(), due, snapshot.sourceBoxesToReturn(),
                    snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                    snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                    snapshot.missingTotals(), snapshot.currentBoxMissing())
                    .withDisplacedAppleSlots(snapshot.displacedAppleSlots())
                    .withRetainedShell(snapshot.retainedShell()));
            debugRecorder.record("kit_resume_scheduled",
                    "reason=process_restarted, phase=" + snapshot.state()
                            + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
        } catch (Exception e) {
            warn("Kit checkpoint cannot be scheduled after restart: {}", e.getMessage());
        }
    }

    private boolean cancelKitScheduledResume() {
        try {
            var snapshot = kitJournal.load().orElse(null);
            if (snapshot == null || snapshot.resumeState() == null
                    || snapshot.resumeNotBeforeEpochMilli() == 0) return false;
            saveKitResumeReview(snapshot, "manual_stop", false);
            return true;
        } catch (Exception e) {
            warn("Could not cancel kit resume schedule: {}", e.getMessage());
            return false;
        }
    }

    private void saveKitResumeReview(KitFulfillmentJournalStore.Snapshot snapshot,
                                     String reason, boolean notify) throws IOException {
        kitJournal.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                snapshot.ownedShellSlot(), snapshot.exportPosition(), snapshot.kitWorksite(),
                snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                snapshot.borrowedSourceContents(), reason, System.currentTimeMillis(),
                snapshot.resumeState(), 0, snapshot.sourceBoxesToReturn(),
                snapshot.auditedCargoSlot(), snapshot.writtenOffSources(),
                snapshot.auditedCargoSlots(), snapshot.incompleteBoxes(),
                snapshot.missingTotals(), snapshot.currentBoxMissing())
                .withDisplacedAppleSlots(snapshot.displacedAppleSlots())
                .withRetainedShell(snapshot.retainedShell()));
        kitResumeCadence.reset();
        debugRecorder.record("kit_resume_manual_review", "kit=" + snapshot.kitName()
                + ", reason=" + reason + ", completed_boxes=" + snapshot.completedBoxes());
        if (notify) handleAutomationEvent("kit_fulfillment_blocked", Map.of(
                "kit", snapshot.kitName(), "reason", "resume_manual_review:" + reason,
                "completed_boxes", snapshot.completedBoxes()));
    }

    public DebugRecorder getDebugRecorder() {
        return debugRecorder;
    }

    public DebugRecorder getGeometryRecorder() {
        return geometryRecorder;
    }

    public PoseTraceRecorder getPoseTraceRecorder() {
        return poseTraceRecorder;
    }

    private void recordPoseTrace(String stage, String context) {
        if (!poseTraceRecorder.isEnabled()) return;
        try {
            var player = CACHE.getPlayerCache();
            var feet = PlayerContext.INSTANCE.playerFeet();
            var velocity = BOT.getVelocity();
            var inputs = BARITONE.getInputOverrideHandler();
            String movementDetail = String.format(Locale.ROOT,
                    "nav_feet=%d,%d,%d, on_ground=%s, velocity=%.3f,%.3f,%.3f, "
                            + "path_active=%s, forced_inputs=F%s/B%s/L%s/R%s/J%s/Sneak%s/Sprint%s, "
                            + "feet_block=%s, below=%s, above=%s",
                    feet.x(), feet.y(), feet.z(), BOT.isOnGround(),
                    velocity.getX(), velocity.getY(), velocity.getZ(), BARITONE.isActive(),
                    inputs.isInputForcedDown(PathInput.MOVE_FORWARD),
                    inputs.isInputForcedDown(PathInput.MOVE_BACK),
                    inputs.isInputForcedDown(PathInput.MOVE_LEFT),
                    inputs.isInputForcedDown(PathInput.MOVE_RIGHT),
                    inputs.isInputForcedDown(PathInput.JUMP),
                    inputs.isInputForcedDown(PathInput.SNEAK),
                    inputs.isInputForcedDown(PathInput.SPRINT),
                    WorldGeometrySnapshot.describeBlock(feet.x(), feet.y(), feet.z()),
                    WorldGeometrySnapshot.describeBlock(feet.x(), feet.y() - 1, feet.z()),
                    WorldGeometrySnapshot.describeBlock(feet.x(), feet.y() + 1, feet.z()));
            poseTraceRecorder.record(stage, context,
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(), BOT.getYaw(), BOT.getPitch(), movementDetail);
        } catch (RuntimeException e) {
            // Diagnostics must never change the outcome of a cargo-carrying job.
            debugRecorder.record("pose_trace_sample_unavailable", e.getClass().getSimpleName());
        }
    }

    private void sampleAutomationPose() {
        if (!poseTraceRecorder.isEnabled()) return;
        // Keep every pathing tick so a brief ladder exit cannot fall between samples.
        // Idle/interaction states still use the lower-rate baseline sample.
        if (++poseTraceTicks % POSE_TRACE_SAMPLE_TICKS != 0 && !BARITONE.isActive()) return;
        if (organizer != null && organizer.isActive()) {
            recordPoseTrace("organize_pose", "state=" + organizer.getState());
        } else if (state != ScanState.IDLE && state != ScanState.DONE) {
            recordPoseTrace("scan_pose", "state=" + state + ", target=" + currentContainerPos()
                    + ", click_target=" + scanOpenClickTarget
                    + ", open_wait_ticks=" + openTimeoutCounter);
        }
    }

    @Override
    public boolean enabledSetting() {
        return config.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientTickEvent.class, this::onClientTick),
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onTickStarting),
            of(ClientBotTick.Stopped.class, this::onTickStopped),
            of(PlayerLoginEvent.Post.class, this::onControllingPlayerLogin),
            of(HealthAutoDisconnectEvent.class, this::onHealthAutoDisconnect),
            of(ClientDisconnectEvent.class, this::onClientDisconnect),
            of(ClientDeathEvent.class, this::onClientDeath),
            of(AutoReconnectEvent.class, this::onAutoReconnectScheduled),
            of(ClientStartConnectEvent.class, this::onClientStartConnect),
            of(ClientConnectEvent.class, this::onClientConnect),
            of(ClientLoginFailedEvent.class, this::onClientLoginFailed),
            of(ClientOnlineEvent.class, this::onClientOnline),
            of(AutoEatOutOfFoodEvent.class, this::onAutoEatOutOfFood)
        );
    }

    @Override
    public @Nullable PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("stash-manager")
            .setPriority(1)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundContainerSetContentPacket.class, (packet, session) -> {
                    if (state == ScanState.OPENING || state == ScanState.READING) {
                        containerDataReceived = true;
                        debug("Received container data packet (windowId={})", packet.getContainerId());
                    } else if ((state == ScanState.CLOSING || state == ScanState.APPROACHING
                            || (state == ScanState.YIELDED && lateOpenQuarantineTicks > 0))
                            && packet.getContainerId() > 0) {
                        // A response can arrive after its open attempt timed out. It no longer
                        // owns scanner state, so quarantine it instead of leaving a stale GUI
                        // open over the next target.
                        session.send(new ServerboundContainerClosePacket(packet.getContainerId()));
                        debugRecorder.record("scan_late_container_closed",
                            "container_id=" + packet.getContainerId() + ", target=" + currentContainerPos());
                    }
                    // Forward container data to organizer
                    if (organizer != null && organizer.isActive()) {
                        organizer.onContainerData(session, packet);
                    }
                    if (retriever.isActive()) {
                        retriever.onContainerData(session, packet);
                    }
                    if (kitFulfillment.isActive()) {
                        kitFulfillment.onContainerData(packet.getContainerId());
                    }
                    if (kitRecovery.isActive()) {
                        kitRecovery.onContainerData(packet.getContainerId());
                    }
                    return packet;
                })
                // Priority 1 runs before Zenith's priority-0 entity-cache handler removes the
                // collected item. This gives the organizer definitive pickup evidence even if
                // the following inventory-slot update is delayed or coalesced.
                .inbound(ClientboundTakeItemEntityPacket.class, (packet, session) -> {
                    if (organizer != null && organizer.isActive()) {
                        organizer.onItemCollected(packet);
                    }
                    return packet;
                })
                .build())
            .build();
    }

    @Override
    public void onEnable() {
        // Hydrate in-memory index from database
        if (database != null && database.isInitialized() && index.size() == 0) {
            int loaded = index.loadFromDatabase();
            if (loaded > 0) {
                info("Loaded {} containers from database", loaded);
            }
        }
        refreshLatestScanTrust();
        if (organizer != null) {
            StashOrganizer.DurableRestoreResult restored = organizer.restoreDurableCheckpoint();
            if (restored == StashOrganizer.DurableRestoreResult.RESTORED) {
                refreshLatestScanTrust();
                organizerPreemptionGate = newOrganizerPreemptionGate();
                organizerPreemptionGate.yield();
                organizerPreemptionCount = 1;
                organizerPickupRecoveryDeferred = false;
                lastOrganizerRecoveryBlocker = null;
                requestFoodContingency("organize_checkpoint_restored");
                info("Organizer restart checkpoint armed; resume will wait for the configured cooldown and a quiet automation window");
            } else if (restored == StashOrganizer.DurableRestoreResult.INVALID) {
                warn("Organizer restart checkpoint needs attention: {}",
                        organizer.getDurableRecoveryError());
            }
        }
        armRestoredKitCheckpoint();
        if (activeResumableJob() != JobContinuanceManager.Job.NONE
                && !Proxy.getInstance().isConnected()) {
            beginConnectionOutage("module_enabled_while_disconnected", false, "module_enable");
        }
        info("StashManager module enabled");
    }

    @Override
    public void onDisable() {
        if (kitFulfillment.isActive()) {
            kitFulfillment.interruptForRecovery("module_disabled",
                    Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        resetKeepReequip();
        jobContinuanceManager.clear();
        connectionRecoveryTracker.reset();
        controllingPlayerName = null;
        if (organizer != null && organizer.isActive()) {
            organizer.prepareForProcessShutdown("module_disabled");
            organizerPreemptionGate.reset();
        }
        if (retriever.isActive()) {
            retriever.stop();
        }
        restoreAutoEatWarning();
        tunnelNetworkSyncWorker.stop();
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            info("StashManager module disabled — aborting scan");
            abortScan("module_disabled", false);
        }
        info("StashManager module disabled");
    }

    // Public API
    public ScanState getState() {
        return state;
    }

    public int getContainersFound() {
        return containersFound;
    }

    public int getContainersIndexed() {
        return containersIndexed;
    }

    public int getContainersFailed() {
        return containersFailed;
    }

    public int getScanPreemptionCount() {
        return scanPreemptionCount;
    }

    public int getScanPreemptionCooldownRemainingSeconds() {
        return (scannerPreemptionGate.remainingHoldTicks() + 19) / 20;
    }

    public int getOrganizerPreemptionCount() {
        return organizerPreemptionCount;
    }

    public int getOrganizerPreemptionCooldownRemainingSeconds() {
        return (organizerPreemptionGate.remainingHoldTicks() + 19) / 20;
    }

    public int getProxyControlGraceRemainingSeconds() {
        return jobContinuanceManager.remainingSeconds(System.nanoTime());
    }

    public JobContinuanceManager.Job getControlledJob() {
        return jobContinuanceManager.job();
    }

    public boolean isConnectionRecoveryPending() {
        return connectionRecoveryTracker.isPending();
    }

    public ConnectionRecoveryTracker.Phase getConnectionRecoveryPhase() {
        return connectionRecoveryTracker.phase();
    }

    public int getConnectionOutageCount() {
        return connectionRecoveryTracker.outageCount();
    }

    public int getConnectionRecoveryCount() {
        return connectionRecoveryTracker.recoveryCount();
    }

    public long getConnectionOutageElapsedSeconds() {
        return connectionRecoveryTracker.elapsedSeconds(System.nanoTime());
    }

    public String getLastConnectionOutageReason() {
        return connectionRecoveryTracker.reason();
    }

    public int getPendingCount() {
        return Math.max(0, containersFound - getProcessedCount());
    }

    public int getProcessedCount() {
        return containersIndexed + containersFailed;
    }

    public double getProcessedRatio() {
        if (containersFound <= 0) return 0.0;
        return (double) getProcessedCount() / containersFound;
    }

    public double getSuccessRate() {
        if (containersFound <= 0) return 0.0;
        return (double) containersIndexed / containersFound;
    }

    public double getFailureRate() {
        if (containersFound <= 0) return 0.0;
        return (double) containersFailed / containersFound;
    }

    public @Nullable String getRetrieveBlocker() {
        if (kitJournal.exists()) return "kit fulfillment checkpoint requires recovery or manual review";
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            return "scan is active (state=" + state + ")";
        }
        if (organizer != null && organizer.isActive()) {
            return "organizer is active";
        }
        if (retriever.isActive()) {
            return "retriever is already active";
        }
        if (database == null || !database.isInitialized()) {
            return "database not connected";
        }
        return getAutomationUnavailableReason();
    }

    public @Nullable String getOrganizerBlocker() {
        if (kitJournal.exists()) return "kit fulfillment checkpoint requires recovery or manual review";
        if (organizer == null) {
            return config.organizerEnabled ? "organizer not available" : "organizer is disabled in config";
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            return "scan is active (state=" + state + ")";
        }
        if (organizer.isActive()) {
            return "organizer is already active";
        }
        if (organizer.hasDurableCheckpoint()) {
            return organizer.getDurableRecoveryError() == null
                    ? "a saved organizer checkpoint exists; use /stash organize resume or /stash organize discard confirm"
                    : "a saved organizer checkpoint is invalid; use /stash organize discard confirm ("
                            + organizer.getDurableRecoveryError() + ")";
        }
        if (retriever.isActive()) {
            return "retriever is active";
        }
        if (database == null || !database.isInitialized()) {
            return "database not connected";
        }
        refreshLatestScanTrust();
        if (!latestScanTrusted) {
            return "the latest scan is incomplete or unhealthy; run a fresh /stash scan before organizing";
        }
        String automationBlocker = getAutomationUnavailableReason();
        if (automationBlocker != null) return automationBlocker;
        if (isBotOutsideScanEnvelope() && !organizer.isWithinPinnedIntakeEnvelope(
                CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(),
                CACHE.getPlayerCache().getZ(), SCAN_HORIZONTAL_RECOVERY_MARGIN,
                SCAN_VERTICAL_RECOVERY_MARGIN)) {
            return "bot is outside the configured stash area; return it near the scanned region before organizing";
        }

        LaneCapacityReport capacity = organizer.calculateLaneCapacity();
        boolean importStagingAvailable = hasImportStagingInventory();
        boolean shortageCanStage = capacity.canOrganizeWithImportStaging(importStagingAvailable);
        return switch (capacity.status()) {
            case READY -> capacity.mixedShulkers() > 0 && !importStagingAvailable
                    ? capacity.mixedShulkers()
                            + " mixed shulker(s) require a registered import chest for safe decomposition staging"
                    : null;
            case INSUFFICIENT_LANES -> shortageCanStage
                    ? null
                    : "lane capacity is short by " + capacity.laneShortfall()
                            + " dedicated lane(s); register an import chest for temporary staging or run /stash lanes for details";
            case INSUFFICIENT_LANE_STORAGE -> shortageCanStage
                    ? null
                    : capacity.laneStorage().unassigned().size()
                            + " bulk class(es) do not fit any assignable lane; register an import chest for temporary staging or run /stash lanes for details";
            case NEEDS_FRESH_SCAN -> "lane capacity data contains "
                    + capacity.unclassifiedShulkers() + " unclassified shulker(s); run a fresh /stash scan";
            case NEEDS_FRESH_CONTAINER_SCAN -> "double-chest physical footprints require a fresh /stash scan";
            case REGION_NOT_DEFINED -> "region not defined (set pos1 and pos2 first)";
            case NO_STORAGE_REGION -> "no all-purpose or storage region is available";
            case MULTIPLE_STORAGE_REGIONS -> "multiple storage regions exist; choose one explicitly";
            case NO_SCANNED_CONTAINERS -> "no scanned containers in the configured region";
            case NO_LANES_DETECTED -> shortageCanStage
                    ? null
                    : "no storage lanes detected in the configured region; register an import chest for temporary staging";
        };
    }

    private boolean hasImportStagingInventory() {
        if (config.pos1 == null || config.pos2 == null) return false;
        return organizer != null && organizer.hasScopedImportStaging();
    }

    public boolean startOrganizer() {
        String blocker = getOrganizerBlocker();
        if (blocker != null) {
            warn("Cannot start organizer: {}", blocker);
            fireWebhookEvent("organize_start_blocked", Map.of("reason", blocker));
            return false;
        }
        organizerPreemptionGate = newOrganizerPreemptionGate();
        organizerPreemptionCount = 0;
        organizerPickupRecoveryDeferred = false;
        lastOrganizerRecoveryBlocker = null;
        boolean started = organizer.start();
        if (started) requestFoodContingency("organize_started");
        return started;
    }

    /** Select a saved region before planning; keep it selected for durable resume. */
    public boolean startOrganizerForRegion(DatabaseManager.SavedRegion region) {
        if (region == null || region.pos1() == null || region.pos2() == null
                || region.pos1().length < 3 || region.pos2().length < 3
                || !region.role().isStorageCapable()) return false;
        if (region.role() == DatabaseManager.RegionRole.UNASSIGNED && database != null
                && database.isInitialized()) {
            try {
                if (database.listRegions().stream().anyMatch(saved ->
                        saved.role() == DatabaseManager.RegionRole.STORAGE)) return false;
            } catch (Exception e) {
                warn("Cannot validate storage-region policy before organizing: {}", e.getMessage());
                return false;
            }
        }
        if (organizer == null || organizer.isActive() || organizer.hasDurableCheckpoint()
                || (state != ScanState.IDLE && state != ScanState.DONE)) return false;
        if (!organizer.configureCrossRegionIntakes(List.of())) return false;
        int[] previousPos1 = config.pos1;
        int[] previousPos2 = config.pos2;
        config.pos1 = Arrays.copyOf(region.pos1(), 3);
        config.pos2 = Arrays.copyOf(region.pos2(), 3);
        if (startOrganizer()) {
            debugRecorder.record("organize_region_selected", "region=" + region.name());
            return true;
        }
        config.pos1 = previousPos1;
        config.pos2 = previousPos2;
        refreshLatestScanTrust();
        return false;
    }

    /** Route every explicitly registered intake inventory to one pinned storage region. */
    public @Nullable String startOrganizerAcrossRegions(DatabaseManager.SavedRegion storage) {
        if (database == null || !database.isInitialized()) return "database not connected";
        if (organizer == null || organizer.isActive() || organizer.hasDurableCheckpoint()
                || (state != ScanState.IDLE && state != ScanState.DONE)) {
            return "an automation or organizer checkpoint is already active";
        }
        final MultiRegionImportRoutePolicy.Selection route;
        try {
            List<DatabaseManager.SavedRegion> regions = database.listRegions();
            if (storage == null || regions.stream().noneMatch(saved ->
                    saved.name().equals(storage.name())
                            && saved.role() == DatabaseManager.RegionRole.STORAGE
                            && Arrays.equals(saved.pos1(), storage.pos1())
                            && Arrays.equals(saved.pos2(), storage.pos2()))) {
                return "selected storage region changed; reload it before organizing";
            }
            route = MultiRegionImportRoutePolicy.select(storage, regions, index);
            if (!route.ready()) return route.blocker();
            for (StashOrganizer.IntakeRegion intake : route.intakes()) {
                var scan = database.getLatestScanSummary(intake.pos1(), intake.pos2()).orElse(null);
                if (scan == null || !scan.completed()
                        || !ScanCompletionPolicy.trustedSnapshot(
                                scan.found(), scan.indexed(), scan.failed())) {
                    return "intake " + intake.name() + " needs a complete, fully indexed scan";
                }
                for (int[] position : intake.importBlocks()) {
                    ContainerEntry entry = index.get(position[0], position[1], position[2]);
                    if (entry == null || entry.timestamp() < scan.startedAtEpochMilli()) {
                        return "intake " + intake.name() + " has a stale import at "
                                + position[0] + "," + position[1] + "," + position[2];
                    }
                }
            }
        } catch (Exception e) {
            return "could not verify intake routes: " + e.getMessage();
        }
        int[] previousPos1 = config.pos1;
        int[] previousPos2 = config.pos2;
        config.pos1 = Arrays.copyOf(storage.pos1(), 3);
        config.pos2 = Arrays.copyOf(storage.pos2(), 3);
        if (!organizer.configureCrossRegionIntakes(route.intakes())) {
            config.pos1 = previousPos1;
            config.pos2 = previousPos2;
            return "organizer route could not be pinned";
        }
        String blocker = getOrganizerBlocker();
        if (blocker == null && startOrganizer()) {
            debugRecorder.record("organize_multi_region_selected",
                    "storage=" + storage.name() + ", intakes=" + route.intakes().size()
                            + ", import_blocks=" + route.importBlocks());
            return null;
        }
        organizer.configureCrossRegionIntakes(List.of());
        config.pos1 = previousPos1;
        config.pos2 = previousPos2;
        refreshLatestScanTrust();
        return blocker == null ? "organizer could not start; see console for the exact reason" : blocker;
    }

    /** Cancel organizer work and retire any cooldown gate owned by that job. */
    public boolean stopOrganizer() {
        if (organizer == null || !organizer.isActive()) return false;
        if (foodContingencyJob == JobContinuanceManager.Job.ORGANIZE) {
            resetFoodContingency();
        }
        organizer.stop();
        if (!organizer.isYielded()) {
            organizerPreemptionGate.reset();
            organizerPickupRecoveryDeferred = false;
            lastOrganizerRecoveryBlocker = null;
        }
        if (!organizer.isActive()) {
            clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE, "manual_stop");
            connectionRecoveryTracker.reset();
        }
        return true;
    }

    /** Arm (or leave armed) a restored organizer checkpoint for normal gated resume. */
    public boolean requestOrganizerCheckpointResume() {
        return requestOrganizerCheckpointResume(0);
    }

    /** Operator-attested count is checked again in the server's destination window. */
    public boolean requestOrganizerCheckpointResume(int expectedPackedBoxCount) {
        if (organizer == null) return false;
        if (!organizer.isDurableRecoveryLoaded()) {
            StashOrganizer.DurableRestoreResult restored = organizer.restoreDurableCheckpoint();
            if (restored != StashOrganizer.DurableRestoreResult.RESTORED) return false;
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionGate.yield();
            lastOrganizerRecoveryBlocker = null;
        }
        refreshLatestScanTrust();
        if (!organizer.isYielded()) return false;
        String resumeBlocker = getOrganizerCheckpointResumeBlocker();
        boolean attestedFoodGate = foodContingencyJob == JobContinuanceManager.Job.ORGANIZE
                && FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                        expectedPackedBoxCount, resumeBlocker, foodContingencyBlocked,
                        foodContingencyTerminalReason, foodContingencyBlockedRetryCount);
        if (resumeBlocker != null
                && (!attestedFoodGate || organizer.getDurableResumeBlocker() != null)) return false;
        if (!organizer.prepareManualCheckpointResume(expectedPackedBoxCount)) return false;
        organizer.emitPackedShulkerInventoryAudit();
        if (!organizerPreemptionGate.isYielded()) {
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionGate.yield();
        }
        // The verified box is no longer an unresolved world marker. Re-evaluate food from
        // live inventory; the old blocked result must not indefinitely prevent this resume.
        if (attestedFoodGate) resetFoodContingency();
        requestFoodContingency("organize_checkpoint_resume");
        return true;
    }

    public @Nullable String getOrganizerCheckpointResumeBlocker() {
        if (organizer != null && organizer.isInventoryRecoveryOnly()) {
            try {
                var snapshot = kitJournal.load().orElse(null);
                if (!kitAllowsInventoryReconciliation(snapshot)) {
                    return "kit_owned_shell_or_checkpoint_changed";
                }
            } catch (Exception e) {
                return "kit_checkpoint_unavailable_for_inventory_reconciliation";
            }
        } else if (!latestScanTrusted) {
            return "latest_scan_incomplete_or_unhealthy";
        }
        if (foodContingencyJob == JobContinuanceManager.Job.ORGANIZE
                && foodContingencyBlocked) {
            if (foodContingencyBlockedRetryCount >= FoodContingencyRetryPolicy.MAX_RETRIES) {
                return "food refill failed after " + foodContingencyBlockedRetryCount
                        + " retries (" + foodContingencyTerminalReason
                        + "); manual help is needed";
            }
            return "food refill is blocked (" + foodContingencyTerminalReason
                    + "); it will retry when the bot is back near the stash";
        }
        return organizer == null ? "organizer_unavailable" : organizer.getDurableResumeBlocker();
    }

    private boolean kitAllowsInventoryReconciliation(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (organizer == null || !organizer.isInventoryRecoveryOnly()
                || !KitInventoryReconciliationBoundary.matchesActive(
                        snapshot, organizer.inventoryRecoveryPinnedSlots())) return false;
        return snapshot == null || !KitResumePolicy.canAuditLostBorrowedNoCargo(snapshot)
                || kitFulfillment.hasVerifiedOwnedShell(snapshot);
    }

    public boolean discardOrganizerCheckpoint() {
        if (organizer == null || (organizer.isActive() && !organizer.isYielded())) return false;
        boolean discarded = organizer.discardDurableCheckpoint("manual_discard");
        if (discarded) {
            organizerPreemptionGate.reset();
            organizerPickupRecoveryDeferred = false;
            lastOrganizerRecoveryBlocker = null;
            clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE,
                    "organize_checkpoint_discarded");
        }
        return discarded;
    }

    public boolean startKitRetrieval(String requestName, Map<String, Integer> kitItems) {
        return startKitRetrieval(requestName, kitItems, false);
    }

    /** Start one serial, checkpointed physical kit delivery job from a trusted scan. */
    public @Nullable String startKitFulfillment(String kitName, int boxes) {
        String blocker = getRetrieveBlocker();
        if (blocker != null) return blocker;
        if (foodContingencyActive || foodContingencyRequested || reequipActive) {
            return "food recovery or re-equipping is active";
        }
        if (hasAnyBaritoneProcessActive() || BARITONE.isActive() || INVENTORY.hasActiveRequest()) {
            return "shared Baritone/inventory automation is active";
        }
        if (boxes < 1 || boxes > 64) return "request between 1 and 64 boxes";
        refreshLatestScanTrust();
        if (config.pos1 == null || config.pos2 == null) return "stash region is not configured";
        final java.util.Optional<DatabaseManager.ScanSummary> scan;
        try {
            scan = database.getLatestScanSummary(config.pos1, config.pos2);
        } catch (Exception e) {
            return "could not load latest scan: " + e.getMessage();
        }
        if (scan.isEmpty()) return "run a fresh stash scan first";
        var summary = scan.get();
        if (!KitScanAdmission.allows(summary.completionStatus(), summary.found(),
                summary.indexed(), summary.failed())) {
            return "latest scan is incomplete or has more than one failed chest";
        }
        boolean oneTargetPartial = !summary.completed();
        if (isBotOutsideScanEnvelope()) return "bot is outside the scanned stash area";
        int foodNeeded = Math.min(64, 16 * boxes);
        if (CACHE.getPlayerCache().getThePlayer().getFood() < 14
                || kitSafeFoodCount() < foodNeeded) {
            return "kit build needs hunger at least 14 and " + foodNeeded
                    + " safe, AutoEat-accepted food items in inventory";
        }
        try {
            DatabaseManager.KitDefinition kit = database.loadKitDefinition(kitName);
            if (kit == null || kit.status() != DatabaseManager.KitStatus.READY
                    || kit.items().isEmpty() || kit.exportPosition() == null) {
                return "kit is missing, incomplete, or has no export chest";
            }
            if (kit.items().keySet().stream().anyMatch(id -> id.startsWith("profile:"))) {
                return "physical kit packing needs exact scanned item IDs; profile-based recipes "
                        + "are not yet supported";
            }
            // Cargo is retrieved and packed in bounded batches, not all at once.
            // The second main slot lets an empty borrowed shell be retained
            // without immediately consuming the sole cargo slot.
            int requiredMainSlots = 2;
            int freeMainSlots = KitFulfillmentCoordinator.freeMainSlots();
            int freeHotbarSlots = KitFulfillmentCoordinator.freeHotbarSlots();
            if (freeMainSlots < requiredMainSlots || freeHotbarSlots < 1) {
                return "kit needs " + requiredMainSlots + " free main slots and one free hotbar slot "
                        + "(currently " + freeMainSlots + " main, " + freeHotbarSlots
                        + " hotbar); keep items will not be moved";
            }
            int[] output = kit.exportPosition();
            if (!index.isExportChest(output[0], output[1], output[2])) {
                return "kit output is no longer a registered export chest";
            }
            ContainerEntry exportEntry = index.get(output[0], output[1], output[2]);
            if (exportEntry == null || !exportEntry.inventoryFootprintKnown()
                    || exportEntry.timestamp() < summary.startedAtEpochMilli()) {
                return "export chest needs a fresh physical scan";
            }
            int chestSlots = exportEntry.isDouble() ? 54 : 27;
            int occupied = exportEntry.shulkerCount()
                    + exportEntry.directStackSlots().values().stream().mapToInt(Integer::intValue).sum();
            int freeExportSlots = Math.max(0, chestSlots - occupied);

            Set<Long> excluded = new HashSet<>(getReservedContainerKeys());
            List<ContainerEntry> candidates = database.getAllContainers().stream()
                    .filter(this::inConfiguredRegion)
                    .filter(ContainerEntry::inventoryFootprintKnown)
                    .filter(entry -> entry.timestamp() >= summary.startedAtEpochMilli())
                    .toList();
            Set<Long> reservedInventories = new HashSet<>();
            reservedInventories.add(exportEntry.inventoryKey());
            for (ContainerEntry entry : candidates) {
                if (excluded.contains(entry.posKey()) || index.isBlacklistedChest(entry)
                        || index.isExportChest(entry)) {
                    reservedInventories.add(entry.inventoryKey());
                }
            }
            for (ContainerEntry entry : candidates) {
                if (reservedInventories.contains(entry.inventoryKey())) excluded.add(entry.posKey());
            }
            Map<Long, ContainerEntry> physical = new java.util.LinkedHashMap<>();
            for (ContainerEntry entry : candidates) {
                if (inConfiguredRegion(entry) && entry.inventoryFootprintKnown()
                        && !excluded.contains(entry.posKey())
                        && !index.isBlacklistedChest(entry) && !index.isExportChest(entry)) {
                    physical.putIfAbsent(entry.inventoryKey(), entry);
                }
            }
            Map<String, Long> stock = new java.util.LinkedHashMap<>();
            int emptyBoxes = 0;
            for (ContainerEntry entry : physical.values()) {
                entry.items().forEach((id, amount) -> stock.merge(id, amount.longValue(), Long::sum));
                for (ContainerEntry.ShulkerDetail detail : entry.shulkerDetails()) {
                    if (detail.isPhysicalInstance() && detail.items().isEmpty()) emptyBoxes++;
                }
            }
            if (kit.craftMissing()) {
                KitCraftingPlan.Assessment craft = KitCraftingPlan.assess(
                        kit.items(), boxes, stock, true);
                if (!craft.feasible()) {
                    return "kit crafting ingredients unavailable: " + craft.shortages();
                }
                if (!craft.toCraft().isEmpty()) {
                    boolean hasAssignedTable = index.getCraftingTables().stream().anyMatch(pos ->
                            pos[0] >= Math.min(config.pos1[0], config.pos2[0])
                                    && pos[0] <= Math.max(config.pos1[0], config.pos2[0])
                                    && pos[1] >= Math.min(config.pos1[1], config.pos2[1])
                                    && pos[1] <= Math.max(config.pos1[1], config.pos2[1])
                                    && pos[2] >= Math.min(config.pos1[2], config.pos2[2])
                                    && pos[2] <= Math.max(config.pos1[2], config.pos2[2]));
                    if (!hasAssignedTable) return "kit crafting needs an assigned table in the region";
                    return "kit crafting is requested for " + craft.toCraft()
                            + " but the physical kit crafting executor is not available yet; "
                            + "no materials were moved";
                }
            }
            KitFulfillmentPlan.Assessment capacity = KitFulfillmentPlan.assess(
                    kit.items(), boxes, stock, emptyBoxes, freeExportSlots);
            if (!capacity.feasible()) {
                return "kit stock/capacity preflight failed: missingItems=" + capacity.shortages()
                        + ", emptyShulkersNeeded=" + capacity.missingEmptyBoxes()
                        + ", exportSlotsNeeded=" + capacity.missingExportSlots();
            }
            Map<String, Integer> keepRules = database.loadKeepItems();
            var inventoryAdmission = KitInventoryAdmissionPolicy.inspectLive(keepRules, Set.of());
            if (inventoryAdmission == null) return "kit inventory keep-policy check unavailable";
            if (!inventoryAdmission.accepted()) {
                String disposition = kitUnexpectedInventoryDisposition(inventoryAdmission);
                debugRecorder.record("kit_inventory_admission_rejected",
                        "phase=start, " + disposition);
                return "kit inventory contains items outside the keep list in raw slots "
                        + inventoryAdmission.unexpectedSlots() + "; " + disposition;
            }
            kitFulfillment.setInventoryKeepRules(keepRules);
            if (!kitFulfillment.start(kitName, kit.items(), boxes, output,
                    candidates, config.pos1, config.pos2, excluded,
                    index.getImportChests())) {
                return "kit fulfillment could not start; check inventory headroom and checkpoint";
            }
            if (oneTargetPartial) {
                debugRecorder.record("kit_fulfillment_partial_scan_sources",
                        "found=" + summary.found() + ", indexed=" + summary.indexed()
                                + ", failed=" + summary.failed()
                                + ", fresh_candidate_entries=" + candidates.size());
            }
            return null;
        } catch (Exception e) {
            debugRecorder.record("kit_fulfillment_start_error", "kit=" + kitName, e);
            return "kit fulfillment preflight failed: " + e.getMessage();
        }
    }

    private void tickScheduledKitResume() {
        final KitFulfillmentJournalStore.Snapshot snapshot;
        try {
            snapshot = kitJournal.load().orElse(null);
        } catch (IOException e) {
            kitResumeCadence.reset();
            return;
        }
        if (snapshot == null || snapshot.resumeState() == null
                || snapshot.resumeNotBeforeEpochMilli() == 0) {
            kitResumeCadence.reset();
            return;
        }
        if (kitRecovery.state() != KitWorksiteRecovery.State.DONE) {
            kitResumeCadence.reset();
            return;
        }
        String phaseBlocker = KitResumePolicy.blocker(snapshot);
        if (phaseBlocker != null) {
            if (!"world_box_recovery_pending".equals(phaseBlocker)) {
                try { saveKitResumeReview(snapshot, phaseBlocker, true); }
                catch (IOException e) { warn("Could not record kit resume review: {}", e.getMessage()); }
            }
            return;
        }
        long now = System.currentTimeMillis();
        String quietBlocker = kitResumeQuietBlocker();
        if (quietBlocker != null && now >= snapshot.resumeNotBeforeEpochMilli()
                && now - lastKitResumeWaitDiagnosticEpochMilli >= 30_000) {
            lastKitResumeWaitDiagnosticEpochMilli = now;
            debugRecorder.record("kit_resume_waiting", "reason=" + quietBlocker
                    + ", hunger=" + CACHE.getPlayerCache().getThePlayer().getFood()
                    + ", safe_food=" + kitSafeFoodCount());
        }
        if (!kitResumeCadence.tick(now, snapshot.resumeNotBeforeEpochMilli(),
                quietBlocker == null)) return;
        String blocker = resumeScheduledKit(snapshot);
        if ("inventory_headroom_missing".equals(blocker)
                || "resume_preflight_error".equals(blocker)) {
            if (now - lastKitResumeWaitDiagnosticEpochMilli >= 30_000) {
                lastKitResumeWaitDiagnosticEpochMilli = now;
                debugRecorder.record("kit_resume_waiting", "reason=" + blocker
                        + ", kit=" + snapshot.kitName()
                        + ", phase=" + snapshot.resumeState()
                        + ", free_main=" + KitFulfillmentCoordinator.freeMainSlots()
                        + ", free_hotbar=" + KitFulfillmentCoordinator.freeHotbarSlots());
            }
            kitResumeCadence.reset();
            return;
        }
        if (blocker != null) {
            try { saveKitResumeReview(snapshot, blocker, true); }
            catch (IOException e) { warn("Could not record kit resume review: {}", e.getMessage()); }
        } else {
            kitResumeCadence.reset();
            debugRecorder.record("kit_resume_started", "kit=" + snapshot.kitName()
                    + ", phase=" + snapshot.resumeState()
                    + ", completed_boxes=" + snapshot.completedBoxes());
        }
    }

    private @Nullable String kitResumeQuietBlocker() {
        if (!Proxy.getInstance().isConnected()) return "proxy_disconnected";
        if (Proxy.getInstance().hasActivePlayer()) return "client_control_active";
        if (!CACHE.getPlayerCache().getThePlayer().isAlive()) return "bot_not_alive";
        if (state != ScanState.IDLE && state != ScanState.DONE) return "scan_active";
        if (organizer != null && organizer.isActive()) return "organizer_active";
        if (retriever.isActive()) return "retriever_active";
        if (reequipActive) return "reequip_active";
        if (foodContingencyActive || foodContingencyRequested) return "food_contingency_active";
        if (BARITONE.isActive() || hasAnyBaritoneProcessActive()) return "pathfinder_active";
        if (INVENTORY.hasActiveRequest()) return "inventory_action_active";
        if (isBotOutsideScanEnvelope()) return "outside_scan_envelope";
        if (CACHE.getPlayerCache().getThePlayer().getFood() < 14) return "hunger_low";
        if (kitSafeFoodCount() < 16) return "safe_food_low";
        return null;
    }

    private @Nullable String resumeScheduledKit(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (config.pos1 == null || config.pos2 == null) return "stash_region_missing";
        try {
            var scan = database.getLatestScanSummary(config.pos1, config.pos2).orElse(null);
            if (scan == null || !KitScanAdmission.allows(scan.completionStatus(),
                    scan.found(), scan.indexed(), scan.failed())) return "fresh_scan_required";
            var definition = database.loadKitDefinition(snapshot.kitName());
            if (definition == null || definition.status() != DatabaseManager.KitStatus.READY
                    || !snapshot.recipe().equals(definition.items())
                    || !java.util.Arrays.equals(snapshot.exportPosition(), definition.exportPosition())) {
                return "kit_recipe_or_output_changed";
            }
            int[] output = snapshot.exportPosition();
            if (!index.isExportChest(output[0], output[1], output[2])) {
                return "export_chest_unregistered";
            }
            ContainerEntry outputEntry = index.get(output[0], output[1], output[2]);
            if (outputEntry == null || !outputEntry.inventoryFootprintKnown()
                    || outputEntry.timestamp() < scan.startedAtEpochMilli()) {
                return "export_chest_scan_stale";
            }
            // This exact pre-retrieval headroom stop has already retained its
            // empty source shell; one main cargo slot and one free hotbar slot
            // suffice. Other fresh boundaries retain the two-slot margin.
            boolean returningSource = "resume_source_return".equals(snapshot.blocker())
                    || "resume_source_return_exact_import".equals(snapshot.blocker());
            boolean verifyingReturn = "resume_audit_completed_source_return"
                    .equals(snapshot.blocker());
            boolean verifyingFailedReturn = "resume_audit_failed_source_return"
                    .equals(snapshot.blocker());
            boolean auditedCargoResume = "resume_audited_inventory_cargo".equals(snapshot.blocker())
                    || "resume_audited_kept_apple_cargo".equals(snapshot.blocker())
                    || KitEmptyShellOwnershipAudit.isScheduled(snapshot.blocker());
            boolean auditedEmptyShell = KitEmptyShellOwnershipAudit.isScheduled(snapshot.blocker());
            boolean variantRepairResume = "resume_variant_exchange".equals(snapshot.blocker());
            boolean variantReadyResume = "resume_variant_source_cargo".equals(snapshot.blocker());
            int requiredMain = auditedCargoResume
                    || returningSource || verifyingReturn || verifyingFailedReturn ? 0
                    : variantRepairResume ? 2
                    : variantReadyResume ? 1
                    : "resume_main_headroom".equals(snapshot.blocker())
                        ? 1 : 2;
            boolean borrowedInventorySourceAlreadyInHotbar =
                    "resume_borrowed_inventory_source".equals(snapshot.blocker())
                    && KitFulfillmentCoordinator.uniqueBorrowedInventorySourceSlot(snapshot) >= 36;
            boolean auditedCargoWithHeldShell =
                    auditedCargoResume
                    && snapshot.ownedShellSlot() >= 36 && snapshot.ownedShellSlot() <= 44;
            if (KitFulfillmentCoordinator.freeMainSlots() < requiredMain
                    || (!returningSource && !verifyingReturn && !verifyingFailedReturn
                        && KitFulfillmentCoordinator.freeHotbarSlots() < 1
                        && !borrowedInventorySourceAlreadyInHotbar
                        && !auditedCargoWithHeldShell)) {
                return "inventory_headroom_missing";
            }
            Set<Long> excluded = new HashSet<>(getReservedContainerKeys());
            List<ContainerEntry> allContainers = database.getAllContainers();
            Map<Long, List<KitFulfillmentJournalStore.BorrowedBox>> lossesByInventory =
                    new HashMap<>();
            for (var loss : snapshot.writtenOffSources()) {
                int[] source = loss.sourcePosition();
                allContainers.stream()
                        .filter(entry -> entry.x() == source[0] && entry.y() == source[1]
                                && entry.z() == source[2])
                        .map(ContainerEntry::inventoryKey).distinct()
                        .forEach(key -> lossesByInventory.computeIfAbsent(
                                key, ignored -> new ArrayList<>()).add(loss));
            }
            List<ContainerEntry> candidates = allContainers.stream()
                    .filter(this::inConfiguredRegion)
                    .filter(ContainerEntry::inventoryFootprintKnown)
                    .filter(entry -> entry.timestamp() >= scan.startedAtEpochMilli())
                    .map(entry -> {
                        var losses = lossesByInventory.get(entry.inventoryKey());
                        if (losses == null || losses.isEmpty()) return entry;
                        // If the physical shulker is no longer unique in this scan,
                        // exclude this inventory rather than route to stale stock.
                        if (losses.size() != 1) return null;
                        return KitLostSourceIndexPolicy.withoutLostBox(entry, losses.getFirst(),
                                snapshot.currentItem(), snapshot.currentQuantity());
                    })
                    .filter(Objects::nonNull)
                    .toList();
            Set<Long> reservedInventories = new HashSet<>();
            reservedInventories.add(outputEntry.inventoryKey());
            for (ContainerEntry entry : candidates) {
                if (excluded.contains(entry.posKey()) || index.isBlacklistedChest(entry)
                        || index.isExportChest(entry)) {
                    reservedInventories.add(entry.inventoryKey());
                }
            }
            for (ContainerEntry entry : candidates) {
                if (reservedInventories.contains(entry.inventoryKey())) excluded.add(entry.posKey());
            }
            Set<Integer> ownedSlots = new HashSet<>();
            if (snapshot.ownedShellSlot() >= 9 && snapshot.ownedShellSlot() <= 44) {
                if (!KitFulfillmentCoordinator.hasVerifiedOwnedShell(snapshot))
                    return "owned_inventory_unverified";
                ownedSlots.add(snapshot.ownedShellSlot());
            }
            if (snapshot.retainedShell() != null) {
                int retainedSlot = KitFulfillmentCoordinator.uniqueRetainedShellSlot(snapshot);
                if (retainedSlot < 0) return "retained_shell_unverified";
                ownedSlots.add(retainedSlot);
            }
            if (!KitFulfillmentCoordinator.verifiedDisplacedAppleSlots(
                    snapshot.displacedAppleSlots())) return "displaced_apple_slots_changed";
            ownedSlots.addAll(snapshot.displacedAppleSlots().keySet());
            if (auditedCargoResume) {
                if (auditedEmptyShell && !KitEmptyShellOwnershipAudit.verify(snapshot,
                        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory())) {
                    return "audited_empty_shell_ownership_changed";
                }
                ownedSlots.addAll(snapshot.auditedCargoSlots().keySet());
                if (snapshot.auditedCargoSlot() >= 9 && snapshot.auditedCargoSlot() <= 44)
                    ownedSlots.add(snapshot.auditedCargoSlot());
                if (auditedEmptyShell) {
                    var player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
                    for (int raw = 9; raw <= 44; raw++) {
                        ItemStack stack = player.getItemStack(raw);
                        if (stack != null && stack.getAmount() > 0
                                && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                                        .contains("shulker_box")) ownedSlots.add(raw);
                    }
                } else if (!snapshot.sourceBoxesToReturn().isEmpty()) {
                    if (snapshot.sourceBoxesToReturn().size() == 1) {
                        int returnSlot = KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot);
                        if (returnSlot < 0) return "borrowed_source_return_unverified";
                        ownedSlots.add(returnSlot);
                    } else {
                        Set<Integer> returnSlots =
                                KitFulfillmentCoordinator.verifiedPendingSourceSlots(snapshot);
                        if (returnSlots.size() != snapshot.sourceBoxesToReturn().size())
                            return "borrowed_source_return_unverified";
                        ownedSlots.addAll(returnSlots);
                    }
                }
            }
            if (variantRepairResume) {
                if (!KitFulfillmentCoordinator.verifiedVariantRepairBoundary(snapshot)
                        || !KitFulfillmentCoordinator.verifiedVariantPendingSources(snapshot)) {
                    return "variant_repair_boundary_changed";
                }
                Set<Integer> pendingSlots =
                        KitFulfillmentCoordinator.variantPendingSourceSlots(snapshot);
                if (pendingSlots.size() != snapshot.sourceBoxesToReturn().size()) {
                    return "borrowed_source_return_unverified";
                }
                ownedSlots.addAll(pendingSlots);
                ownedSlots.add(snapshot.variantRepair().cargoRawSlot());
            }
            if (variantReadyResume) {
                if (!KitFulfillmentCoordinator.verifiedVariantReadyBoundary(snapshot))
                    return "variant_source_cargo_changed";
                ownedSlots.addAll(
                        KitFulfillmentCoordinator.variantPendingSourceSlots(snapshot));
                ownedSlots.add(snapshot.variantRepair().cargoRawSlot());
                ownedSlots.add(snapshot.variantRepair().auditedIncomingRawSlot());
            }
            if ("resume_borrowed_inventory_source".equals(snapshot.blocker())) {
                int sourceSlot = KitFulfillmentCoordinator.uniqueBorrowedInventorySourceSlot(snapshot);
                if (sourceSlot < 0) return "borrowed_inventory_source_unverified";
                ownedSlots.add(sourceSlot);
            }
            if (returningSource || verifyingFailedReturn) {
                int returnSlot = KitFulfillmentCoordinator.uniqueSourceReturnSlot(snapshot);
                if (returnSlot < 0) return "borrowed_source_return_unverified";
                ownedSlots.add(returnSlot);
            }
            Map<String, Integer> keepRules = database.loadKeepItems();
            var inventoryAdmission = KitInventoryAdmissionPolicy.inspectLive(keepRules, ownedSlots);
            if (inventoryAdmission == null) return "inventory_keep_policy_unavailable";
            if (!inventoryAdmission.accepted()) {
                String disposition = kitUnexpectedInventoryDisposition(inventoryAdmission);
                debugRecorder.record("kit_inventory_admission_rejected",
                        "phase=resume, " + disposition);
                return "unexpected_inventory_slots:" + inventoryAdmission.unexpectedSlots()
                        + "; " + disposition;
            }
            kitFulfillment.setInventoryKeepRules(keepRules);
            return kitFulfillment.resumeFromCheckpoint(snapshot, candidates,
                    config.pos1, config.pos2, excluded, index.getImportChests())
                    ? null : "owned_inventory_unverified";
        } catch (Exception e) {
            debugRecorder.record("kit_resume_error", "kit=" + snapshot.kitName(), e);
            return "resume_preflight_error";
        }
    }

    private String kitUnexpectedInventoryDisposition(KitInventoryAdmissionPolicy.Result admission)
            throws SQLException {
        return KitInventoryDispositionPolicy.plan(admission.unexpectedItems(),
                        database.loadColumnAssignments(), database.loadImportChests()).stream()
                .map(decision -> decision.rawSlot() + "=" + decision.route())
                .collect(java.util.stream.Collectors.joining(",", "disposition=[", "]"));
    }

    private boolean inConfiguredRegion(ContainerEntry entry) {
        return entry.x() >= Math.min(config.pos1[0], config.pos2[0])
                && entry.x() <= Math.max(config.pos1[0], config.pos2[0])
                && entry.y() >= Math.min(config.pos1[1], config.pos2[1])
                && entry.y() <= Math.max(config.pos1[1], config.pos2[1])
                && entry.z() >= Math.min(config.pos1[2], config.pos2[2])
                && entry.z() <= Math.max(config.pos1[2], config.pos2[2]);
    }

    private int kitSafeFoodCount() {
        int total = 0;
        for (var entry : playerInventoryCounts().entrySet()) {
            var food = FoodRegistry.REGISTRY.get(entry.getKey());
            if (food != null && food.isSafeFood() && autoEatAccepts(entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private boolean startKitRetrieval(String requestName, Map<String, Integer> kitItems,
                                      boolean preferNearbyFoodTargets) {
        String blocker = getRetrieveBlocker();
        if (blocker != null) {
            warn("Cannot start retrieval: {}", blocker);
            fireWebhookEvent("retrieve_start_blocked", Map.of(
                "request_name", requestName,
                "reason", blocker
            ));
            return false;
        }

        final List<com.zenith.plugin.stashmanager.index.ContainerEntry> entries;
        try {
            entries = database.getAllContainers();
        } catch (Exception e) {
            warn("Cannot start retrieval: failed to load containers from database: {}", e.getMessage());
            debugRecorder.record("retrieve_start_db_error", "Failed to load containers from database", e);
            fireWebhookEvent("retrieve_start_db_error", Map.of(
                "request_name", requestName,
                "message", e.getMessage()
            ));
            return false;
        }

        return retriever.startKit(
            requestName,
            kitItems,
            entries,
            config.pos1,
            config.pos2,
            getReservedContainerKeys(),
            preferNearbyFoodTargets,
            null,
            null
        );
    }

    /** Refill keep-list deficits, then equip only empty armor/offhand slots. */
    public boolean startKeepReequip(Map<String, Integer> requested, Set<String> equipment) {
        lastReequipError = getRetrieveBlocker();
        if (reequipActive) lastReequipError = "re-equip is already active";
        if (lastReequipError == null && (config.pos1 == null || config.pos2 == null)) {
            lastReequipError = "stash region is not configured";
        }
        if (lastReequipError == null && isBotOutsideScanEnvelope()) {
            lastReequipError = "bot is outside the configured stash area";
        }
        if (lastReequipError == null
                && (!CACHE.getPlayerCache().getThePlayer().isAlive()
                    || CACHE.getPlayerCache().getInventoryCache().getPlayerInventory() == null)) {
            lastReequipError = "bot inventory is not available in the game world";
        }
        if (lastReequipError != null || requested == null || equipment == null) return false;
        Map<String, Integer> food = new HashMap<>();
        Map<String, Integer> other = new HashMap<>();
        requested.forEach((id, count) -> {
            var foodData = FoodRegistry.REGISTRY.get(id);
            if (foodData != null && foodData.isSafeFood() && autoEatAccepts(id)) {
                food.put(id, count);
            } else {
                other.put(id, count);
            }
        });
        boolean carryingFood = playerInventoryCounts().keySet().stream().anyMatch(id -> {
            var foodData = FoodRegistry.REGISTRY.get(id);
            return foodData != null && foodData.isSafeFood() && autoEatAccepts(id);
        });
        if (!requested.isEmpty() && !CONFIG.client.extra.autoEat.enabled) {
            lastReequipError = "Zenith AutoEat is disabled; enable it before an autonomous re-equip trip.";
            return false;
        }
        if (!carryingFood && !requested.isEmpty()
                && CACHE.getPlayerCache().getThePlayer().getFood() <= 14) {
            lastReequipError = "Bot hunger is already low with no food aboard; feed it once before re-equip.";
            return false;
        }
        if (food.isEmpty() && !other.isEmpty() && !carryingFood) {
            lastReequipError = "No safe food is carried or requested by a finite keep rule; restock food first.";
            return false;
        }
        reequipRetrievalDone = requested.isEmpty();
        reequipFoodPhase = !food.isEmpty();
        reequipPartial = false;
        reequipRemainingRequests = Map.copyOf(other);
        reequipRequestName = reequipFoodPhase ? KEEP_REEQUIP_FOOD_REQUEST : KEEP_REEQUIP_REQUEST;
        reequipRetrievalResult = requested.isEmpty() ? "already_stocked" : "pending";
        reequipEquipment = Set.copyOf(equipment);
        reequipEquipped = 0;
        reequipSwap = null;
        reequipSwapTarget = -1;
        reequipSwapItem = null;
        Map<String, Integer> firstRequest = reequipFoodPhase ? food : other;
        if (!requested.isEmpty() && !startKitRetrieval(
                reequipRequestName, firstRequest, reequipFoodPhase)) {
            lastReequipError = "No indexed stock matched the first re-equip request ("
                    + retriever.getLastStartFailureReason() + ").";
            resetKeepReequip();
            return false;
        }
        // Target planning can block the terminal thread while client ticks continue. Do not
        // start the re-equip timeout until retrieval has actually been initialized.
        reequipWaitTicks = 0;
        reequipActive = true;
        debugRecorder.record("reequip_started",
                "requested=" + requested + ", equipment_types=" + equipment.size());
        return true;
    }

    private void resetKeepReequip() {
        reequipActive = false;
        reequipRetrievalDone = false;
        reequipFoodPhase = false;
        reequipPartial = false;
        reequipRemainingRequests = Map.of();
        reequipRequestName = KEEP_REEQUIP_REQUEST;
        reequipRetrievalResult = "none";
        reequipEquipment = Set.of();
        reequipSwap = null;
        reequipSwapTarget = -1;
        reequipSwapItem = null;
        reequipVerifyTicks = 0;
        reequipWaitTicks = 0;
    }

    public @Nullable String getLastReequipError() { return lastReequipError; }

    private void tickKeepReequip() {
        if (!reequipRetrievalDone) {
            if (++reequipWaitTicks > 200) finishKeepReequip(false, "retrieval_ended_without_result");
            return;
        }
        if (Proxy.getInstance().hasActivePlayer()
                || state != ScanState.IDLE && state != ScanState.DONE
                || organizer != null && organizer.isActive()) {
            finishKeepReequip(false, "automation_ownership_changed");
            return;
        }
        var inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null) {
            finishKeepReequip(false, "player_inventory_unavailable");
            return;
        }
        if (reequipFoodPhase) {
            boolean carryingFood = playerInventoryCounts().keySet().stream().anyMatch(id -> {
                var foodData = FoodRegistry.REGISTRY.get(id);
                return foodData != null && foodData.isSafeFood() && autoEatAccepts(id);
            });
            if (!carryingFood) {
                finishKeepReequip(false, "food_not_retrieved");
                return;
            }
            if (!reequipRemainingRequests.isEmpty()) {
                if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                        || INVENTORY.hasActiveRequest()) {
                    if (++reequipWaitTicks > 200) finishKeepReequip(false, "food_container_not_closed");
                    return;
                }
                if (!startKitRetrieval(KEEP_REEQUIP_REQUEST,
                        reequipRemainingRequests, false)) {
                    finishKeepReequip(false, "gear_retrieval_start_failed");
                    return;
                }
                reequipRequestName = KEEP_REEQUIP_REQUEST;
                reequipRetrievalDone = false;
                reequipWaitTicks = 0;
                reequipFoodPhase = false;
                return;
            }
            reequipFoodPhase = false;
        }
        if (reequipSwap != null) {
            if (!reequipSwap.isCompleted()) {
                if (++reequipVerifyTicks > 100) finishKeepReequip(false, "equip_action_timeout");
                return;
            }
            if (!reequipSwap.getNow()) {
                finishKeepReequip(false, "equip_action_rejected");
                return;
            }
            ItemStack equipped = inventory.getItemStack(reequipSwapTarget);
            String observed = equipped == null ? null
                    : ItemIdentifier.baseItemId(ItemIdentifier.getItemId(equipped));
            if (!Objects.equals(reequipSwapItem, observed)) {
                if (++reequipVerifyTicks > 40) finishKeepReequip(false, "equip_inventory_sync_unverified");
                return;
            }
            reequipEquipped++;
            debugRecorder.record("reequip_slot_confirmed",
                    "slot=" + reequipSwapTarget + ", item=" + reequipSwapItem);
            reequipSwap = null;
            reequipSwapTarget = -1;
            reequipSwapItem = null;
            reequipVerifyTicks = 0;
        }

        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || INVENTORY.hasActiveRequest() || BARITONE.isActive()) {
            if (++reequipWaitTicks > 200) finishKeepReequip(false, "shared_automation_busy");
            return;
        }
        reequipWaitTicks = 0;
        KeepReequipSelection.Swap candidate = nextReequipSwap(inventory);
        if (candidate == null) {
            List<Integer> ambiguous = KeepReequipSelection.ambiguousTargets(
                    currentReequipSlots(inventory), reequipEquipment);
            finishKeepReequip(!reequipPartial && ambiguous.isEmpty(),
                    !ambiguous.isEmpty() ? "manual_equipment_choice_required_slots_" + ambiguous
                        : reequipPartial ? "retrieval_incomplete" : "done");
            return;
        }
        reequipSwapTarget = candidate.target();
        reequipSwapItem = candidate.itemId();
        reequipVerifyTicks = 0;
        reequipSwap = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(InventoryActionMacros.swapSlots(candidate.source(), candidate.target()))
                .priority(5000)
                .build());
        debugRecorder.record("reequip_slot_requested",
                "source_slot=" + candidate.source() + ", target_slot=" + candidate.target()
                        + ", item=" + candidate.itemId());
    }

    private KeepReequipSelection.@Nullable Swap nextReequipSwap(
            com.zenith.cache.data.inventory.Container inventory) {
        return KeepReequipSelection.next(currentReequipSlots(inventory), reequipEquipment);
    }

    private Map<Integer, String> currentReequipSlots(
            com.zenith.cache.data.inventory.Container inventory) {
        Map<Integer, String> slots = new HashMap<>();
        for (int slot = 5; slot <= 45; slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String id = ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack));
            if (id != null) slots.put(slot, id);
        }
        // Zenith's own gear modules use higher-priority inventory actions. Never race them.
        if (CONFIG.client.extra.autoArmor.enabled) {
            for (int slot = 5; slot <= 8; slot++) slots.putIfAbsent(slot, "zenith:auto_armor");
        }
        if (CONFIG.client.extra.autoTotem.enabled) {
            slots.putIfAbsent(45, "zenith:auto_totem");
        }
        return slots;
    }

    private void finishKeepReequip(boolean success, String reason) {
        String retrieval = reequipRetrievalResult;
        int equipped = reequipEquipped;
        debugRecorder.record(success ? "reequip_completed" : "reequip_failed",
                "reason=" + reason + ", retrieval=" + retrieval + ", equipped=" + equipped);
        fireWebhookEvent(success ? "reequip_completed" : "reequip_failed", Map.of(
                "reason", reason,
                "retrieval_result", retrieval,
                "equipped_slots", equipped));
        resetKeepReequip();
    }

    private Set<Long> getReservedContainerKeys() {
        Set<Long> reserved = getScanExcludedContainerKeys();
        // Output chests are visible to scans for capacity checks, but never supply cargo.
        for (int[] pos : index.getExportChests()) {
            reserved.add(posKey(pos[0], pos[1], pos[2]));
        }
        return reserved;
    }

    private Set<Long> getScanExcludedContainerKeys() {
        Set<Long> reserved = new HashSet<>();
        for (int[] pos : config.supplyChests) {
            if (pos != null && pos.length >= 3) reserved.add(posKey(pos[0], pos[1], pos[2]));
        }

        // Protected inventories remain user-owned and are never scan targets.
        for (int[] pos : index.getBlacklistedChests()) {
            reserved.add(posKey(pos[0], pos[1], pos[2]));
        }

        if (database != null && database.isInitialized()) {
            try {
                var storage = database.loadStorageChests();
                for (int[] pos : storage.chests()) {
                    if (pos != null && pos.length >= 3) reserved.add(posKey(pos[0], pos[1], pos[2]));
                }
                int[] overflow = storage.overflowChest();
                if (overflow != null && overflow.length >= 3) {
                    reserved.add(posKey(overflow[0], overflow[1], overflow[2]));
                }
            } catch (Exception e) {
                warn("Failed to load reserved storage chests: {}", e.getMessage());
                debugRecorder.record("reserved_chests_load_failed", "Failed to load reserved storage chests", e);
            }
        }
        return reserved;
    }

    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) y & 0xFFFL) << 26
            | ((long) z & 0x3FFFFFFL);
    }

    public void stopRetrieval() {
        if (retriever.isActive()) {
            retriever.stop();
        }
    }

    // Start a scan. Returns true if started.
    public boolean startScan() {
        if (config.pos1 == null || config.pos2 == null) {
            warn("Cannot start scan: region not defined");
            return false;
        }
        boolean started = startScanRegion(new DatabaseManager.SavedRegion(
                "pos1/pos2", config.pos1, config.pos2), false);
        if (started) pendingScanRegions.clear();
        return started;
    }

    /** Scan saved regions in order, with a separate snapshot and history row for each. */
    public boolean startScanRegions(List<DatabaseManager.SavedRegion> regions) {
        if (regions == null || regions.isEmpty()) return false;
        List<DatabaseManager.SavedRegion> snapshots = new ArrayList<>(regions.size());
        for (var region : regions) {
            if (region == null || region.pos1() == null || region.pos2() == null
                    || region.pos1().length < 3 || region.pos2().length < 3) return false;
            snapshots.add(new DatabaseManager.SavedRegion(
                    region.name(), Arrays.copyOf(region.pos1(), 3),
                    Arrays.copyOf(region.pos2(), 3), region.role()));
        }
        if (!startScanRegion(snapshots.get(0), true)) return false;
        pendingScanRegions.clear();
        pendingScanRegions.addAll(snapshots.subList(1, snapshots.size()));
        scanRegionHandoffWaitTicks = 0;
        return true;
    }

    private boolean startScanRegion(DatabaseManager.SavedRegion region, boolean named) {
        String blocker = getScanStartBlocker(region.pos1(), region.pos2(), named);
        if (blocker != null) {
            warn("Cannot start scan: {}", blocker);
            recordAndFireScanFailure("scan_start_blocked", Map.of("reason", blocker));
            return false;
        }

        resetScanState();
        activeScanPos1 = Arrays.copyOf(region.pos1(), 3);
        activeScanPos2 = Arrays.copyOf(region.pos2(), 3);
        activeScanRegionName = region.name();
        latestScanTrusted = false;

        // Clear any existing Baritone state before starting
        BARITONE.stop();

        // Record start position for return nav
        var playerCache = CACHE.getPlayerCache();
        startX = playerCache.getX();
        startY = playerCache.getY();
        startZ = playerCache.getZ();
        hasStartPosition = true;
        scanTransitX = startX;
        scanTransitY = startY;
        scanTransitZ = startZ;
        scanTransitActive = named && ScanNavigationPolicy.isOutsideRegionEnvelope(
                startX, startY, startZ, activeScanPos1, activeScanPos2,
                SCAN_HORIZONTAL_RECOVERY_MARGIN, SCAN_VERTICAL_RECOVERY_MARGIN);
        info("Recorded starting position: {}, {}, {}",
            String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));

        // Disable Baritone block breaking during scan
        saveAndDisableBaritoneBreaking();

        // Record scan in DB
        if (database != null && database.isInitialized()) {
            try {
                currentScanId = database.recordScanStart(activeScanPos1, activeScanPos2);
            } catch (Exception e) {
                warn("Failed to record scan start in database: {}", e.getMessage());
                debugRecorder.record("scan_start_db_error", "Failed to record scan start", e);
                fireWebhookEvent("scan_start_db_error", Map.of("message", e.getMessage()));
            }
        }
        if (named && currentScanId < 0) {
            restoreBaritoneBreaking();
            activeScanPos1 = null;
            activeScanPos2 = null;
            activeScanRegionName = null;
            scanTransitActive = false;
            hasStartPosition = false;
            return false;
        }

        state = ScanState.ZONE_SCANNING;
        info("Starting container scan for {} in region ({}) to ({})",
            activeScanRegionName, formatPos(activeScanPos1), formatPos(activeScanPos2));
        debugRecorder.record("scan_started",
            "region=" + activeScanRegionName
                + ", pos1=" + formatPos(activeScanPos1)
                + ", pos2=" + formatPos(activeScanPos2)
                + ", start_position=" + String.format("%.1f, %.1f, %.1f", startX, startY, startZ));
        requestFoodContingency("scan_started");
        return true;
    }

    // Abort scan in progress.
    public void abortScan() {
        abortScan("manual_abort", true);
    }

    private void abortScan(String reason) {
        abortScan(reason, false);
    }

    private void abortScan(String reason, boolean returnAfterAbort) {
        if (state == ScanState.IDLE) return;
        if (state == ScanState.DONE) {
            if (!pendingScanRegions.isEmpty()) {
                info("Multi-region scan stopped before the next region ({} remaining)",
                        pendingScanRegions.size());
                pendingScanRegions.clear();
                scanRegionHandoffWaitTicks = 0;
            }
            return;
        }
        pendingScanRegions.clear();
        scanRegionHandoffWaitTicks = 0;

        if (foodContingencyJob == JobContinuanceManager.Job.SCAN) {
            resetFoodContingency();
        }

        boolean wasYielded = state == ScanState.YIELDED;

        // Close any open container
        if (!wasYielded) closeCurrentContainer();

        // Restore Baritone config
        restoreBaritoneBreaking();
        finishScanAfterReturn = false;
        resumeScanAfterReturn = false;
        abortScanAfterReturnReason = null;

        // Close out the scan_history row with partial counts so it doesn't stay
        // stuck at completed_at=NULL forever (a rescan will still safely
        // overwrite any already-indexed containers via ON CONFLICT upsert).
        if (database != null && database.isInitialized() && currentScanId >= 0) {
            try {
                database.recordScanAborted(currentScanId, containersFound, containersIndexed, containersFailed);
            } catch (Exception e) {
                warn("Failed to record scan abort in database: {}", e.getMessage());
                debugRecorder.record("scan_abort_db_error", "Failed to record scan abort", e);
            }
        }

        info("Scan aborted. Found={}, Indexed={}, Failed={}",
            containersFound, containersIndexed, containersFailed);
        recordAndFireScanFailure("scan_aborted", Map.of(
            "reason", reason,
            "found", containersFound,
            "indexed", containersIndexed,
            "failed", containersFailed));
        clearProxyControlCheckpoint(JobContinuanceManager.Job.SCAN, reason);

        if (shouldReturnToStartAfterAbort(
                returnAfterAbort, config.returnToStart, hasStartPosition, wasYielded)) {
            info("Returning to starting position after scan abort: {}, {}, {}",
                String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
            beginReturnPath();
            fireWebhookEvent("return_to_start_started", Map.of(
                "reason", "scan_aborted",
                "start_position", String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
        } else {
            if (wasYielded && returnAfterAbort && config.returnToStart && hasStartPosition) {
                // A manual stop cancels the checkpoint. Starting a delayed return inside the
                // old yield gate makes the stopped scan continue to look active and can later
                // steal shared automation back from the task which interrupted it.
                debugRecorder.record("scan_return_skipped",
                        "reason=manual_stop_during_yield, disposition=checkpoint_cancelled");
            }
            state = ScanState.IDLE;
            scannerPreemptionGate.reset();
            clearOwnedAutomation();
            lateOpenQuarantineTicks = 0;
            connectionRecoveryTracker.reset();
            activeScanPos1 = null;
            activeScanPos2 = null;
            activeScanRegionName = null;
            scanTransitActive = false;
        }
    }

    static boolean shouldReturnToStartAfterAbort(
            boolean returnAfterAbort,
            boolean returnToStartConfigured,
            boolean hasStartPosition,
            boolean wasYielded) {
        return returnAfterAbort && returnToStartConfigured && hasStartPosition && !wasYielded;
    }

    // Return to recorded start position. Returns true if nav started.
    public boolean returnToStart() {
        String blocker = getReturnToStartBlocker();
        if (blocker != null) {
            warn("Cannot return to start: {}", blocker);
            recordAndFireScanFailure("return_to_start_blocked", Map.of("reason", blocker));
            return false;
        }
        info("Returning to starting position: {}, {}, {}",
            String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
        beginReturnPath();
        finishScanAfterReturn = false;
        resumeScanAfterReturn = false;
        abortScanAfterReturnReason = null;
        fireWebhookEvent("return_to_start_started", Map.of("start_position",
            String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
        return true;
    }

    // True if a start position was recorded.
    public boolean hasStartPosition() {
        return hasStartPosition;
    }

    public double getStartX() { return startX; }
    public double getStartY() { return startY; }
    public double getStartZ() { return startZ; }
    public @Nullable String getActiveScanRegionName() { return activeScanRegionName; }
    public int getRemainingScanRegions() { return pendingScanRegions.size(); }

    public @Nullable String getScanStartBlocker() {
        return getScanStartBlocker(config.pos1, config.pos2, false);
    }

    public @Nullable String getScanStartBlocker(List<DatabaseManager.SavedRegion> regions) {
        if (regions == null || regions.isEmpty()) return "no saved regions selected";
        var region = regions.get(0);
        return getScanStartBlocker(region.pos1(), region.pos2(), true);
    }

    private @Nullable String getScanStartBlocker(int[] pos1, int[] pos2, boolean named) {
        if (kitJournal.exists()) return "kit fulfillment checkpoint requires recovery or manual review";
        if (named && (database == null || !database.isInitialized())) {
            return "database not connected; named scans require persisted scan history";
        }
        if (pos1 == null || pos2 == null || pos1.length < 3 || pos2.length < 3) {
            return "region not defined (set pos1 and pos2 first)";
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            return "already scanning (state=" + state + ")";
        }
        if (organizer != null && organizer.isActive()) {
            return "organizer is active";
        }
        if (retriever.isActive()) {
            return "retriever is active";
        }
        if (hasAnyBaritoneProcessActive() || BARITONE.isActive() || INVENTORY.hasActiveRequest()) {
            return "shared Baritone/inventory automation is active";
        }
        String automationBlocker = getAutomationUnavailableReason();
        if (automationBlocker != null) return automationBlocker;
        var player = CACHE.getPlayerCache();
        return ScanNavigationPolicy.isOutsideRegionEnvelope(
                player.getX(), player.getY(), player.getZ(), pos1, pos2,
                named ? NAMED_SCAN_START_HORIZONTAL_MARGIN : SCAN_HORIZONTAL_RECOVERY_MARGIN,
                named ? NAMED_SCAN_START_VERTICAL_MARGIN : SCAN_VERTICAL_RECOVERY_MARGIN)
                ? named
                    ? "bot is too far from the selected region; move it within 128 blocks before scanning"
                    : "bot is outside the configured stash area; move it near the region before scanning"
                : null;
    }

    public @Nullable String getReturnToStartBlocker() {
        if (!hasStartPosition) {
            return "no starting position recorded";
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            return "scan is active (state=" + state + ")";
        }
        if (organizer != null && organizer.isActive()) {
            return "organizer is active";
        }
        if (retriever.isActive()) {
            return "retriever is active";
        }
        return getAutomationUnavailableReason();
    }

    // Region size in blocks.
    public int[] getRegionDimensions() {
        if (config.pos1 == null || config.pos2 == null) return null;
        return new int[]{
            Math.abs(config.pos1[0] - config.pos2[0]) + 1,
            Math.abs(config.pos1[1] - config.pos2[1]) + 1,
            Math.abs(config.pos1[2] - config.pos2[2]) + 1
        };
    }

    // Tick Handlers
    private void onControllingPlayerLogin(PlayerLoginEvent.Post event) {
        if (!event.session().isActivePlayer()) return;

        if (kitFulfillment.isActive()) {
            kitFulfillment.interruptForRecovery("proxy_control",
                    Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        if (kitJournal.exists() && !(organizer != null && organizer.isActive()
                && organizer.isInventoryRecoveryOnly())) {
            try {
                var checkpoint = kitJournal.load().orElse(null);
                if (checkpoint != null && checkpoint.resumeNotBeforeEpochMilli() > 0) {
                    var update = jobContinuanceManager.beginControl(JobContinuanceManager.Job.KIT,
                            System.nanoTime(), Math.max(1, config.proxyControlGraceSeconds));
                    if (update.transition() == JobContinuanceManager.Transition.CONTROL_STARTED) {
                        controllingPlayerName = event.session().getName();
                        notifications.sendProxyControlWarning(controllingPlayerName, "kit",
                                Math.max(1, config.proxyControlGraceSeconds),
                                Math.max(1, config.scanPreemptionCooldownSeconds),
                                checkpoint.kitWorksite() != null
                                        || checkpoint.borrowedSourceWorksite() != null);
                    }
                }
            } catch (IOException e) {
                debugRecorder.record("kit_proxy_control_checkpoint_unreadable", e.getMessage());
            }
            event.session().sendAsyncAlert(
                    "<yellow>Kit fulfillment paused.</yellow> <gray>Its checkpoint is saved. "
                            + "Safe phases will resume after the configured cooldown when you release control; "
                            + "uncertain item moves require review. Check <white>/stash kit job</white>.</gray>");
            return;
        }

        JobContinuanceManager.Job job = activeResumableJob();
        if (job == JobContinuanceManager.Job.NONE) {
            if (organizer != null && !organizer.isActive() && organizer.hasDurableCheckpoint()) {
                event.session().sendAsyncAlert(
                        "<red>The previous stash organization stopped before finishing.</red> "
                                + "<gray>Its checkpoint is saved. Check <white>/stash organize status</white>, "
                                + "then use <white>/stash organize resume</white> after fixing the destination.</gray>");
            }
            return;
        }

        long now = System.nanoTime();
        var update = jobContinuanceManager.beginControl(
                job, now, Math.max(1, config.proxyControlGraceSeconds));
        if (update.transition() != JobContinuanceManager.Transition.CONTROL_STARTED) return;

        controllingPlayerName = event.session().getName();

        int graceSeconds = Math.max(1, config.proxyControlGraceSeconds);
        int cooldownSeconds = Math.max(1, config.scanPreemptionCooldownSeconds);
        boolean temporaryShulkerOutstanding = job == JobContinuanceManager.Job.ORGANIZE
                && organizer != null && organizer.hasTemporaryShulkerOutstanding();
        String detail = "job=" + job.name().toLowerCase()
                + ", controller=" + controllingPlayerName
                + ", grace_seconds=" + graceSeconds
                + ", cooldown_seconds=" + cooldownSeconds
                + (job == JobContinuanceManager.Job.ORGANIZE
                        ? ", " + organizerCheckpointDetail()
                        : ", scan_state=" + state + ", resume_mode=" + scanResumeMode
                            + ", completed=" + getProcessedCount() + ", total=" + containersFound);
        debugRecorder.record("proxy_control_grace_started", detail);
        notifications.sendProxyControlWarning(
                controllingPlayerName, job.name().toLowerCase(), graceSeconds, cooldownSeconds,
                temporaryShulkerOutstanding);
        event.session().sendAsyncAlert(
                "<yellow>Stash " + job.name().toLowerCase()
                        + " paused.</yellow> <gray>Run <white>/swap</white> within "
                        + graceSeconds
                        + " seconds or the job checkpoint will be aborted.</gray>"
                        + (temporaryShulkerOutstanding
                                ? " <red>A temporary shulker is mid-recovery; switch now.</red>"
                                : ""));
    }

    private void onClientTick(ClientTickEvent event) {
        if (!jobContinuanceManager.isActive()) return;

        long now = System.nanoTime();
        var update = jobContinuanceManager.tick(Proxy.getInstance().hasActivePlayer(), now);
        if (update.transition() == JobContinuanceManager.Transition.CONTROL_RELEASED) {
            debugRecorder.record("proxy_control_released",
                    "job=" + update.job().name().toLowerCase()
                            + ", controller=" + controllingPlayerName
                            + ", resume_cooldown_seconds="
                            + Math.max(getScanPreemptionCooldownRemainingSeconds(),
                                    getOrganizerPreemptionCooldownRemainingSeconds()));
            notifications.sendProxyControlReleased(
                    controllingPlayerName, update.job().name().toLowerCase());
            controllingPlayerName = null;
            return;
        }
        if (update.transition() != JobContinuanceManager.Transition.ABORT_REQUIRED) return;

        JobContinuanceManager.Job expiredJob = update.job();
        String controller = controllingPlayerName;
        int elapsedSeconds = jobContinuanceManager.elapsedSeconds(now);
        // Clear first so the job's abort callback cannot report this expected expiry as a
        // separate manual checkpoint cancellation.
        jobContinuanceManager.clear();
        if (expiredJob == JobContinuanceManager.Job.ORGANIZE
                && organizer != null && organizer.isActive()) {
            if (!organizer.isYielded()) organizer.yieldToAutomation("proxy_control_grace_expired");
            organizer.abortYielded("proxy_control_grace_expired");
            organizerPreemptionGate.reset();
        } else if (expiredJob == JobContinuanceManager.Job.SCAN
                && state != ScanState.IDLE && state != ScanState.DONE) {
            abortScan("proxy_control_grace_expired");
        } else if (expiredJob == JobContinuanceManager.Job.KIT) {
            cancelKitScheduledResume();
            debugRecorder.record("kit_resume_grace_expired",
                    "controller=" + controller + ", checkpoint_preserved=true");
            notifications.sendProxyControlKitReview(controller,
                    Math.max(1, config.proxyControlGraceSeconds));
            var activePlayer = Proxy.getInstance().getActivePlayer();
            if (activePlayer != null) {
                activePlayer.sendAsyncAlert("<yellow>Kit auto-resume canceled.</yellow> "
                        + "<gray>The checkpoint is saved. Review <white>/stash kit job</white> "
                        + "before scheduling another resume.</gray>");
            }
            controllingPlayerName = null;
            return;
        }

        debugRecorder.record("proxy_control_grace_expired",
                "job=" + expiredJob.name().toLowerCase()
                        + ", controller=" + controller
                        + ", elapsed_seconds=" + elapsedSeconds
                        + ", lost_progress=true");
        notifications.sendProxyControlAbort(
                controller, expiredJob.name().toLowerCase(), config.proxyControlGraceSeconds);
        var activePlayer = Proxy.getInstance().getActivePlayer();
        if (activePlayer != null) {
            activePlayer.sendAsyncAlert("<red>Stash " + expiredJob.name().toLowerCase()
                    + " aborted.</red> <gray>The proxy-control grace period expired.</gray>");
        }
        controllingPlayerName = null;
    }

    private void onTickStarting(ClientBotTick.Starting event) {
        // ClientBotTick.Starting may be posted from inside ClientOnlineEvent before every
        // subscriber has observed that the login completed. The explicit online handler owns
        // connection recovery so no job can resume against a half-initialized session.
        if (connectionRecoveryTracker.isPending()) return;

        long now = System.nanoTime();
        if (state == ScanState.YIELDED && scannerPreemptionGate.isClockSuspended()) {
            int suspendedTicks = scannerPreemptionGate.resumeClock(now);
            info("Bot control returned after {} seconds; scan checkpoint remains gated until cooldown and quiet checks pass",
                suspendedTicks / 20);
            debugRecorder.record("scan_bot_control_returned",
                "suspended_seconds=" + suspendedTicks / 20
                    + ", cooldown_remaining_seconds=" + getScanPreemptionCooldownRemainingSeconds()
                    + ", resume_mode=" + scanResumeMode
                    + ", target=" + currentContainerPos());
        }
        if (organizer != null && organizer.isYielded()
                && organizerPreemptionGate.isClockSuspended()) {
            int suspendedTicks = organizerPreemptionGate.resumeClock(now);
            info("Bot control returned after {} seconds; organizer checkpoint remains gated until cooldown and quiet checks pass",
                    suspendedTicks / 20);
            debugRecorder.record("organize_bot_control_returned",
                    organizerCheckpointDetail()
                            + ", suspended_seconds=" + suspendedTicks / 20
                            + ", cooldown_remaining_seconds="
                            + getOrganizerPreemptionCooldownRemainingSeconds());
        }
    }

    private void onTickStopped(ClientBotTick.Stopped event) {
        if (kitFulfillment.isActive()) {
            kitFulfillment.interruptForRecovery("bot_ticks_stopped",
                    Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        if (reequipActive) {
            debugRecorder.record("reequip_interrupted", "reason=bot_control_stopped");
            resetKeepReequip();
        }
        // Upstream disconnects stop bot ticks too. They are not proxy-control handoffs and must
        // remain recoverable until an explicit ClientOnlineEvent confirms a usable game session.
        if (!Proxy.getInstance().isConnected() || connectionRecoveryTracker.isPending()) {
            beginConnectionOutage(
                    "bot_ticks_stopped_while_disconnected", false, "bot_tick_stopped");
            return;
        }

        // Direct player control stops bot ticks and cancels Baritone. Preserve either long job
        // at a reconstructable checkpoint; wall time still counts toward the resume hold.
        if (organizer != null && organizer.isActive()) {
            if (!organizer.isYielded()) {
                beginOrganizerYield("proxy_control");
            }
            organizerPreemptionGate.suspendClock(System.nanoTime());
            info("Bot control taken; organizer checkpoint suspended");
            debugRecorder.record("organize_bot_control_suspended",
                    organizerCheckpointDetail()
                            + ", cooldown_seconds="
                            + Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        if (retriever.isActive()) {
            warn("Bot ticks stopped while retriever was active — stopping retriever");
            retriever.stop();
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            if (state != ScanState.YIELDED) {
                beginScannerYield();
            }
            scannerPreemptionGate.suspendClock(System.nanoTime());
            info("Bot control taken; scan checkpoint suspended (resume={})", scanResumeMode);
            debugRecorder.record("scan_bot_control_suspended",
                "resume_mode=" + scanResumeMode
                    + ", target=" + currentContainerPos()
                    + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds));
        }
    }

    private void onClientDisconnect(ClientDisconnectEvent event) {
        // Login failures can post a delayed synthetic disconnect. Ignore it if another manual
        // attempt has already established a live replacement session.
        if (Proxy.getInstance().isConnected()) {
            debugRecorder.record("connection_disconnect_event_ignored",
                    "reason=replacement_session_connected, manual_disconnect="
                            + event.manualDisconnect());
            return;
        }
        if (kitFulfillment.isActive()) {
            kitFulfillment.interruptForRecovery("client_disconnect",
                    Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        beginConnectionOutage(event.reason(), event.manualDisconnect(), "client_disconnect");
        if (retriever.isActive()) {
            warn("Connection ended while retriever was active — stopping retrieval safely");
            retriever.stop();
        }
    }

    private void onHealthAutoDisconnect(HealthAutoDisconnectEvent event) {
        // This event is asynchronous and can arrive after the definitive disconnect callback.
        // Keep it as useful provenance; ClientDisconnectEvent owns the atomic checkpoint.
        if (activeResumableJob() != JobContinuanceManager.Job.NONE) {
            debugRecorder.record("health_autodisconnect_requested",
                    "job=" + activeResumableJob().name().toLowerCase());
        }
    }

    private void onAutoReconnectScheduled(AutoReconnectEvent event) {
        long now = System.nanoTime();
        var update = connectionRecoveryTracker.autoReconnectScheduled(event.delaySeconds(), now);
        if (update.transition()
                != ConnectionRecoveryTracker.Transition.AUTO_RECONNECT_SCHEDULED) return;

        debugRecorder.record("connection_auto_reconnect_scheduled",
                connectionRecoveryDetail(update)
                        + ", delay_seconds=" + event.delaySeconds());
    }

    private void onClientStartConnect(ClientStartConnectEvent event) {
        ensureConnectionOutageForActiveCheckpoint("manual_or_automatic_connect_started");
        long now = System.nanoTime();
        var update = connectionRecoveryTracker.connectStarted(now);
        if (update.transition() != ConnectionRecoveryTracker.Transition.CONNECT_STARTED) return;

        debugRecorder.record("connection_attempt_started", connectionRecoveryDetail(update));
    }

    private void onClientConnect(ClientConnectEvent event) {
        long now = System.nanoTime();
        var update = connectionRecoveryTracker.transportConnected(now);
        if (update.transition()
                != ConnectionRecoveryTracker.Transition.TRANSPORT_CONNECTED) return;

        debugRecorder.record("connection_transport_connected",
                connectionRecoveryDetail(update)
                        + ", disposition=wait_for_client_online");
    }

    private void onClientLoginFailed(ClientLoginFailedEvent event) {
        ensureConnectionOutageForActiveCheckpoint("login_failed");
        long now = System.nanoTime();
        var update = connectionRecoveryTracker.loginFailed(now);
        if (update.transition() != ConnectionRecoveryTracker.Transition.LOGIN_FAILED) return;

        String exceptionType = event.exception() == null
                ? "unknown"
                : event.exception().getClass().getSimpleName();
        debugRecorder.record("connection_login_failed",
                connectionRecoveryDetail(update)
                        + ", exception_type=" + exceptionType
                        + ", disposition=checkpoint_preserved");
    }

    private void onClientOnline(ClientOnlineEvent event) {
        var client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected() || !client.isOnline()) {
            debugRecorder.record("connection_online_event_ignored",
                    "reason=session_no_longer_online, disposition=checkpoint_preserved");
            return;
        }

        long now = System.nanoTime();
        var update = connectionRecoveryTracker.online(now);
        if (update.transition()
                != ConnectionRecoveryTracker.Transition.ONLINE_RECOVERED) return;

        int scanSuspendedTicks = scannerPreemptionGate.resumeClock(now);
        int organizerSuspendedTicks = organizerPreemptionGate.resumeClock(now);
        int cooldownRemaining = Math.max(
                getScanPreemptionCooldownRemainingSeconds(),
                getOrganizerPreemptionCooldownRemainingSeconds());
        String detail = connectionRecoveryDetail(update)
                + ", scan_suspended_seconds=" + scanSuspendedTicks / 20
                + ", organizer_suspended_seconds=" + organizerSuspendedTicks / 20
                + ", cooldown_remaining_seconds=" + cooldownRemaining
                + ", disposition=wait_for_cooldown_and_quiet_window";
        info("Connection restored; stash checkpoint will resume after cooldown and quiet checks");
        debugRecorder.record("connection_recovery_armed", detail);
        fireWebhookEvent("connection_recovery_armed", Map.of(
                "job", activeResumableJob().name().toLowerCase(),
                "outage_seconds", update.elapsedSeconds(),
                "cooldown_remaining_seconds", cooldownRemaining,
                "manual_disconnect", connectionRecoveryTracker.manualDisconnect()
        ));
    }

    private void ensureConnectionOutageForActiveCheckpoint(String reason) {
        if (connectionRecoveryTracker.isPending()) return;
        // Start/connect events are asynchronous in Zenith and can be delivered after the
        // synchronous online event. Never let a late lifecycle callback pause a healthy job.
        if (Proxy.getInstance().isConnected()) return;
        if (activeResumableJob() == JobContinuanceManager.Job.NONE) return;
        beginConnectionOutage(reason, false, "connection_lifecycle");
    }

    private void beginConnectionOutage(
            String reason, boolean manualDisconnect, String source) {
        JobContinuanceManager.Job job = activeResumableJob();
        if (job == JobContinuanceManager.Job.NONE
                && !connectionRecoveryTracker.isPending()) return;

        long now = System.nanoTime();
        var update = connectionRecoveryTracker.beginOutage(reason, manualDisconnect, now);
        if (update.transition() != ConnectionRecoveryTracker.Transition.OUTAGE_STARTED) {
            debugRecorder.record("connection_disconnect_observed",
                    connectionRecoveryDetail(update)
                            + ", source=" + source
                            + ", manual_disconnect=" + manualDisconnect);
            return;
        }

        jobContinuanceManager.clear();
        controllingPlayerName = null;
        if (organizer != null && organizer.isActive()) {
            warn("Connection ended while organizer was active — preserving its restart checkpoint");
            if (!organizer.isYielded()) beginOrganizerYield("connection_lost");
            organizerPreemptionGate.suspendClock(now);
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Connection ended while scan was active — preserving its in-memory checkpoint");
            if (state != ScanState.YIELDED) beginScannerYield();
            scannerPreemptionGate.suspendClock(now);
        }
        if (foodContingencyActive
                && FOOD_CONTINGENCY_REQUEST.equals(retriever.getActiveRequestName())) {
            // The retriever has no durable walking/container checkpoint of its own. Preserve the
            // parent job and rebuild the missing-food request from live inventory after login.
            foodContingencyActive = false;
            foodContingencyRequested = true;
            foodContingencyVerificationTicks = 0;
            foodContingencyTerminalReason = "connection_interrupted";
            retriever.stop();
            debugRecorder.record("food_contingency_interrupted",
                    "reason=connection_lost, disposition=retry_after_reconnect");
        }

        String detail = connectionRecoveryDetail(update)
                + ", source=" + source
                + ", job=" + job.name().toLowerCase()
                + ", manual_disconnect=" + manualDisconnect
                + ", disposition=checkpoint_preserved";
        debugRecorder.record("connection_outage_started", detail);
        fireWebhookEvent("connection_outage_started", Map.of(
                "job", job.name().toLowerCase(),
                "manual_disconnect", manualDisconnect,
                "checkpoint_preserved", true
        ));
    }

    private String connectionRecoveryDetail(ConnectionRecoveryTracker.Update update) {
        return "phase=" + update.phase().name().toLowerCase()
                + ", outage_number=" + update.outageNumber()
                + ", elapsed_seconds=" + update.elapsedSeconds()
                + ", reason=" + connectionRecoveryTracker.reason();
    }

    private void onAutoEatOutOfFood(AutoEatOutOfFoodEvent event) {
        // Outside a stash job, Zenith owns both the warning and the response.
        if (activeResumableJob() == JobContinuanceManager.Job.NONE) return;
        muteAutoEatWarning();
        requestFoodContingency("autoeat_out_of_food");
    }

    private void onClientDeath(ClientDeathEvent event) {
        if (reequipActive) {
            debugRecorder.record("reequip_interrupted", "reason=player_died");
            if (retriever.isActive()) retriever.stop();
            resetKeepReequip();
        }
        stopAfterBotDeath();
    }

    private boolean stopAfterBotDeath() {
        if (organizer == null || !organizer.isActive()) return false;
        resetFoodContingency();
        organizer.abortAfterPlayerDeath();
        organizerPreemptionGate.reset();
        organizerPickupRecoveryDeferred = false;
        debugRecorder.record("organize_player_died",
                "disposition=terminal_checkpoint_manual_reconciliation_required");
        return true;
    }

    private void probeFoodSafety() {
        if (++foodSafetyProbeTicks < 20) return;
        foodSafetyProbeTicks = 0;
        boolean active = activeResumableJob() != JobContinuanceManager.Job.NONE;
        boolean alive = CACHE.getPlayerCache().getThePlayer().isAlive();
        int hunger = CACHE.getPlayerCache().getThePlayer().getFood();
        if (!shouldProbeFoodRefill(active, alive,
                foodContingencyJob != JobContinuanceManager.Job.NONE,
                hunger, CONFIG.client.extra.autoEat.hungerThreshold)) return;
        FoodContingencyPolicy.Plan plan = currentFoodContingencyPlan();
        if (plan != null && plan.hasFood()) return;
        requestFoodContingency("live_food_empty");
    }

    static boolean shouldProbeFoodRefill(boolean jobActive, boolean alive,
                                         boolean alreadyHandling, int hunger, int autoEatThreshold) {
        return jobActive && alive && !alreadyHandling
                && hunger <= Math.max(14, autoEatThreshold);
    }

    private void requestFoodContingency(String trigger) {
        foodContingencyTrigger = trigger == null ? "unspecified" : trigger;
        foodContingencyRequested = true;
    }

    /** Runs an internal retrieval while leaving the interrupted stash job checkpointed. */
    private boolean tickFoodContingency() {
        JobContinuanceManager.Job liveJob = activeResumableJob();
        if (foodContingencyJob == JobContinuanceManager.Job.NONE
                && foodContingencyRequested) {
            foodContingencyJob = liveJob;
        }
        if (foodContingencyJob == JobContinuanceManager.Job.NONE) {
            resetFoodContingency();
            return false;
        }
        if (!isFoodContingencyJobActive()) {
            resetFoodContingency();
            return false;
        }

        if (foodContingencyActive) {
            holdFoodContingencyGate();
            if (retriever.isActive()) {
                retriever.tick();
            } else {
                // A proxy-control handoff or explicit retriever stop can cancel this child task.
                // Rebuild its request rather than leaving the parent job yielded forever.
                foodContingencyActive = false;
                foodContingencyRequested = true;
                foodContingencyTerminalReason = "retrieval_interrupted";
                debugRecorder.record("food_contingency_interrupted",
                        "reason=retriever_inactive, disposition=retry_from_live_inventory");
            }
            return true;
        }

        if (foodContingencyVerificationTicks > 0) {
            holdFoodContingencyGate();
            if (foodContingencyCleanupPending && !tickFoodContingencyCleanup()) return true;
            foodContingencyVerificationTicks--;
            if (foodContingencyVerificationTicks == 0) verifyFoodContingencyResult();
            return true;
        }

        if (foodContingencyStartRetryTicks > 0) {
            holdFoodContingencyGate();
            foodContingencyStartRetryTicks--;
            return true;
        }

        if (foodContingencyBlocked) {
            holdFoodContingencyGate();
            if (foodContingencyBlockedElapsedTicks < Integer.MAX_VALUE) {
                foodContingencyBlockedElapsedTicks++;
            }
            if (++foodContingencyBlockedRecheckTicks >= 20) {
                foodContingencyBlockedRecheckTicks = 0;
                if ("retrieval_container_close_unverified".equals(
                        foodContingencyTerminalReason)) {
                    retryBlockedFoodContainerCleanup();
                    return true;
                }
                FoodContingencyPolicy.Plan plan = currentFoodContingencyPlan();
                if (plan != null && plan.hasFood()) {
                    completeFoodContingency(plan, "manual_food_available");
                } else if (plan != null && plan.configured() && plan.needsRefill()
                        && FoodContingencyRetryPolicy.shouldRetry(
                                foodContingencyTerminalReason,
                                foodContingencyBlockedElapsedTicks,
                                foodContingencyBlockedRetryCount,
                                !isBotOutsideScanEnvelope(),
                                Proxy.getInstance().isConnected()
                                        && !Proxy.getInstance().hasActivePlayer(),
                                !isSharedAutomationBusy())) {
                    foodContingencyBlocked = false;
                    foodContingencyRequested = true;
                    foodContingencyBlockedElapsedTicks = 0;
                    foodContingencyBlockedRetryCount++;
                    debugRecorder.record("food_contingency_retry_armed",
                            "job=" + foodContingencyJob.name().toLowerCase()
                                    + ", previous_reason=" + foodContingencyTerminalReason
                                    + ", retry=" + foodContingencyBlockedRetryCount
                                    + "/" + FoodContingencyRetryPolicy.MAX_RETRIES
                                    + ", disposition=retry_from_live_inventory_near_stash");
                }
            }
            return true;
        }

        if (!foodContingencyRequested) return false;
        FoodContingencyPolicy.Plan plan = currentFoodContingencyPlan();
        if (plan == null) {
            if (yieldForFoodContingency()) {
                blockFoodContingency("keep_list_unavailable");
                return true;
            }
            return false;
        }
        if (!plan.configured()) {
            if (!foodContingencyMustBlock()) {
                skipFoodContingency("no_safe_food_keep_rule");
                return false;
            }
            if (yieldForFoodContingency()) {
                blockFoodContingency("no_safe_food_keep_rule");
                return true;
            }
            return false;
        }
        if (!plan.needsRefill()) {
            if (plan.hasFood()) {
                completeFoodContingency(plan, "keep_target_already_satisfied");
                return false;
            }
            if (!foodContingencyMustBlock()) {
                skipFoodContingency("finite_food_keep_count_required");
                return false;
            }
            if (yieldForFoodContingency()) {
                blockFoodContingency("finite_food_keep_count_required");
                return true;
            }
            return false;
        }
        if (!yieldForFoodContingency()) return false;
        return startFoodContingencyRetrieval(plan);
    }

    private boolean foodContingencyMustBlock() {
        return "autoeat_out_of_food".equals(foodContingencyTrigger)
                || "live_food_empty".equals(foodContingencyTrigger);
    }

    private void skipFoodContingency(String reason) {
        debugRecorder.record("food_contingency_skipped",
                "job=" + foodContingencyJob.name().toLowerCase()
                        + ", trigger=" + foodContingencyTrigger
                        + ", reason=" + reason);
        foodContingencyRequested = false;
        foodContingencyActive = false;
        foodContingencyBlocked = false;
        foodContingencyVerificationTicks = 0;
        foodContingencyBlockedRecheckTicks = 0;
        foodContingencyBlockedElapsedTicks = 0;
        foodContingencyBlockedRetryCount = 0;
        foodContingencyStartRetryTicks = 0;
        foodContingencyCleanupPending = false;
        foodContingencyRetrievalStarted = false;
        foodContingencyCloseGate.reset();
        restoreAutoEatWarning();
        foodContingencyTerminalReason = "none";
        foodContingencyJob = JobContinuanceManager.Job.NONE;
    }

    private boolean yieldForFoodContingency() {
        return switch (foodContingencyJob) {
            case ORGANIZE -> organizer != null && (organizer.isYielded()
                    || beginOrganizerYield("food_contingency"));
            case SCAN -> {
                if (state != ScanState.YIELDED) beginScannerYield();
                yield state == ScanState.YIELDED;
            }
            case NONE, KIT -> false;
        };
    }

    private boolean startFoodContingencyRetrieval(FoodContingencyPolicy.Plan plan) {
        boolean directFoodAroundPackedHandoff = foodContingencyJob == JobContinuanceManager.Job.ORGANIZE
                && organizer != null && organizer.hasUnresolvedPackedHandoff()
                && organizer.canRefillFoodWithoutTouchingPackedHandoff();
        if (foodContingencyJob == JobContinuanceManager.Job.ORGANIZE
                && organizer != null
                && (organizer.hasTemporaryShulkerOutstanding()
                    || organizer.hasUnresolvedPackedHandoff())
                && !directFoodAroundPackedHandoff) {
            // A normal retrieval can borrow the organizer's in-flight box. Only the
            // exact, inventory-verified handoff may use direct loose stock instead.
            blockFoodContingency("organizer_temporary_shulker_unresolved");
            return true;
        }
        if (isBotOutsideScanEnvelope()) {
            blockFoodContingency("bot_outside_stash_region");
            return true;
        }
        if (database == null || !database.isInitialized()) {
            blockFoodContingency("database_unavailable");
            return true;
        }
        final List<com.zenith.plugin.stashmanager.index.ContainerEntry> entries;
        try {
            entries = database.getAllContainers();
        } catch (Exception e) {
            debugRecorder.record("food_contingency_db_error",
                    "Could not load food candidates", e);
            blockFoodContingency("container_index_unavailable");
            return true;
        }

        foodContingencyRequested = false;
        foodContingencyActive = true;
        foodContingencyRetrievalStarted = true;
        muteAutoEatWarning();
        foodContingencyTerminalReason = "retrieval_not_started";
        debugRecorder.record("food_contingency_started",
                "job=" + foodContingencyJob.name().toLowerCase()
                        + ", trigger=" + foodContingencyTrigger
                        + ", requested=" + plan.requested()
                        + ", current_food_units=" + plan.currentFoodUnits()
                        + ", target_food_units=" + plan.targetFoodUnits()
                        + ", direct_contents_only=" + directFoodAroundPackedHandoff);
        boolean started = retriever.startKit(
                FOOD_CONTINGENCY_REQUEST,
                plan.requested(),
                entries,
                foodContingencyJob == JobContinuanceManager.Job.SCAN ? scanPos1() : config.pos1,
                foodContingencyJob == JobContinuanceManager.Job.SCAN ? scanPos2() : config.pos2,
                getReservedContainerKeys(),
                true,
                foodContingencyJob == JobContinuanceManager.Job.ORGANIZE && organizer != null
                        ? organizer.getReconciliationStation() : null,
                foodContingencyJob == JobContinuanceManager.Job.ORGANIZE && organizer != null
                        ? organizer.getReconciliationWorksite() : null,
                -1, null, directFoodAroundPackedHandoff);
        if (!started && foodContingencyActive) {
            foodContingencyActive = false;
            StashRetriever.StartFailureReason failure = retriever.getLastStartFailureReason();
            foodContingencyTerminalReason = "retrieval_start_"
                    + failure.name().toLowerCase();
            if (failure.retryable()) {
                // Connection and proxy-control transitions are temporary ownership failures,
                // not evidence that the indexed stash lacks food. Keep the parent checkpoint
                // yielded and rebuild the request from live inventory after a short backoff.
                foodContingencyRequested = true;
                foodContingencyStartRetryTicks = FOOD_CONTINGENCY_START_RETRY_TICKS;
                debugRecorder.record("food_contingency_start_deferred",
                        "job=" + foodContingencyJob.name().toLowerCase()
                                + ", reason=" + failure.name().toLowerCase()
                                + ", retry_ticks=" + foodContingencyStartRetryTicks
                                + ", disposition=retry_from_live_inventory");
            } else {
                foodContingencyVerificationTicks = 10;
            }
        }
        holdFoodContingencyGate();
        return true;
    }

    private FoodContingencyPolicy.Plan currentFoodContingencyPlan() {
        if (database == null || !database.isInitialized()) return null;
        try {
            return FoodContingencyPolicy.plan(
                    database.loadKeepItems(), playerInventoryCounts(), this::autoEatAccepts);
        } catch (Exception e) {
            debugRecorder.record("food_contingency_keep_load_failed",
                    "Could not load keep-list food targets", e);
            return null;
        }
    }

    private Map<String, Integer> playerInventoryCounts() {
        Map<String, Integer> counts = new HashMap<>();
        var player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return counts;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = player.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack));
            if (itemId != null) counts.merge(itemId, stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    private void handleFoodContingencyRetrievalTerminal(
            String event, Map<String, Object> payload) {
        foodContingencyActive = false;
        foodContingencyTerminalReason = "retrieve_completed".equals(event)
                ? "complete"
                : String.valueOf(payload.getOrDefault("reason", event));
        foodContingencyCleanupPending = true;
        foodContingencyCloseGate.reset();
        foodContingencyVerificationTicks = 10;
        holdFoodContingencyGate();
    }

    private boolean tickFoodContingencyCleanup() {
        int openContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        FoodContingencyCloseGate.Action action = foodContingencyCloseGate.tick(
                openContainerId > 0, INVENTORY.hasActiveRequest());
        return switch (action) {
            case WAIT -> false;
            case REQUEST_CLOSE -> {
                closeCurrentContainer();
                debugRecorder.record("food_contingency_container_close_retry",
                        "container_id=" + openContainerId
                                + ", attempt=" + foodContingencyCloseGate.closeAttempts()
                                + ", elapsed_ticks=" + foodContingencyCloseGate.elapsedTicks());
                yield false;
            }
            case READY -> {
                debugRecorder.record("food_contingency_handoff_ready",
                        "close_attempts=" + foodContingencyCloseGate.closeAttempts()
                                + ", elapsed_ticks=" + foodContingencyCloseGate.elapsedTicks());
                foodContingencyCleanupPending = false;
                foodContingencyCloseGate.reset();
                yield true;
            }
            case TIMEOUT -> {
                blockFoodContingency("retrieval_container_close_unverified");
                yield false;
            }
        };
    }

    private void retryBlockedFoodContainerCleanup() {
        int openContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openContainerId <= 0 && !INVENTORY.hasActiveRequest()) {
            FoodContingencyPolicy.Plan plan = currentFoodContingencyPlan();
            if (plan != null && plan.hasFood()) {
                foodContingencyBlocked = false;
                completeFoodContingency(plan, "container_closed_after_block");
            }
            return;
        }
        if (openContainerId > 0 && !INVENTORY.hasActiveRequest()) {
            closeCurrentContainer();
            debugRecorder.record("food_contingency_container_close_retry",
                    "container_id=" + openContainerId
                            + ", disposition=blocked_retry");
        }
    }

    private void verifyFoodContingencyResult() {
        FoodContingencyPolicy.Plan plan = currentFoodContingencyPlan();
        if (plan != null && plan.hasFood()) {
            completeFoodContingency(plan, foodContingencyTerminalReason);
            return;
        }
        blockFoodContingency("no_food_retrieved_" + foodContingencyTerminalReason);
    }

    private void completeFoodContingency(
            FoodContingencyPolicy.Plan plan, String reason) {
        JobContinuanceManager.Job completedJob = foodContingencyJob;
        boolean retrieved = foodContingencyRetrievalStarted;
        info("Food contingency satisfied with {} safe kept food item(s); resuming {} after cooldown",
                plan.currentFoodUnits(), completedJob.name().toLowerCase());
        debugRecorder.record("food_contingency_completed",
                "job=" + completedJob.name().toLowerCase()
                        + ", reason=" + reason
                        + ", food_units=" + plan.currentFoodUnits()
                        + ", remaining_refill=" + plan.requested());
        if (retrieved && completedJob == JobContinuanceManager.Job.ORGANIZE
                && !"manual_food_available".equals(reason)) {
            notifications.sendFoodContingencyRecovered(
                    "organize", plan.currentFoodUnits(),
                    Math.max(1, config.scanPreemptionCooldownSeconds));
        }
        foodContingencyRequested = false;
        foodContingencyActive = false;
        foodContingencyBlocked = false;
        foodContingencyVerificationTicks = 0;
        foodContingencyBlockedRecheckTicks = 0;
        foodContingencyBlockedElapsedTicks = 0;
        foodContingencyBlockedRetryCount = 0;
        foodContingencyStartRetryTicks = 0;
        foodContingencyCleanupPending = false;
        foodContingencyRetrievalStarted = false;
        foodContingencyCloseGate.reset();
        foodContingencyTerminalReason = "none";
        foodContingencyJob = JobContinuanceManager.Job.NONE;
        restoreAutoEatWarning();
    }

    private void blockFoodContingency(String reason) {
        boolean newlyBlocked = !foodContingencyBlocked;
        foodContingencyRequested = false;
        foodContingencyActive = false;
        foodContingencyBlocked = true;
        foodContingencyVerificationTicks = 0;
        foodContingencyBlockedRecheckTicks = 0;
        foodContingencyBlockedElapsedTicks = 0;
        foodContingencyStartRetryTicks = 0;
        foodContingencyCleanupPending = false;
        foodContingencyCloseGate.reset();
        foodContingencyTerminalReason = reason;
        holdFoodContingencyGate();
        if (!newlyBlocked) return;

        String job = foodContingencyJob.name().toLowerCase();
        warn("{} paused because no usable keep-list food could be retrieved ({})", job, reason);
        debugRecorder.record("food_contingency_blocked",
                "job=" + job + ", trigger=" + foodContingencyTrigger + ", reason=" + reason);
        notifications.sendFoodContingencyBlocked(job, reason);
    }

    private void holdFoodContingencyGate() {
        if (foodContingencyJob == JobContinuanceManager.Job.ORGANIZE
                && organizerPreemptionGate.isYielded()) {
            organizerPreemptionGate.tick(true);
        } else if (foodContingencyJob == JobContinuanceManager.Job.SCAN
                && scannerPreemptionGate.isYielded()) {
            scannerPreemptionGate.tick(true);
        }
    }

    private boolean isFoodContingencyJobActive() {
        return switch (foodContingencyJob) {
            case ORGANIZE -> organizer != null && organizer.isActive();
            case SCAN -> state != ScanState.IDLE && state != ScanState.DONE;
            case NONE, KIT -> false;
        };
    }

    private void resetFoodContingency() {
        if (retriever.isActive()
                && FOOD_CONTINGENCY_REQUEST.equals(retriever.getActiveRequestName())) {
            retriever.stop();
        }
        foodContingencyRequested = false;
        foodContingencyActive = false;
        foodContingencyBlocked = false;
        foodContingencyVerificationTicks = 0;
        foodContingencyBlockedRecheckTicks = 0;
        foodContingencyBlockedElapsedTicks = 0;
        foodContingencyBlockedRetryCount = 0;
        foodContingencyStartRetryTicks = 0;
        foodContingencyCleanupPending = false;
        foodContingencyRetrievalStarted = false;
        foodContingencyCloseGate.reset();
        foodContingencyTrigger = "none";
        foodContingencyTerminalReason = "none";
        foodContingencyJob = JobContinuanceManager.Job.NONE;
        restoreAutoEatWarning();
    }

    private boolean autoEatAccepts(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        Object autoEat = CONFIG.client.extra.autoEat;
        try {
            Object mode = autoEat.getClass().getField("mode").get(autoEat);
            Object configuredFoods = autoEat.getClass().getField("foods").get(autoEat);
            boolean listed = false;
            if (configuredFoods instanceof Iterable<?> foods) {
                for (Object configuredFood : foods) {
                    if (itemId.equals(ItemIdentifier.baseItemId(
                            String.valueOf(configuredFood)))) {
                        listed = true;
                        break;
                    }
                }
            }
            return switch (String.valueOf(mode)) {
                case "BLACKLIST" -> !listed;
                case "WHITELIST" -> listed;
                default -> true;
            };
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Older Zenith targets only expose the legacy all-food behavior.
            return true;
        }
    }

    private void muteAutoEatWarning() {
        if (autoEatWarningMuted) return;
        autoEatWarningBeforeMute = CONFIG.client.extra.autoEat.warning;
        CONFIG.client.extra.autoEat.warning = false;
        autoEatWarningMuted = true;
        debugRecorder.record("food_contingency_autoeat_warning_muted",
                "previous=" + autoEatWarningBeforeMute);
    }

    private void restoreAutoEatWarning() {
        if (!autoEatWarningMuted) return;
        CONFIG.client.extra.autoEat.warning = autoEatWarningBeforeMute;
        autoEatWarningMuted = false;
        debugRecorder.record("food_contingency_autoeat_warning_restored",
                "restored=" + autoEatWarningBeforeMute);
    }

    private void onTick(ClientBotTick event) {
        // Tick TravelManager independently (it manages its own state)
        com.zenith.plugin.stashmanager.travel.TravelManager.get().tick();
        tunnelNetworkSyncWorker.tick();
        sampleAutomationPose();

        if (!CACHE.getPlayerCache().getThePlayer().isAlive()) {
            if (kitFulfillment.isActive()) {
                kitFulfillment.interruptForRecovery("bot_death",
                        Math.max(1, config.scanPreemptionCooldownSeconds));
            }
            if (reequipActive) {
                if (retriever.isActive()) retriever.stop();
                resetKeepReequip();
            }
            if (stopAfterBotDeath()) return;
        }
        if (reequipActive
                && CACHE.getPlayerCache().getThePlayer().getFood()
                    <= (reequipFoodPhase ? 6 : 14)
                && playerInventoryCounts().keySet().stream().noneMatch(id -> {
                    var foodData = FoodRegistry.REGISTRY.get(id);
                    return foodData != null && foodData.isSafeFood() && autoEatAccepts(id);
                })) {
            // Retrieval has no durable checkpoint. Stop before hunger turns a restock into
            // another death; the command can be retried from live inventory after food arrives.
            finishKeepReequip(false, "food_exhausted_during_reequip");
            if (retriever.isActive()) retriever.stop();
            return;
        }
        probeFoodSafety();

        if (kitFulfillment.isActive()) {
            if (CACHE.getPlayerCache().getThePlayer().getFood() <= 8
                    && kitSafeFoodCount() == 0) {
                kitFulfillment.interruptForRecovery("food_exhausted",
                        Math.max(1, config.scanPreemptionCooldownSeconds));
                return;
            }
            if (retriever.isActive()) retriever.tick();
            kitFulfillment.tick();
            return;
        }
        if (kitJournal.exists() && !(organizer != null && organizer.isActive()
                && organizer.isInventoryRecoveryOnly())) {
            if (kitRecovery.state() == KitWorksiteRecovery.State.DONE) {
                try {
                    var checkpoint = kitJournal.load().orElse(null);
                    if (checkpoint != null && (checkpoint.kitWorksite() != null
                            || checkpoint.borrowedSourceWorksite() != null)) {
                        kitRecovery.retry();
                    }
                } catch (IOException e) {
                    debugRecorder.record("kit_recovery_checkpoint_unreadable", e.getMessage());
                }
            }
            if (!retriever.isActive()) kitRecovery.tick();
            tickScheduledKitResume();
            return;
        }

        if (tickFoodContingency()) return;

        // Delegate tick to organizer when active
        if (organizer != null && organizer.isActive()) {
            if (organizer.isYielded()) {
                tickOrganizerYielded();
                return;
            }
            if (organizer.isInventoryRecoveryOnly()) {
                try {
                    var savedKit = kitJournal.load().orElse(null);
                    if (!kitAllowsInventoryReconciliation(savedKit)) {
                        beginOrganizerYield("kit_owned_shell_or_checkpoint_changed");
                        return;
                    }
                } catch (Exception e) {
                    beginOrganizerYield("kit_checkpoint_unreadable");
                    return;
                }
            }
            if (organizer.wasAutomationPreempted()) {
                if (!beginOrganizerYield("shared_automation")) {
                    if (!organizerPickupRecoveryDeferred) {
                        organizerPickupRecoveryDeferred = true;
                        organizerPreemptionCount++;
                        debugRecorder.record("organize_preemption_deferred",
                                organizerCheckpointDetail()
                                        + ", reason=temporary_shulker_drop_exposed"
                                        + ", disposition=wait_for_foreign_task_then_recover"
                                        + ", preemption_count=" + organizerPreemptionCount);
                    }
                }
                return;
            }
            if (organizerPickupRecoveryDeferred) {
                organizerPickupRecoveryDeferred = false;
                debugRecorder.record("organize_preemption_recovery_started",
                        organizerCheckpointDetail()
                                + ", reason=foreign_task_released");
            }
            organizer.tick();
            return;
        }

        if (retriever.isActive()) {
            retriever.tick();
            return;
        }

        if (reequipActive) {
            tickKeepReequip();
            return;
        }

        if (state == ScanState.DONE && !pendingScanRegions.isEmpty()) {
            tickPendingScanRegion();
            return;
        }
        if (state == ScanState.IDLE || state == ScanState.DONE) return;

        if (state == ScanState.YIELDED) {
            tickScannerYielded();
            return;
        }

        if (scannerWasPreempted()) {
            beginScannerYield();
            return;
        }

        enforceBaritoneBreakingDisabled();

        switch (state) {
            case ZONE_SCANNING -> tickZoneScanning();
            case WALKING -> tickWalking();
            case APPROACHING -> tickApproaching();
            case OPENING -> tickOpening();
            case READING -> tickReading();
            case CLOSING -> tickClosing();
            case WALKING_TO_ZONE -> tickWalkingToZone();
            case RETURNING -> tickReturning();
            case YIELDED -> tickScannerYielded();
            default -> {}
        }
    }

    // State Implementations
    private void tickZoneScanning() {
        if (beginScanPositionRecoveryIfNeeded(
                ScanResumeMode.RESCAN_ZONE, "zone_scan_outside_region")) return;

        int remainingCapacity = Math.max(0, config.maxContainers - discoveredContainerKeys.size());
        List<ContainerLocation> found = remainingCapacity > 0
            ? regionScanner.scanRegion(
                scanPos1(), scanPos2(), remainingCapacity, getScanExcludedContainerKeys())
            : List.of();

        int newlyQueued = 0;
        for (ContainerLocation container : found) {
            if ((container.type() == BlockEntityType.CHEST
                    || container.type() == BlockEntityType.TRAPPED_CHEST)
                    && isDoubleChestPartner(container)) {
                continue;
            }
            if (discoveredContainerKeys.add(container.posKey())) {
                pendingContainers.add(container);
                newlyQueued++;
            }
        }
        containersFound = discoveredContainerKeys.size();

        info("Zone scan complete: {} new containers queued ({} unique total)",
            newlyQueued, containersFound);
        debugRecorder.record("scan_zone_queued",
            "new_targets=" + newlyQueued
                + ", unique_total=" + containersFound
                + ", queue_entries=" + Math.max(0, pendingContainers.size() - currentContainerIndex - 1));

        // Check for chunks beyond render distance
        var unscanned = regionScanner.getUnscannedChunks(scanPos1(), scanPos2());
        if (!unscanned.isEmpty() && containersFound < config.maxContainers) {
            info("{} chunks still unloaded — will walk to load them", unscanned.size());
        }

        if (pendingContainers.isEmpty() && !unscanned.isEmpty()
                && containersFound < config.maxContainers) {
            startWalkingToUnscannedChunk(unscanned.get(0));
            return;
        }

        if (pendingContainers.isEmpty()) {
            info("No containers found in region");
            fireWebhookEvent("scan_empty");
            finishScan();
            return;
        }

        advanceToNextContainer();
    }

    private void tickWalking() {
        if (beginScanPositionRecoveryIfNeeded(
                ScanResumeMode.RETRY_CURRENT, "container_walk_outside_region")) return;

        walkingTickCount++;

        ContainerLocation target = currentContainer();
        if (target == null) {
            advanceToNextContainer();
            return;
        }

        if (scanDoorOpeningPos != null) {
            tickScanDoorOpening(target);
            return;
        }

        double dist = distanceToContainer(target);

        // Always check distance — may have arrived regardless of Baritone state
        if (isAtContainerAccessPosition(target)) {
            stopOwnedBaritoneProcess();
            scanWalkProgress.reset();
            walkRetryCount = 0;
            state = ScanState.OPENING;
            containerOpenGate.reset();
            tickCounter = 0;
            openTimeoutCounter = 0;
            openInteractionAttempts = 0;
            lastOpenInteractionTick = -1;
            scanOpenClickTarget = null;
            containerDataReceived = false;
            return;
        }

        // Zenith's diagonal movement cannot click a closed door on its path.
        if (beginScanDoorOpening()) return;

        // A distant container can take longer than 30 seconds even while pathing normally.
        var player = CACHE.getPlayerCache();
        if (scanWalkProgress.stalled(player.getX(), player.getY(), player.getZ())) {
            recordPoseTrace("scan_walk_timeout", "target=" + currentContainerPos()
                    + ", idle_ticks=" + scanWalkProgress.idleTicks());
            warn("Walking timeout for container at {} (dist={}, elapsed_ticks={}, idle_ticks={})",
                currentContainerPos(), String.format("%.1f", dist),
                scanWalkProgress.elapsedTicks(), scanWalkProgress.idleTicks());
            if (tryAlternateScanApproach(target, "container_walk_timeout")) return;
            retryCurrentContainerAtTail("container_walk_timeout", Map.of(
                "container", currentContainerPos(),
                "distance", String.format("%.1f", dist),
                "elapsed_ticks", scanWalkProgress.elapsedTicks(),
                "idle_ticks", scanWalkProgress.idleTicks()));
            stopOwnedBaritoneProcess();
            advanceToNextContainer();
            return;
        }

        // Use CustomGoalProcess.isActive() — not Baritone.isActive() which depends on
        // runtime gates (chunks loaded, teleport delay) that may not have passed yet
        if (ScanPathRetryCadence.shouldHandleInactive(
                BARITONE.getCustomGoalProcess().isActive(),
                walkingTickCount, lastContainerPathRequestTick,
                PATH_RETRY_SETTLE_TICKS)) {
            if (walkRetryCount < MAX_WALK_RETRIES) {
                walkRetryCount++;
                info("Re-pathing to container at {}, {}, {} (dist={}, attempt={}, path_age_ticks={})",
                    target.x(), target.y(), target.z(), String.format("%.1f", dist),
                    walkRetryCount, walkingTickCount - lastContainerPathRequestTick);
                pathToContainer(target);
            } else {
                warn("Failed to reach container at {}, {}, {} after {} attempts (dist={}, path_age_ticks={})",
                    target.x(), target.y(), target.z(), MAX_WALK_RETRIES,
                    String.format("%.1f", dist), walkingTickCount - lastContainerPathRequestTick);
                if (tryAlternateScanApproach(target, "container_unreachable")) return;
                retryCurrentContainerAtTail("container_unreachable", Map.of(
                    "container", target.x() + ", " + target.y() + ", " + target.z(),
                    "distance", String.format("%.1f", dist),
                    "attempts", MAX_WALK_RETRIES));
                advanceToNextContainer();
            }
        }
    }

    private boolean beginScanDoorOpening() {
        if (ownedBaritoneProcess != OwnedBaritoneProcess.CUSTOM_GOAL
                || ownedBaritoneRequest == null || ownedBaritoneRequest.isCompleted()) return false;

        var executor = BARITONE.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        int movementIndex = executor.getPosition();
        var movements = executor.getPath().movements();
        if (movementIndex < 0 || movementIndex >= movements.size()) return false;
        IMovement movement = movements.get(movementIndex);
        if (!(movement instanceof MovementDiagonal)) return false;

        BlockPos src = movement.getSrc();
        BlockPos dest = movement.getDest();
        BlockPos cornerX = new BlockPos(dest.x(), src.y(), src.z());
        BlockPos cornerZ = new BlockPos(src.x(), src.y(), dest.z());
        BlockPos[] candidates = {
            dest, cornerX, cornerZ, src,
            dest.above(), cornerX.above(), cornerZ.above(), src.above()
        };
        BlockPos nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        var player = CACHE.getPlayerCache();
        for (BlockPos candidate : candidates) {
            if (attemptedScanDoors.contains(candidate)
                    || !ContainerApproach.isAtAccessPosition(
                            player.getX(), player.getY(), player.getZ(),
                            candidate.x(), candidate.y(), candidate.z())
                    || !isClosedManualDoor(candidate)) continue;
            double dx = player.getX() - (candidate.x() + 0.5);
            double dy = player.getY() + 1.62 - (candidate.y() + 0.5);
            double dz = player.getZ() - (candidate.z() + 0.5);
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < nearestDistanceSq) {
                nearest = candidate;
                nearestDistanceSq = distanceSq;
            }
        }
        if (nearest == null) return false;

        attemptedScanDoors.add(nearest);
        scanDoorOpeningPos = nearest;
        scanDoorOpeningTicks = 0;
        scanDoorInteractionDispatched = false;
        containerOpenGate.reset();
        stopOwnedBaritoneProcess();
        debugRecorder.record("scan_door_open_attempt",
            "door=" + nearest.x() + ", " + nearest.y() + ", " + nearest.z()
                + ", container=" + currentContainerPos()
                + ", movement=diagonal");
        return true;
    }

    private void tickScanDoorOpening(ContainerLocation target) {
        BlockPos door = scanDoorOpeningPos;
        if (door == null) return;
        if (!isClosedManualDoor(door)) {
            boolean opened = isOpenManualDoor(door);
            debugRecorder.record(opened ? "scan_door_opened" : "scan_door_changed",
                "door=" + door.x() + ", " + door.y() + ", " + door.z()
                    + ", container=" + currentContainerPos());
            resumeWalkingAfterDoor(target);
            return;
        }

        scanDoorOpeningTicks++;
        if (!scanDoorInteractionDispatched && prepareStandingContainerInteraction()) {
            ownInteraction(BARITONE.rightClickBlock(door.x(), door.y(), door.z()));
            scanDoorInteractionDispatched = true;
        }
        boolean rejected = scanDoorInteractionDispatched
            && ownedBaritoneRequest != null && ownedBaritoneRequest.isCompleted()
            && !ownedBaritoneRequest.getNow();
        if (rejected || scanDoorOpeningTicks >= DOOR_OPEN_TIMEOUT_TICKS) {
            debugRecorder.record("scan_door_open_unconfirmed",
                "door=" + door.x() + ", " + door.y() + ", " + door.z()
                    + ", container=" + currentContainerPos()
                    + ", reason=" + (rejected ? "interaction_rejected" : "timeout"));
            resumeWalkingAfterDoor(target);
        }
    }

    private void resumeWalkingAfterDoor(ContainerLocation target) {
        stopOwnedBaritoneProcess();
        scanDoorOpeningPos = null;
        scanDoorOpeningTicks = 0;
        scanDoorInteractionDispatched = false;
        walkingTickCount = 0;
        pathToContainer(target);
    }

    private boolean isClosedManualDoor(BlockPos pos) {
        var state = World.getBlockState(pos);
        return state.block().name().endsWith("_door")
            && state.block() != BlockRegistry.IRON_DOOR
            && Boolean.FALSE.equals(state.getProperty(BlockStateProperties.OPEN));
    }

    private boolean isOpenManualDoor(BlockPos pos) {
        var state = World.getBlockState(pos);
        return state.block().name().endsWith("_door")
            && state.block() != BlockRegistry.IRON_DOOR
            && Boolean.TRUE.equals(state.getProperty(BlockStateProperties.OPEN));
    }

    private void tickOpening() {
        if (containerDataReceived) {
            ContainerLocation opened = currentContainer();
            if (opened != null) {
                rejectedScanStances.remove(opened.posKey());
                scanAlternateAttempts.remove(opened.posKey());
            }
            if (scanOpenClickTarget != null) {
                debugRecorder.record("scan_partner_half_opened",
                        "container=" + currentContainerPos()
                                + ", clicked_half=" + scanOpenClickTarget);
            }
            if (openInteractionAttempts > 1) {
                debugRecorder.record("scan_open_recovered",
                    "container=" + currentContainerPos()
                        + ", interaction_attempts=" + openInteractionAttempts
                        + ", wait_ticks=" + openTimeoutCounter
                        + ", distance=" + String.format("%.2f", distanceToCurrentContainer()));
            }
            // Buffer ticks for container content
            state = ScanState.READING;
            tickCounter = 0;
            return;
        }

        if (!prepareStandingContainerInteraction()) return;
        openTimeoutCounter++;

        ContainerLocation target = currentContainer();
        if (target != null && isChestTarget(target) && !selectVisibleChestHalf(target)) {
            // The cached raycast is only an approach hint. Zenith may still click a
            // reachable chest half, so verify that with a bounded GUI-confirmed probe.
            if (lastOpenInteractionTick >= 0
                    && openTimeoutCounter - lastOpenInteractionTick
                    < CONTAINER_INTERACTION_ATTEMPT_TIMEOUT_TICKS) return;
            if (openInteractionAttempts < ScanChestOpenFallback.MAX_UNVERIFIED_PROBES_PER_STANCE
                    && selectUnverifiedReachableChestHalf(target, openInteractionAttempts)) {
                debugRecorder.record("scan_chest_visibility_probe",
                        "container=" + currentContainerPos()
                                + ", click_target=" + (scanOpenClickTarget == null
                                        ? new BlockPos(target.x(), target.y(), target.z())
                                        : scanOpenClickTarget)
                                + ", attempt=" + (openInteractionAttempts + 1));
                interactWithContainer(target);
                return;
            }
            reapproachScannerChest(target, "no_visible_half_after_probe");
            return;
        }
        if (target != null) maybeTryPartnerChestHalf(target);

        int effectiveOpenTimeoutTicks = Math.max(
            config.openTimeoutTicks, MIN_CONTAINER_OPEN_TIMEOUT_TICKS);
        if (openTimeoutCounter >= effectiveOpenTimeoutTicks) {
            if (target != null && isChestTarget(target)) {
                reapproachScannerChest(target, "open_timeout");
                return;
            }
            recordPoseTrace("scan_open_timeout", "target=" + currentContainerPos()
                    + ", click_target=" + scanOpenClickTarget
                    + ", interaction_attempts=" + openInteractionAttempts);
            warn("Timeout waiting for container open at {}", currentContainerPos());
            retryCurrentContainerAtTail("container_open_timeout", Map.of(
                "container", currentContainerPos(),
                "timeout_ticks", effectiveOpenTimeoutTicks,
                "interaction_attempts", openInteractionAttempts,
                "partner_half_attempted", scanOpenClickTarget != null,
                "distance", String.format("%.2f", distanceToCurrentContainer())));
            state = ScanState.CLOSING;
            tickCounter = 0;
            return;
        }

        // Let Baritone's final movement/rotation settle for a tick, then retry the vanilla
        // interaction periodically. Previously the scanner clicked exactly once on the same
        // tick that movement stopped and waited 20 seconds even when that click was missed.
        if (OpenRetryCadence.shouldInteract(
                openTimeoutCounter,
                effectiveOpenTimeoutTicks,
                CONTAINER_OPEN_RETRY_INTERVAL_TICKS)) {
            if (target != null) interactWithContainer(target);
        }
    }

    private void maybeTryPartnerChestHalf(ContainerLocation target) {
        if (!ScanChestOpenFallback.shouldTryPartner(
                openTimeoutCounter, openInteractionAttempts, scanOpenClickTarget != null)) return;
        if (target.type() != BlockEntityType.CHEST
                && target.type() != BlockEntityType.TRAPPED_CHEST) return;
        try {
            BlockPos primary = new BlockPos(target.x(), target.y(), target.z());
            var partner = ScanChestOpenFallback.partner(
                    DoubleChestIdentity.resolve(target.x(), target.y(), target.z(), true),
                    primary);
            if (partner.isEmpty()) return;
            BlockPos candidate = partner.get();
            var player = CACHE.getPlayerCache();
            if (!ContainerApproach.isWithinVanillaReach(
                    player.getX(), player.getY(), player.getZ(),
                    candidate.x(), candidate.y(), candidate.z())
                    || !ContainerAccessPlanner.visible(
                            player.getX(), player.getY(), player.getZ(),
                            candidate.x(), candidate.y(), candidate.z())) return;
            stopOwnedBaritoneProcess();
            scanOpenClickTarget = candidate;
            lastOpenInteractionTick = -1;
            recordPoseTrace("scan_partner_half_selected", "target=" + currentContainerPos()
                    + ", click_target=" + candidate);
            info("Trying verified partner half for double chest at {}", currentContainerPos());
            debugRecorder.record("scan_partner_half_attempt",
                    "container=" + currentContainerPos()
                            + ", alternate_half=" + candidate
                            + ", prior_clicks=" + openInteractionAttempts);
        } catch (RuntimeException e) {
            debug("Could not resolve partner half for {}: {}", currentContainerPos(), e.getMessage());
        }
    }

    private void tickApproaching() {
        ContainerLocation target = currentContainer();
        BlockPos goal = scanAlternateGoal;
        if (target == null || goal == null) {
            resumeCurrentContainer();
            return;
        }
        if (beginScanPositionRecoveryIfNeeded(
                ScanResumeMode.RETRY_CURRENT, "chest_approach_outside_region")) return;

        var player = CACHE.getPlayerCache();
        boolean arrived = (int) Math.floor(player.getX()) == goal.x()
                && (int) Math.floor(player.getY()) == goal.y()
                && (int) Math.floor(player.getZ()) == goal.z();
        if (arrived) {
            stopOwnedBaritoneProcess();
            scanAlternateGoal = null;
            scanAlternateProgress.reset();
            // The final Baritone step can change the raycast. Opening owns the
            // bounded interaction probe before rejecting this stance.
            selectVisibleChestHalf(target);
            state = ScanState.OPENING;
            containerOpenGate.reset();
            openTimeoutCounter = 0;
            openInteractionAttempts = 0;
            lastOpenInteractionTick = -1;
            debugRecorder.record("scan_chest_approach_arrived",
                    "container=" + currentContainerPos() + ", stance=" + goal);
            return;
        }
        if (scanAlternateProgress.stalled(goal.x(), goal.y(), goal.z(),
                player.getX(), player.getY(), player.getZ())) {
            debugRecorder.record("scan_chest_approach_stalled",
                    "container=" + currentContainerPos() + ", stance=" + goal
                            + ", elapsed_ticks=" + scanAlternateProgress.elapsedTicks()
                            + ", idle_ticks=" + scanAlternateProgress.idleTicks());
            reapproachScannerChest(target, "alternate_path_stalled");
        }
    }

    private boolean isChestTarget(ContainerLocation target) {
        return target.type() == BlockEntityType.CHEST
                || target.type() == BlockEntityType.TRAPPED_CHEST;
    }

    private boolean tryAlternateScanApproach(ContainerLocation target, String reason) {
        if (!isChestTarget(target)) return false;
        info("Trying alternate standing position for chest at {} after {}",
                currentContainerPos(), reason);
        debugRecorder.record("scan_walk_alternate_approach",
                "container=" + currentContainerPos() + ", reason=" + reason);
        reapproachScannerChest(target, reason);
        return true;
    }

    private boolean selectVisibleChestHalf(ContainerLocation target) {
        var player = CACHE.getPlayerCache();
        BlockPos primary = new BlockPos(target.x(), target.y(), target.z());
        BlockPos selected = scanOpenClickTarget == null ? primary : scanOpenClickTarget;
        if (ContainerApproach.isWithinVanillaReach(player.getX(), player.getY(), player.getZ(),
                selected.x(), selected.y(), selected.z())
                && ContainerAccessPlanner.visible(player.getX(), player.getY(), player.getZ(),
                        selected.x(), selected.y(), selected.z())) return true;

        if (scanOpenClickTarget != null
                && ContainerApproach.isWithinVanillaReach(player.getX(), player.getY(), player.getZ(),
                        primary.x(), primary.y(), primary.z())
                && ContainerAccessPlanner.visible(player.getX(), player.getY(), player.getZ(),
                        primary.x(), primary.y(), primary.z())) {
            scanOpenClickTarget = null;
            lastOpenInteractionTick = -1;
            return true;
        }
        try {
            var partner = ScanChestOpenFallback.partner(
                    DoubleChestIdentity.resolve(target.x(), target.y(), target.z(), true), primary);
            if (partner.isPresent()) {
                BlockPos other = partner.get();
                if (ContainerApproach.isWithinVanillaReach(player.getX(), player.getY(), player.getZ(),
                        other.x(), other.y(), other.z())
                        && ContainerAccessPlanner.visible(player.getX(), player.getY(), player.getZ(),
                                other.x(), other.y(), other.z())) {
                    scanOpenClickTarget = other;
                    lastOpenInteractionTick = -1;
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // A stale partner identity is not evidence of a usable click face.
        }
        return false;
    }

    private boolean selectUnverifiedReachableChestHalf(ContainerLocation target, int priorAttempts) {
        var player = CACHE.getPlayerCache();
        BlockPos primary = new BlockPos(target.x(), target.y(), target.z());
        Optional<BlockPos> partner = Optional.empty();
        try {
            partner = ScanChestOpenFallback.partner(
                    DoubleChestIdentity.resolve(target.x(), target.y(), target.z(), true),
                    primary);
        } catch (RuntimeException ignored) {
            // Unknown partner identity leaves only the primary half available.
        }
        var selected = ScanChestOpenFallback.unverifiedProbe(
                primary, partner, priorAttempts, scanOpenClickTarget != null,
                candidate -> ContainerApproach.isWithinVanillaReach(
                        player.getX(), player.getY(), player.getZ(),
                        candidate.x(), candidate.y(), candidate.z()));
        if (selected.isEmpty()) return false;
        scanOpenClickTarget = selected.get().equals(primary) ? null : selected.get();
        return true;
    }

    private void reapproachScannerChest(ContainerLocation target, String reason) {
        var player = CACHE.getPlayerCache();
        long key = target.posKey();
        Set<Long> rejected = rejectedScanStances.computeIfAbsent(key, ignored -> new HashSet<>());
        rejected.add(ContainerOpenReach.stanceKey(player.getX(), player.getY(), player.getZ()));
        if (scanAlternateGoal != null) {
            rejected.add(ContainerOpenReach.stanceKey(
                    scanAlternateGoal.x() + 0.5, scanAlternateGoal.y(), scanAlternateGoal.z() + 0.5));
        }
        stopOwnedBaritoneProcess();
        scanAlternateGoal = null;
        scanAlternateProgress.reset();

        List<BlockPos> candidates = new ArrayList<>(ContainerAccessPlanner.alternateStances(
                player.getX(), player.getY(), player.getZ(),
                target.x(), target.y(), target.z(), rejected));
        try {
            var partner = ScanChestOpenFallback.partner(
                    DoubleChestIdentity.resolve(target.x(), target.y(), target.z(), true),
                    new BlockPos(target.x(), target.y(), target.z()));
            partner.ifPresent(other -> candidates.addAll(ContainerAccessPlanner.alternateStances(
                    player.getX(), player.getY(), player.getZ(),
                    other.x(), other.y(), other.z(), rejected)));
        } catch (RuntimeException ignored) {
            // A single chest still has its primary candidates.
        }
        candidates.sort((a, b) -> Double.compare(
                scanStanceDistanceSquared(player.getX(), player.getY(), player.getZ(), a),
                scanStanceDistanceSquared(player.getX(), player.getY(), player.getZ(), b)));
        int attempted = scanAlternateAttempts.getOrDefault(key, 0);
        if (candidates.isEmpty() || attempted >= MAX_SCAN_ALTERNATE_STANCES) {
            recordPoseTrace("scan_chest_access_exhausted", "target=" + currentContainerPos()
                    + ", reason=" + reason + ", alternate_attempts=" + attempted);
            retryCurrentContainerAtTail("container_no_visible_approach", Map.of(
                    "container", currentContainerPos(), "reason", reason,
                    "alternate_attempts", attempted, "rejected_stances", rejected.size(),
                    "interaction_attempts", openInteractionAttempts));
            state = ScanState.CLOSING;
            tickCounter = 0;
            return;
        }
        scanAlternateGoal = candidates.getFirst();
        scanAlternateAttempts.put(key, attempted + 1);
        scanOpenClickTarget = null;
        state = ScanState.APPROACHING;
        debugRecorder.record("scan_chest_reapproach",
                "container=" + currentContainerPos() + ", reason=" + reason
                        + ", stance=" + scanAlternateGoal + ", attempt=" + (attempted + 1)
                        + ", candidates=" + candidates.size());
        ownCustomGoal(BARITONE.pathTo(new GoalBlock(scanAlternateGoal)));
    }

    private static double scanStanceDistanceSquared(double x, double y, double z, BlockPos stance) {
        double dx = x - (stance.x() + 0.5);
        double dy = y - stance.y();
        double dz = z - (stance.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void tickReading() {
        tickCounter++;

        // Enforce delay before reading
        if (tickCounter < config.scanDelayTicks) return;

        ContainerLocation loc = currentContainer();
        if (loc == null) {
            advanceToNextContainer();
            return;
        }

        boolean isDouble = isDoubleChest(loc);
        boolean success = containerReader.readOpenContainer(loc, isDouble);

        if (success) {
            containersIndexed++;
            settledContainerKeys.add(loc.posKey());
            markCurrentContainerRecovered();
        } else {
            warn("Failed to read container at {}", currentContainerPos());
            retryCurrentContainerAtTail("container_read_failed", Map.of(
                "container", currentContainerPos()));
        }

        state = ScanState.CLOSING;
        tickCounter = 0;
    }

    private void tickClosing() {
        tickCounter++;

        // Close on first tick
        if (tickCounter == 1) {
            closeCurrentContainer();
        }

        // Brief pause after close
        if (tickCounter >= 3) {
            advanceToNextContainer();
        }
    }

    private void tickWalkingToZone() {
        if (beginScanPositionRecoveryIfNeeded(
                ScanResumeMode.RESCAN_ZONE, "zone_waypoint_outside_region")) return;
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            state = ScanState.ZONE_SCANNING;
        }
    }

    private void tickReturning() {
        double dist = distanceToStart();
        if (dist <= 3.0) {
            stopOwnedBaritoneProcess();
            if (resumeScanAfterReturn) {
                ScanResumeMode resumeMode = returnResumeMode;
                resumeScanAfterReturn = false;
                returnPathAttempts = 0;
                info("Recovered scan position at the recorded start; resuming {}", resumeMode);
                debugRecorder.record("scan_position_recovered",
                        "resume_mode=" + resumeMode
                                + ", start_position="
                                + String.format("%.1f, %.1f, %.1f", startX, startY, startZ));
                resumeScanCheckpoint(resumeMode);
                return;
            }

            info("Returned to starting position: {}, {}, {}",
                String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
            inGameAlert("<green>Returned to starting position.</green>");
            fireWebhookEvent("returned_to_start", Map.of("start_position",
                String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
            notifications.sendReturnToStartCompleted(startX, startY, startZ);
            finishOrAbortAfterReturn();
            return;
        }

        if (BARITONE.getCustomGoalProcess().isActive()) return;
        if (returnPathAttempts < MAX_WALK_RETRIES) {
            returnPathAttempts++;
            info("Re-pathing to scan start (dist={}, attempt={}/{})",
                    String.format("%.1f", dist), returnPathAttempts, MAX_WALK_RETRIES);
            ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
            return;
        }

        if (resumeScanAfterReturn) {
            resumeScanAfterReturn = false;
            recordAndFireScanFailure("scan_position_recovery_failed", Map.of(
                    "distance", String.format("%.1f", dist),
                    "attempts", returnPathAttempts,
                    "start_position", String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
            abortScan("position_recovery_failed", false);
            return;
        }

        warn("Could not reach starting position (dist={}). Finishing scan.",
            String.format("%.1f", dist));
        inGameAlert("<yellow>Could not reach starting position</yellow> <gray>(dist="
            + String.format("%.1f", dist) + "). Finishing scan.</gray>");
        recordAndFireScanFailure("return_to_start_failed", Map.of(
            "distance", String.format("%.1f", dist),
            "start_position", String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
        notifications.sendReturnToStartFailed(startX, startY, startZ, dist);
        finishOrAbortAfterReturn();
    }

    private boolean scannerWasPreempted() {
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
            // A new CustomGoal request completes the scanner's old future as rejected before
            // replacing it, so an active process with a completed future is foreign.
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

    private JobContinuanceManager.Job activeResumableJob() {
        if (organizer != null && organizer.isActive()) {
            return JobContinuanceManager.Job.ORGANIZE;
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            return JobContinuanceManager.Job.SCAN;
        }
        return JobContinuanceManager.Job.NONE;
    }

    private boolean beginOrganizerYield(String reason) {
        if (organizer == null || !organizer.yieldToAutomation(reason)) return false;

        organizerPreemptionGate.yield();
        organizerPreemptionCount++;
        String detail = organizerCheckpointDetail()
                + ", reason=" + reason
                + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds)
                + ", preemption_count=" + organizerPreemptionCount;
        info("Organizer yielded to {} for at least {} seconds (checkpoint={})",
                reason, Math.max(1, config.scanPreemptionCooldownSeconds),
                organizer.getYieldedFromState());
        debugRecorder.record("organize_preempted", detail);
        if (!"connection_lost".equals(reason)) {
            fireWebhookEvent("organize_preempted", Map.ofEntries(
                    Map.entry("reason", reason),
                    Map.entry("interrupted_state", String.valueOf(organizer.getYieldedFromState())),
                    Map.entry("completed_tasks", organizer.getCompletedTasks()),
                    Map.entry("total_tasks", organizer.getTotalTasks()),
                    Map.entry("temporary_shulker_outstanding", organizer.hasTemporaryShulkerOutstanding()),
                    Map.entry("cooldown_seconds", Math.max(1, config.scanPreemptionCooldownSeconds))
            ));
        }
        return true;
    }

    private void tickOrganizerYielded() {
        organizer.tickYieldMaintenance();
        if (connectionRecoveryTracker.isPending()) return;

        String recoveryBlocker = getOrganizerCheckpointResumeBlocker();
        if (recoveryBlocker != null) {
            if (!recoveryBlocker.equals(lastOrganizerRecoveryBlocker)) {
                lastOrganizerRecoveryBlocker = recoveryBlocker;
                warn("Organizer restart checkpoint is waiting: {}", recoveryBlocker);
                debugRecorder.record("organize_checkpoint_waiting",
                        organizerCheckpointDetail() + ", reason=" + recoveryBlocker);
            }
            return;
        }
        if (lastOrganizerRecoveryBlocker != null) {
            debugRecorder.record("organize_checkpoint_ready",
                    organizerCheckpointDetail() + ", previous_reason="
                            + lastOrganizerRecoveryBlocker);
            lastOrganizerRecoveryBlocker = null;
        }
        var transition = organizerPreemptionGate.tick(isSharedAutomationBusy());
        if (transition != CooperativePreemptionGate.Transition.RESUMED) return;

        int pausedTicks = organizerPreemptionGate.elapsedTicks();
        String interruptedState = String.valueOf(organizer.getYieldedFromState());
        if (!organizer.resumeFromYield()) {
            debugRecorder.record("organize_resume_failed",
                    "interrupted_state=" + interruptedState
                            + ", resume_state=" + organizer.getState().name()
                            + ", failure_reason="
                            + Objects.toString(organizer.getLastFailureReason(), "none")
                            + ", paused_seconds=" + pausedTicks / 20);
            return;
        }

        info("Shared automation is quiet; resuming organizer checkpoint {} after {} seconds",
                interruptedState, pausedTicks / 20);
        String detail = organizerCheckpointDetail()
                + ", interrupted_state=" + interruptedState
                + ", paused_seconds=" + pausedTicks / 20
                + ", preemption_count=" + organizerPreemptionCount;
        debugRecorder.record("organize_resumed", detail);
        fireWebhookEvent("organize_resumed", Map.of(
                "interrupted_state", interruptedState,
                "resume_state", organizer.getState().name(),
                "paused_seconds", pausedTicks / 20,
                "preemption_count", organizerPreemptionCount
        ));
    }

    private String organizerCheckpointDetail() {
        if (organizer == null) return "organizer=unavailable";
        return "organizer_state=" + organizer.getState()
                + ", interrupted_state=" + organizer.getYieldedFromState()
                + ", completed_tasks=" + organizer.getCompletedTasks()
                + ", total_tasks=" + organizer.getTotalTasks()
                + ", temporary_shulker_outstanding="
                + organizer.hasTemporaryShulkerOutstanding();
    }

    private void clearProxyControlCheckpoint(JobContinuanceManager.Job job, String reason) {
        if (jobContinuanceManager.job() != job) return;

        debugRecorder.record("proxy_control_checkpoint_cleared",
                "job=" + job.name().toLowerCase()
                        + ", controller=" + controllingPlayerName
                        + ", reason=" + reason
                        + ", lost_progress=false");
        jobContinuanceManager.clear();
        controllingPlayerName = null;
    }

    private void beginScannerYield() {
        ScanState interruptedState = state;
        scanResumeMode = switch (interruptedState) {
            case CLOSING -> ScanResumeMode.ADVANCE_CURRENT;
            case ZONE_SCANNING, WALKING_TO_ZONE -> ScanResumeMode.RESCAN_ZONE;
            case RETURNING -> ScanResumeMode.RETURN_TO_START;
            default -> ScanResumeMode.RETRY_CURRENT;
        };

        // If our click was already accepted but its GUI packet has not arrived, quarantine a
        // short late-response window so it cannot cover the interrupting task's screen state.
        boolean acceptedOpenPending = interruptedState == ScanState.OPENING
            && !containerDataReceived
            && ownedBaritoneProcess == OwnedBaritoneProcess.INTERACTION
            && ownedBaritoneRequest != null
            && ownedBaritoneRequest.isCompleted()
            && ownedBaritoneRequest.getNow();
        lateOpenQuarantineTicks = acceptedOpenPending ? LATE_OPEN_QUARANTINE_TICKS : 0;

        if (interruptedState == ScanState.READING || interruptedState == ScanState.CLOSING) {
            closeCurrentContainer();
        }

        // Stop only a scanner-owned process that has not already been replaced. Calling the
        // global BARITONE.stop() here would cancel the very task we are yielding to.
        stopOwnedBaritoneProcess();
        resetScanDoorOpening();
        scanAlternateGoal = null;
        scanAlternateProgress.reset();
        clearOwnedAutomation();
        releaseBaritoneBreakingForYield();
        scannerPreemptionGate.yield();
        scanPreemptionCount++;
        state = ScanState.YIELDED;

        info("Scan yielded to shared automation for at least {} seconds (resume={})",
            Math.max(1, config.scanPreemptionCooldownSeconds), scanResumeMode);
        debugRecorder.record("scan_preempted",
            "interrupted_state=" + interruptedState
                + ", resume_mode=" + scanResumeMode
                + ", target=" + currentContainerPos()
                + ", cooldown_seconds=" + Math.max(1, config.scanPreemptionCooldownSeconds)
                + ", preemption_count=" + scanPreemptionCount);
    }

    private void tickScannerYielded() {
        if (lateOpenQuarantineTicks > 0) lateOpenQuarantineTicks--;
        if (connectionRecoveryTracker.isPending()) return;

        var transition = scannerPreemptionGate.tick(isSharedAutomationBusy());
        if (transition != CooperativePreemptionGate.Transition.RESUMED) return;

        int pausedTicks = scannerPreemptionGate.elapsedTicks();
        ScanResumeMode resumeMode = scanResumeMode;
        lateOpenQuarantineTicks = 0;
        clearOwnedAutomation();

        info("Shared automation is quiet; resuming scan checkpoint {} after {} seconds",
            resumeMode, pausedTicks / 20);
        debugRecorder.record("scan_resumed",
            "resume_mode=" + resumeMode
                + ", paused_seconds=" + pausedTicks / 20
                + ", target=" + currentContainerPos()
                + ", preemption_count=" + scanPreemptionCount);

        reacquireBaritoneBreakingAfterYield();
        if (beginScanPositionRecoveryIfNeeded(
                resumeMode, "resume_outside_region")) return;
        resumeScanCheckpoint(resumeMode);
    }

    private void resumeScanCheckpoint(ScanResumeMode resumeMode) {
        switch (resumeMode) {
            case RETRY_CURRENT -> resumeCurrentContainer();
            case ADVANCE_CURRENT -> advanceToNextContainer();
            case RESCAN_ZONE -> state = ScanState.ZONE_SCANNING;
            case RETURN_TO_START -> beginReturnPath();
        }
    }

    private void resumeCurrentContainer() {
        if (beginScanPositionRecoveryIfNeeded(
                ScanResumeMode.RETRY_CURRENT, "retry_outside_region")) return;

        ContainerLocation target = currentContainer();
        if (target == null) {
            advanceToNextContainer();
            return;
        }

        tickCounter = 0;
        openTimeoutCounter = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
        scanOpenClickTarget = null;
        scanAlternateGoal = null;
        scanAlternateProgress.reset();
        containerDataReceived = false;
        walkRetryCount = 0;
        walkingTickCount = 0;
        scanWalkProgress.reset();
        resetScanDoorOpening();
        if (isAtContainerAccessPosition(target)) {
            containerOpenGate.reset();
            state = ScanState.OPENING;
        } else {
            state = ScanState.WALKING;
            pathToContainer(target);
        }
    }

    private boolean isSharedAutomationBusy() {
        return hasAnyBaritoneProcessActive() || BARITONE.isActive() || INVENTORY.hasActiveRequest();
    }

    private boolean hasAnyBaritoneProcessActive() {
        return BARITONE.getCustomGoalProcess().isActive()
            || BARITONE.getInteractWithProcess().isActive()
            || BARITONE.getFollowProcess().isActive()
            || BARITONE.getGetToBlockProcess().isActive()
            || BARITONE.getMineProcess().isActive()
            || BARITONE.getClearAreaProcess().isActive();
    }

    private void ownCustomGoal(PathingRequestFuture request) {
        ownedBaritoneRequest = request;
        ownedBaritoneProcess = OwnedBaritoneProcess.CUSTOM_GOAL;
    }

    private void ownInteraction(PathingRequestFuture request) {
        ownedBaritoneRequest = request;
        ownedBaritoneProcess = OwnedBaritoneProcess.INTERACTION;
    }

    private void stopOwnedBaritoneProcess() {
        if (ownedBaritoneRequest != null && ownedBaritoneRequest.isCompleted()) {
            // A completed/rejected future may mean another plugin replaced the same concrete
            // process. Never stop that replacement by type.
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

    // Helpers
    private int[] scanPos1() {
        return activeScanPos1 != null ? activeScanPos1 : config.pos1;
    }

    private int[] scanPos2() {
        return activeScanPos2 != null ? activeScanPos2 : config.pos2;
    }

    private boolean isBotOutsideScanEnvelope() {
        var playerCache = CACHE.getPlayerCache();
        if (organizer != null && organizer.isActive()
                && organizer.isWithinPinnedRouteTransitEnvelope(
                        playerCache.getX(), playerCache.getY(), playerCache.getZ(),
                        SCAN_HORIZONTAL_RECOVERY_MARGIN, SCAN_VERTICAL_RECOVERY_MARGIN)) {
            return false;
        }
        boolean outside = ScanNavigationPolicy.isOutsideRegionEnvelope(
                playerCache.getX(), playerCache.getY(), playerCache.getZ(),
                scanPos1(), scanPos2(),
                SCAN_HORIZONTAL_RECOVERY_MARGIN,
                SCAN_VERTICAL_RECOVERY_MARGIN);
        if (!outside && scanTransitActive) scanTransitActive = false;
        if (!outside || !scanTransitActive) return outside;
        return ScanNavigationPolicy.isOutsideTransitEnvelope(
                playerCache.getX(), playerCache.getY(), playerCache.getZ(),
                scanTransitX, scanTransitY, scanTransitZ,
                scanPos1(), scanPos2(),
                SCAN_HORIZONTAL_RECOVERY_MARGIN, SCAN_VERTICAL_RECOVERY_MARGIN);
    }

    private boolean beginScanPositionRecoveryIfNeeded(
            ScanResumeMode resumeMode,
            String reason) {
        if (!hasStartPosition || !isBotOutsideScanEnvelope()) return false;

        var playerCache = CACHE.getPlayerCache();
        stopOwnedBaritoneProcess();
        // RETURN_TO_START already carries its own completion/abort intent. Preserve it when
        // an external task interrupts the return path; clearing these flags would turn an
        // unhealthy scan into a false DONE state after recovery.
        if (resumeMode != ScanResumeMode.RETURN_TO_START) {
            finishScanAfterReturn = false;
            resumeScanAfterReturn = true;
            returnResumeMode = resumeMode;
            abortScanAfterReturnReason = null;
        }
        info("Scan left the configured stash envelope; returning to its start before {}",
                resumeMode);
        String detail = "reason=" + reason
                + ", resume_mode=" + resumeMode
                + ", current_position=" + String.format("%.1f, %.1f, %.1f",
                        playerCache.getX(), playerCache.getY(), playerCache.getZ())
                + ", start_position=" + String.format("%.1f, %.1f, %.1f",
                        startX, startY, startZ);
        debugRecorder.record("scan_position_recovery_started", detail);
        fireWebhookEvent("scan_position_recovery_started", Map.of(
                "reason", reason,
                "resume_mode", resumeMode,
                "distance", String.format("%.1f", distanceToStart())
        ));
        beginReturnPath();
        return true;
    }

    private void beginReturnPath() {
        returnPathAttempts = 1;
        ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
        state = ScanState.RETURNING;
    }

    private double distanceToStart() {
        return Math.sqrt(
                Math.pow(CACHE.getPlayerCache().getX() - startX, 2)
                        + Math.pow(CACHE.getPlayerCache().getY() - startY, 2)
                        + Math.pow(CACHE.getPlayerCache().getZ() - startZ, 2));
    }

    private void finishOrAbortAfterReturn() {
        if (abortScanAfterReturnReason != null) {
            String reason = abortScanAfterReturnReason;
            abortScanAfterReturnReason = null;
            abortScan(reason, false);
        } else if (finishScanAfterReturn) {
            finishScan();
        } else {
            state = ScanState.DONE;
            activeScanPos1 = null;
            activeScanPos2 = null;
            activeScanRegionName = null;
            scanTransitActive = false;
        }
    }

    private void advanceToNextContainer() {
        scanOpenClickTarget = null;
        scanAlternateGoal = null;
        scanAlternateProgress.reset();
        scanWalkProgress.reset();
        currentContainerIndex++;

        if (currentContainerIndex >= pendingContainers.size()) {
            // Walk toward unscanned chunks
            var unscanned = regionScanner.getUnscannedChunks(scanPos1(), scanPos2());
            if (!unscanned.isEmpty() && containersFound < config.maxContainers) {
                // The next zone pass appends targets, then advances once. Park just
                // before the first appended entry so it cannot be skipped.
                currentContainerIndex = ScanQueueCursor.beforeAppendedTargets(pendingContainers.size());
                startWalkingToUnscannedChunk(unscanned.get(0));
                return;
            }

            info("All containers processed. Found={}, Indexed={}, Failed={}",
                containersFound, containersIndexed, containersFailed);
            int unresolved = ScanCompletionPolicy.unresolvedContainers(
                    containersFound, containersIndexed, containersFailed);
            if (unresolved > 0) {
                List<String> unsettled = pendingContainers.stream()
                        .filter(loc -> discoveredContainerKeys.contains(loc.posKey())
                                && !settledContainerKeys.contains(loc.posKey()))
                        .map(loc -> loc.x() + "," + loc.y() + "," + loc.z())
                        .distinct().limit(10).toList();
                warn("Scan has {} unresolved target(s); unsettled identities={}",
                        unresolved, unsettled);
                containersFailed = (int) Math.min(
                        Integer.MAX_VALUE,
                        (long) containersFailed + unresolved);
                debugRecorder.record("scan_unresolved_targets_counted",
                        "unresolved=" + unresolved
                                + ", unsettled_identities=" + unsettled
                                + ", disposition=counted_as_terminal_failures"
                                + ", found=" + containersFound
                                + ", indexed=" + containersIndexed
                                + ", failed=" + containersFailed);
            }
            ScanCompletionPolicy.Assessment completion = ScanCompletionPolicy.assess(
                    containersFound, containersIndexed, containersFailed);
            boolean containerLimitReached = containersFound >= config.maxContainers;
            boolean unhealthySnapshot = !completion.accepted() || containerLimitReached;
            if (unhealthySnapshot) {
                abortScanAfterReturnReason = containerLimitReached
                        ? "scan_container_limit_reached" : completion.rejectionReason();
                debugRecorder.record("scan_completion_rejected",
                        "reason=" + abortScanAfterReturnReason
                                + ", found=" + containersFound
                                + ", indexed=" + containersIndexed
                                + ", failed=" + containersFailed
                                + ", unresolved=" + completion.unresolved()
                                + ", effective_failures=" + completion.effectiveFailures()
                                + ", allowed_failures=" + completion.allowedFailures());
            }

            // Return to start if enabled
            if (config.returnToStart && hasStartPosition) {
                info("Returning to starting position: {}, {}, {}",
                    String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
                inGameAlert((unhealthySnapshot
                        ? "<yellow>Scan needs recovery.</yellow> <gray>"
                        : "<aqua>Scan complete!</aqua> <gray>")
                    + "Found=" + containersFound
                    + ", Indexed=" + containersIndexed + ", Failed=" + containersFailed
                    + ". Returning to start position...</gray>");
                finishScanAfterReturn = !unhealthySnapshot;
                resumeScanAfterReturn = false;
                beginReturnPath();
                fireWebhookEvent("return_to_start_started", Map.of("start_position",
                    String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
                return;
            }

            if (unhealthySnapshot) {
                abortScan(abortScanAfterReturnReason, false);
                return;
            }
            finishScan();
            return;
        }

        ContainerLocation next = currentContainer();
        if (next == null) {
            restoreBaritoneBreaking();
            state = ScanState.DONE;
            return;
        }

        // Skip double-chest partners
        if (next.type() == BlockEntityType.CHEST || next.type() == BlockEntityType.TRAPPED_CHEST) {
            if (isDoubleChestPartner(next)) {
                debug("Skipping double chest partner at {}, {}, {}",
                    next.x(), next.y(), next.z());
                if (discoveredContainerKeys.remove(next.posKey())) {
                    containersFound = discoveredContainerKeys.size();
                }
                advanceToNextContainer();
                return;
            }
        }

        // Stop any lingering Baritone process before starting new action
        stopOwnedBaritoneProcess();
        resetScanDoorOpening();

        double dist = distanceToContainer(next);
        walkRetryCount = 0;
        walkingTickCount = 0;
        if (isAtContainerAccessPosition(next)) {
            // Already in range
            containerOpenGate.reset();
            state = ScanState.OPENING;
            tickCounter = 0;
            openTimeoutCounter = 0;
            openInteractionAttempts = 0;
            lastOpenInteractionTick = -1;
            containerDataReceived = false;
        } else {
            state = ScanState.WALKING;
            pathToContainer(next);
            info("Walking to container {}/{} at {}, {}, {} (dist={})",
                currentContainerIndex + 1, pendingContainers.size(),
                next.x(), next.y(), next.z(), String.format("%.1f", dist));
        }
    }

    private void startWalkingToUnscannedChunk(int[] chunk) {
        int chunkCenterX = chunk[0] * 16 + 8;
        int chunkCenterZ = chunk[1] * 16 + 8;
        double playerX = CACHE.getPlayerCache().getX();
        double playerZ = CACHE.getPlayerCache().getZ();
        double dx = chunkCenterX - playerX;
        double dz = chunkCenterZ - playerZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int legLength = Math.max(16, config.waypointDistance);

        int targetX = chunkCenterX;
        int targetZ = chunkCenterZ;
        if (distance > legLength) {
            targetX = (int) Math.round(playerX + dx / distance * legLength);
            targetZ = (int) Math.round(playerZ + dz / distance * legLength);
        }

        int targetY = ScanNavigationPolicy.waypointY(
                startY,
                CACHE.getPlayerCache().getY(),
                scanPos1(),
                scanPos2());
        info("Walking toward unscanned chunk via level-aware waypoint at {}, {}, {}",
                targetX, targetY, targetZ);
        debugRecorder.record("scan_zone_waypoint_started",
                "waypoint=" + targetX + ", " + targetY + ", " + targetZ
                        + ", range_sq=" + ZONE_WAYPOINT_RANGE_SQ);
        ownCustomGoal(BARITONE.pathTo(
                new GoalNear(targetX, targetY, targetZ, ZONE_WAYPOINT_RANGE_SQ)));
        state = ScanState.WALKING_TO_ZONE;
    }

    private ContainerLocation currentContainer() {
        if (currentContainerIndex < 0 || currentContainerIndex >= pendingContainers.size()) {
            return null;
        }
        return pendingContainers.get(currentContainerIndex);
    }

    private String currentContainerPos() {
        ContainerLocation loc = currentContainer();
        return loc != null ? loc.x() + ", " + loc.y() + ", " + loc.z() : "unknown";
    }

    private double distanceToContainer(ContainerLocation loc) {
        var playerCache = CACHE.getPlayerCache();
        double dx = playerCache.getX() - loc.x();
        double dy = playerCache.getY() - loc.y();
        double dz = playerCache.getZ() - loc.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isAtContainerAccessPosition(ContainerLocation loc) {
        var playerCache = CACHE.getPlayerCache();
        return ContainerApproach.isAtAccessPosition(
            playerCache.getX(), playerCache.getY(), playerCache.getZ(),
            loc.x(), loc.y(), loc.z());
    }

    private double distanceToCurrentContainer() {
        ContainerLocation target = currentContainer();
        return target != null ? distanceToContainer(target) : Double.NaN;
    }

    private void interactWithContainer(ContainerLocation loc) {
        if (ownedBaritoneProcess == OwnedBaritoneProcess.INTERACTION
                && ownedBaritoneRequest != null
                && !ownedBaritoneRequest.isCompleted()) {
            if (lastOpenInteractionTick < 0
                    || openTimeoutCounter - lastOpenInteractionTick
                    < CONTAINER_INTERACTION_ATTEMPT_TIMEOUT_TICKS) {
                return;
            }
            stopOwnedBaritoneProcess();
        }
        BlockPos clickTarget = scanOpenClickTarget != null
                ? scanOpenClickTarget : new BlockPos(loc.x(), loc.y(), loc.z());
        openInteractionAttempts++;
        lastOpenInteractionTick = openTimeoutCounter;
        recordPoseTrace("scan_open_attempt", "target=" + currentContainerPos()
                + ", click_target=" + clickTarget
                + ", attempt=" + openInteractionAttempts
                + ", open_wait_ticks=" + openTimeoutCounter);
        ownInteraction(BARITONE.rightClickBlock(
                clickTarget.x(), clickTarget.y(), clickTarget.z()));
    }

    private boolean prepareStandingContainerInteraction() {
        if (containerOpenGate.tick(BOT.isSneaking())) return true;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().sneaking(false).build())
            .priority(SneakReleaseGate.INPUT_PRIORITY)
            .build());
        return false;
    }

    private void pathToContainer(ContainerLocation loc) {
        lastContainerPathRequestTick = walkingTickCount;
        scanWalkProgress.reset();
        ownCustomGoal(BARITONE.pathTo(new GoalGetToBlock(
                new BlockPos(loc.x(), loc.y(), loc.z()))));
    }

    private void closeCurrentContainer() {
        try {
            ownedInventoryRequest = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new CloseContainer())
                .priority(5000)
                .build());
        } catch (Exception e) {
            debug("Error closing container: {}", e.getMessage());
        }
    }

    /**
     * A failed scanner target has not transferred any inventory, so it can be retried safely.
     * Re-appending the same physical target implements a true tail retry without changing the
     * discovered-container total or pretending a transient attempt was a terminal failure.
     */
    private void retryCurrentContainerAtTail(String event, Map<String, Object> details) {
        ContainerLocation target = currentContainer();
        if (target == null) {
            containersFailed++;
            recordAndFireScanFailure(event, details);
            return;
        }

        long key = target.posKey();
        TailRetryTracker.Decision retryDecision = containerRetries.recordFailure(key);
        int attempt = retryDecision.attempt();
        boolean retryQueued = retryDecision.shouldRetry();
        if (retryQueued) {
            pendingContainers.add(target);
        } else {
            containersFailed++;
            settledContainerKeys.add(key);
            rejectedScanStances.remove(key);
            scanAlternateAttempts.remove(key);
        }

        Map<String, Object> payload = new HashMap<>(details);
        payload.put("attempt", attempt);
        payload.put("max_attempts", MAX_CONTAINER_ATTEMPTS);
        payload.put("disposition", retryQueued ? "retry_tail" : "terminal_failure");
        payload.put("unique_total", containersFound);
        payload.put("queue_entries", Math.max(0, pendingContainers.size() - currentContainerIndex - 1));
        if (!retryQueued && isChestTarget(target)) {
            String overhead = WorldGeometrySnapshot.chestOverhead(
                    target.x(), target.y(), target.z());
            payload.put("chest_overhead", overhead);
            warn("Chest at {} could not be scanned; overhead: {}", currentContainerPos(), overhead);
        }
        try {
            var player = CACHE.getPlayerCache();
            int[] clicked = scanOpenClickTarget == null ? null : new int[]{
                    scanOpenClickTarget.x(), scanOpenClickTarget.y(), scanOpenClickTarget.z()};
            geometryRecorder.record(event,
                    "attempt=" + attempt + ", disposition=" + payload.get("disposition")
                            + "; " + WorldGeometrySnapshot.capture(
                                    target.x(), target.y(), target.z(), clicked,
                                    player.getX(), player.getY(), player.getZ(),
                                    player.getYaw(), player.getPitch()));
        } catch (RuntimeException e) {
            geometryRecorder.record(event,
                    "target=" + target.x() + "," + target.y() + "," + target.z()
                            + "; geometry_unavailable=" + e.getClass().getSimpleName());
        }
        recordAndFireScanFailure(event, payload);
    }

    private void markCurrentContainerRecovered() {
        ContainerLocation target = currentContainer();
        if (target == null) return;
        int failedAttempts = containerRetries.recordSuccess(target.posKey());
        if (failedAttempts > 0) {
            debugRecorder.record("scan_target_recovered",
                "container=" + currentContainerPos()
                    + ", failed_attempts=" + failedAttempts
                    + ", indexed=" + containersIndexed
                    + ", unique_total=" + containersFound);
        }
    }

    private void recordAndFireScanFailure(String event, Map<String, Object> payload) {
        debugRecorder.record(event, formatPayloadDetail(payload));
        recordPoseTrace(event, formatPayloadDetail(payload));
        fireWebhookEvent(event, payload);
    }

    // Is this a double chest?
    private boolean isDoubleChest(ContainerLocation loc) {
        if (loc.type() != BlockEntityType.CHEST && loc.type() != BlockEntityType.TRAPPED_CHEST) {
            return false;
        }
        return DoubleChestIdentity.isDoubleChest(loc.x(), loc.y(), loc.z());
    }

    // Is this the partner half of a double chest? (skip to avoid double-indexing)
    private boolean isDoubleChestPartner(ContainerLocation loc) {
        if (!isDoubleChest(loc)) return false;

        var identity = DoubleChestIdentity.resolve(loc.x(), loc.y(), loc.z(), true);
        return identity.identityKnown()
            && (loc.x() != identity.inventoryX() || loc.z() != identity.inventoryZ());
    }

    private void finishScan() {
        // Restore Baritone config
        restoreBaritoneBreaking();
        scannerPreemptionGate = newScannerPreemptionGate();
        clearOwnedAutomation();

        boolean trustedSnapshot = ScanCompletionPolicy.trustedSnapshot(
                containersFound, containersIndexed, containersFailed)
                && containersFound < config.maxContainers;
        // Preserve partial coverage in the database; older rows still exist but are stale.
        if (database != null && database.isInitialized() && currentScanId >= 0) {
            try {
                if (trustedSnapshot) {
                    database.recordScanComplete(currentScanId, containersFound, containersIndexed, containersFailed);
                } else {
                    database.recordScanPartial(currentScanId, containersFound, containersIndexed, containersFailed);
                }
            } catch (Exception e) {
                warn("Failed to record scan completion in database: {}", e.getMessage());
                debugRecorder.record("scan_complete_db_error", "Failed to record scan completion", e);
                trustedSnapshot = false;
            }
        }
        // A partial scan may miss still-present containers. Only a complete, persisted
        // snapshot is allowed to remove rows that were not rediscovered.
        if (trustedSnapshot) {
            try {
                int pruned = index.pruneRegionExcept(scanPos1(), scanPos2(), discoveredContainerKeys);
                if (pruned > 0) {
                    info("Pruned {} stale container record(s) from the scanned region.", pruned);
                    debugRecorder.record("scan_stale_entries_pruned", "count=" + pruned);
                }
            } catch (Exception e) {
                warn("Failed to prune stale scan rows: {}", e.getMessage());
                debugRecorder.record("scan_stale_prune_failed", "Failed to prune stale scan rows", e);
            }
        } else {
            debugRecorder.record("scan_stale_prune_skipped",
                    "reason=untrusted_snapshot, found=" + containersFound
                            + ", indexed=" + containersIndexed
                            + ", failed=" + containersFailed);
        }
        boolean configuredRegionScanned = Arrays.equals(scanPos1(), config.pos1)
                && Arrays.equals(scanPos2(), config.pos2);
        if (database != null && database.isInitialized()) {
            refreshLatestScanTrust();
        } else {
            latestScanTrusted = trustedSnapshot && configuredRegionScanned;
        }

        // Fire webhook notification
        fireWebhookEvent(trustedSnapshot ? "scan_complete" : "scan_partial");
        notifications.sendScanFinished(containersFound, containersIndexed, containersFailed);

        int fifoLanes = index.detectFifoLanes().size();
        if (fifoLanes > 0) {
            info("Detected {} FIFO lane(s) (chest -> hopper -> chest).", fifoLanes);
        }

        if (organizer != null) {
            LaneCapacityReport capacity = getLaneCapacityReport();
            String capacityRegion = Objects.toString(getLaneCapacityRegionName(), "configured bounds");
            info("Lane capacity for {}: status={}, detected={}, assignable={}, required={}, spare={}, shortfall={}, protected={}",
                    capacityRegion, capacity.status(), capacity.detectedLanes(), capacity.assignableLanes(),
                    capacity.requiredStorageClasses(), capacity.spareLanes(), capacity.laneShortfall(),
                    capacity.protectedLanes());
            debugRecorder.record("lane_capacity_calculated",
                    "region=" + capacityRegion + ", status=" + capacity.status()
                            + ", detected_lanes=" + capacity.detectedLanes()
                            + ", assignable_lanes=" + capacity.assignableLanes()
                            + ", required_storage_classes=" + capacity.requiredStorageClasses()
                            + ", spare_lanes=" + capacity.spareLanes()
                            + ", lane_shortfall=" + capacity.laneShortfall()
                            + ", protected_lanes=" + capacity.protectedLanes()
                            + ", assignable_shulker_slots=" + capacity.laneStorage().totalAssignableShulkerSlots()
                            + ", required_shulker_slots=" + capacity.laneStorage().totalRequiredShulkerSlots()
                            + ", unassigned_required_shulker_slots=" + capacity.laneStorage().unassignedRequiredShulkerSlots()
                            + ", unclassified_shulkers=" + capacity.unclassifiedShulkers());
            if (capacity.status() == LaneCapacityReport.Status.READY) {
                inGameAlert("<green>Lane capacity ready.</green> <gray>Required="
                        + capacity.requiredStorageClasses() + ", Assignable="
                        + capacity.assignableLanes() + ", Spare=" + capacity.spareLanes() + "</gray>");
            } else if (capacity.status() == LaneCapacityReport.Status.INSUFFICIENT_LANES) {
                inGameAlert("<red>Insufficient storage lanes.</red> <gray>Create "
                        + capacity.laneShortfall() + " additional dedicated lane(s).</gray>");
            } else if (capacity.status() == LaneCapacityReport.Status.INSUFFICIENT_LANE_STORAGE) {
                inGameAlert("<red>Insufficient per-lane storage.</red> <gray>"
                        + capacity.laneStorage().unassigned().size()
                        + " bulk class(es) cannot fit any assignable lane.</gray>");
            } else if (capacity.status() == LaneCapacityReport.Status.NEEDS_FRESH_SCAN) {
                inGameAlert("<yellow>Lane audit is not trusted.</yellow> <gray>Unclassified shulkers="
                        + capacity.unclassifiedShulkers() + "; rescan before organizing.</gray>");
            } else if (capacity.status() == LaneCapacityReport.Status.NEEDS_FRESH_CONTAINER_SCAN) {
                inGameAlert("<yellow>Lane audit needs one fresh scan.</yellow> <gray>Legacy double-chest rows do not identify their shared inventory.</gray>");
            }
        }

        state = ScanState.DONE;
        info("Scan {}. Found={}, Indexed={}, Failed={}", trustedSnapshot ? "complete" : "partial",
            containersFound, containersIndexed, containersFailed);
        debugRecorder.record(trustedSnapshot ? "scan_completed" : "scan_partial",
            "region=" + activeScanRegionName
                + ", found=" + containersFound
                + ", indexed=" + containersIndexed
                + ", failed=" + containersFailed);
        clearProxyControlCheckpoint(JobContinuanceManager.Job.SCAN, "scan_completed");
        inGameAlert((trustedSnapshot ? "<green>Scan complete.</green>" : "<yellow>Scan partial.</yellow>")
            + " <gray>Found=" + containersFound
            + ", Indexed=" + containersIndexed + ", Failed=" + containersFailed + "</gray>");
        currentScanId = -1;
        activeScanPos1 = null;
        activeScanPos2 = null;
        activeScanRegionName = null;
        scanTransitActive = false;
        if (trustedSnapshot && !pendingScanRegions.isEmpty()) {
            // A return-path or inventory request can still be active on this tick.
            // Start the next region from a later tick only after the shared gate is quiet.
            scanRegionHandoffWaitTicks = 0;
            info("Waiting for shared automation to settle before scanning region {}",
                    pendingScanRegions.getFirst().name());
        } else if (!trustedSnapshot) {
            pendingScanRegions.clear();
            scanRegionHandoffWaitTicks = 0;
        }
    }

    private void tickPendingScanRegion() {
        var next = pendingScanRegions.getFirst();
        String blocker = getScanStartBlocker(next.pos1(), next.pos2(), true);
        if (blocker != null) {
            scanRegionHandoffWaitTicks++;
            if (scanRegionHandoffWaitTicks == 1 || scanRegionHandoffWaitTicks % 1200 == 0) {
                warn("Waiting to scan region {}: {} (wait_ticks={})",
                        next.name(), blocker, scanRegionHandoffWaitTicks);
            }
            return;
        }
        scanRegionHandoffWaitTicks = 0;
        if (startScanRegion(next, true)) {
            pendingScanRegions.removeFirst();
            return;
        }
        pendingScanRegions.clear();
        warn("Multi-region scan stopped before region {} after a failed handoff", next.name());
        inGameAlert("<yellow>Multi-region scan stopped.</yellow> <gray>Could not start "
                + next.name() + "; check the console, then scan that region explicitly.</gray>");
    }

    public void fireWebhookEvent(String event) {
        fireWebhookEvent(event, (Map<String, Object>) null);
    }

    public void fireWebhookEvent(String event, @Nullable Map<String, Object> extraFields) {
        var embed = Embed.builder()
            .title(formatEventTitle(event));
        if (isFailureEvent(event)) embed.errorColor();
        else if (isSuccessEvent(event)) embed.successColor();
        else embed.primaryColor();

        if (config.webhookIncludeDetailedPayload && extraFields != null) {
            for (var entry : extraFields.entrySet()) {
                embed.addField(formatEventTitle(entry.getKey()), String.valueOf(entry.getValue()), true);
            }
        }
        DISCORD.sendEmbedMessage(embed);
    }

    private static boolean isFailureEvent(String event) {
        return event.endsWith("_failed") || event.endsWith("_blocked") || event.endsWith("_error")
            || event.endsWith("_timeout") || event.endsWith("_miss") || event.endsWith("_rejected")
            || event.endsWith("_aborted") || event.endsWith("_partial")
            || event.contains("unreachable");
    }

    private static boolean isSuccessEvent(String event) {
        return event.endsWith("_complete") || event.endsWith("_completed") || event.endsWith("_started")
            || event.endsWith("_saved") || event.endsWith("_updated") || event.endsWith("_removed")
            || event.endsWith("_deleted") || event.startsWith("returned_");
    }

    private static String formatEventTitle(String event) {
        String[] parts = event.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String formatPayloadDetail(@Nullable Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : payload.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private void resetScanState() {
        regionScanner.reset();
        pendingContainers.clear();
        discoveredContainerKeys.clear();
        settledContainerKeys.clear();
        rejectedScanStances.clear();
        scanAlternateAttempts.clear();
        containerRetries.clear();
        currentContainerIndex = -1;
        tickCounter = 0;
        openTimeoutCounter = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
        scanOpenClickTarget = null;
        scanAlternateGoal = null;
        scanAlternateProgress.reset();
        containerDataReceived = false;
        containersFound = 0;
        containersIndexed = 0;
        containersFailed = 0;
        currentScanId = -1;
        hasStartPosition = false;
        finishScanAfterReturn = false;
        resumeScanAfterReturn = false;
        returnResumeMode = ScanResumeMode.RETRY_CURRENT;
        abortScanAfterReturnReason = null;
        returnPathAttempts = 0;
        walkRetryCount = 0;
        walkingTickCount = 0;
        lastContainerPathRequestTick = 0;
        scanWalkProgress.reset();
        resetScanDoorOpening();
        scannerPreemptionGate = newScannerPreemptionGate();
        clearOwnedAutomation();
        scanResumeMode = ScanResumeMode.RETRY_CURRENT;
        lateOpenQuarantineTicks = 0;
        scanPreemptionCount = 0;
    }

    private void resetScanDoorOpening() {
        scanDoorOpeningPos = null;
        scanDoorOpeningTicks = 0;
        scanDoorInteractionDispatched = false;
        attemptedScanDoors.clear();
    }

    private void refreshLatestScanTrust() {
        latestScanTrusted = false;
        if (database == null || !database.isInitialized()
                || config.pos1 == null || config.pos2 == null) {
            return;
        }
        try {
            latestScanTrusted = database.getLatestScanSummary(config.pos1, config.pos2)
                    .map(summary -> summary.completed()
                            && ScanCompletionPolicy.trustedSnapshot(
                                    summary.found(), summary.indexed(), summary.failed()))
                    .orElse(false);
            if (!latestScanTrusted && index.size() > 0) {
                debugRecorder.record("scan_snapshot_untrusted",
                        "reason=latest_scan_incomplete_or_unhealthy, indexed_containers=" + index.size());
            }
        } catch (Exception e) {
            warn("Failed to verify latest scan status: {}", e.getMessage());
            debugRecorder.record("scan_trust_check_failed", "Failed to verify latest scan status", e);
        }
    }

    private CooperativePreemptionGate newScannerPreemptionGate() {
        return new CooperativePreemptionGate(
            Math.max(1, config.scanPreemptionCooldownSeconds) * 20,
            SCAN_PREEMPTION_QUIET_TICKS);
    }

    private CooperativePreemptionGate newOrganizerPreemptionGate() {
        return new CooperativePreemptionGate(
                Math.max(1, config.scanPreemptionCooldownSeconds) * 20,
                SCAN_PREEMPTION_QUIET_TICKS);
    }

    private void recordOrganizerGeometry(String event, Map<String, Object> payload) {
        int[] target = parseDebugPosition(payload.get("walk_target"));
        if (target == null) return;
        try {
            var player = CACHE.getPlayerCache();
            geometryRecorder.record(event,
                    "state=" + stringValue(payload, "organizer_state")
                            + ", reason=" + stringValue(payload, "reason")
                            + "; " + WorldGeometrySnapshot.capture(
                                    target[0], target[1], target[2], null,
                                    player.getX(), player.getY(), player.getZ(),
                                    player.getYaw(), player.getPitch()));
        } catch (RuntimeException e) {
            geometryRecorder.record(event,
                    "target=" + target[0] + "," + target[1] + "," + target[2]
                            + "; geometry_unavailable=" + e.getClass().getSimpleName());
        }
    }

    private void recordKitRetrievalGeometry(String event, Map<String, Object> payload) {
        int[] target = parseDebugPosition(payload.get("target_position"));
        if (target == null) return;
        int[] clicked = parseDebugPosition(payload.get("opening_click_target"));
        if (clicked == null) clicked = parseDebugPosition(payload.get("partner_position"));
        try {
            var player = CACHE.getPlayerCache();
            geometryRecorder.record(event,
                    "request=" + stringValue(payload, "request_name")
                            + ", reason=" + (payload.containsKey("failure_reason")
                                    ? stringValue(payload, "failure_reason")
                                    : stringValue(payload, "reason"))
                            + "; " + WorldGeometrySnapshot.capture(
                                    target[0], target[1], target[2], clicked,
                                    player.getX(), player.getY(), player.getZ(),
                                    player.getYaw(), player.getPitch()));
        } catch (RuntimeException e) {
            geometryRecorder.record(event,
                    "target=" + Arrays.toString(target)
                            + "; geometry_unavailable=" + e.getClass().getSimpleName());
        }
    }

    private static @Nullable int[] parseDebugPosition(@Nullable Object value) {
        if (!(value instanceof String text)) return null;
        String[] parts = text.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void handleAutomationEvent(String event, Map<String, Object> payload) {
        kitFulfillment.onRetrievalEvent(event, payload);
        if ("organize_failed".equals(event) && poseTraceRecorder.isEnabled()) {
            recordPoseTrace(event, formatPayloadDetail(payload));
            try {
                poseTraceRecorder.saveSnapshot(Path.of("plugins", "config",
                        "stash-manager-pose-trace-last-failure.log"));
            } catch (IOException e) {
                debugRecorder.record("pose_trace_snapshot_failed", e.getClass().getSimpleName());
            }
        }
        if (("organize_target_failed".equals(event) || "organize_failed".equals(event))
                && ("WALKING".equals(stringValue(payload, "organizer_state"))
                        || "OPENING".equals(stringValue(payload, "organizer_state")))) {
            recordOrganizerGeometry(event, payload);
        }
        boolean foodContingencyEvent = FOOD_CONTINGENCY_REQUEST.equals(
                stringValue(payload, "request_name"));
        String requestName = stringValue(payload, "request_name");
        boolean kitRetrievalEvent = requestName != null
                && requestName.startsWith("kit_fulfillment:");
        if (kitRetrievalEvent && ("retrieve_target_failed".equals(event)
                || "retrieve_partner_half_retry".equals(event))) {
            recordKitRetrievalGeometry(event, payload);
        }
        // Every transition remains in console/debug. Discord receives only job starts and
        // actionable blockers here; completion/failure use the richer dedicated embeds below.
        if (!foodContingencyEvent && !kitRetrievalEvent
                && AutomationNotificationPolicy.sendGenericDiscord(event, payload)) {
            fireWebhookEvent(event, payload);
        }
        // Keep successful transitions too. Long headless jobs need a usable baseline even when
        // nothing has failed yet, especially before a pause/resume handoff.
        debugRecorder.record(event, formatPayloadDetail(payload));
        if (kitRetrievalEvent) return;
        if (reequipActive && reequipRequestName.equals(stringValue(payload, "request_name"))) {
            if ("retrieve_completed".equals(event) || "retrieve_incomplete".equals(event)) {
                reequipRetrievalDone = true;
                reequipPartial |= "retrieve_incomplete".equals(event);
                reequipRetrievalResult = reequipFoodPhase
                        ? "food:" + event
                        : reequipRetrievalResult.startsWith("food:")
                            ? reequipRetrievalResult + ",gear:" + event : event;
                reequipWaitTicks = 0;
            } else if ("retrieve_stopped".equals(event)) {
                resetKeepReequip();
            }
        }
        if (foodContingencyEvent) {
            if ("retrieve_completed".equals(event)
                    || "retrieve_incomplete".equals(event)
                    || "retrieve_no_targets".equals(event)) {
                handleFoodContingencyRetrievalTerminal(event, payload);
            }
            return;
        }
        switch (event) {
            case "retrieve_completed" -> notifications.sendRetrievalFinished(
                stringValue(payload, "request_name"),
                true,
                intValue(payload, "moved_stacks"),
                intValue(payload, "obtained_total"),
                intValue(payload, "remaining_total"),
                null
            );
            case "retrieve_incomplete" -> notifications.sendRetrievalFinished(
                stringValue(payload, "request_name"),
                false,
                intValue(payload, "moved_stacks"),
                intValue(payload, "obtained_total"),
                intValue(payload, "remaining_total"),
                stringValue(payload, "reason")
            );
            case "organize_completed" -> {
                organizerPreemptionGate.reset();
                organizerPickupRecoveryDeferred = false;
                clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE,
                        "organize_completed");
                notifications.sendOrganizerFinished(
                    intValue(payload, "completed_tasks"),
                    intValue(payload, "total_tasks"),
                    intValue(payload, "overflow_types"),
                    intValue(payload, "staged_shulkers"),
                    intValue(payload, "staged_storage_classes"),
                    intValue(payload, "permanent_lane_gaps"),
                    intValue(payload, "scan_failed_targets"),
                    booleanValue(payload, "partial_consolidation_candidates_remaining")
                );
            }
            case "organize_inventory_reconciliation_completed" -> {
                organizerPreemptionGate.reset();
                organizerPickupRecoveryDeferred = false;
                clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE,
                        "organize_inventory_reconciliation_completed");
                notifications.sendKitInventoryReconciliationFinished(
                        intValue(payload, "completed_tasks"),
                        intValue(payload, "total_tasks"),
                        intValue(payload, "staged_shulkers"));
            }
            case "organize_failed" -> {
                if (!booleanValue(payload, "terminal")) break;
                organizerPreemptionGate.reset();
                organizerPickupRecoveryDeferred = false;
                clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE,
                        "organize_failed");
                notifications.sendOrganizerFailed(
                        intValue(payload, "completed_tasks"),
                        intValue(payload, "total_tasks"),
                        stringValue(payload, "reason"),
                        booleanValue(payload, "checkpoint_preserved"),
                        booleanValue(payload, "cargo_preserved"),
                        stringValue(payload, "cargo_state"));
            }
            case "organize_aborted", "organize_stopped" -> {
                organizerPreemptionGate.reset();
                organizerPickupRecoveryDeferred = false;
                clearProxyControlCheckpoint(JobContinuanceManager.Job.ORGANIZE, event);
            }
            default -> {
            }
        }
    }

    private int intValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private @Nullable String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Map<String, Object> payload, String key) {
        if (payload == null) return false;
        Object value = payload.get(key);
        return value instanceof Boolean bool
                ? bool
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private @Nullable String getAutomationUnavailableReason() {
        var proxy = Proxy.getInstance();
        if (!proxy.isConnected()) {
            return "bot is not connected";
        }
        if (proxy.hasActivePlayer()) {
            return "a player is currently controlling the proxy";
        }
        return null;
    }

    // Save Baritone allowBreak and disable it.
    private void saveAndDisableBaritoneBreaking() {
        var pathfinderConfig = CONFIG.client.extra.pathfinder;
        savedAllowBreak = pathfinderConfig.allowBreak;
        baritoneConfigSaved = true;
        enforceBaritoneBreakingDisabled();
        info("Baritone block breaking disabled for scan (was={})", savedAllowBreak);
    }

    private void enforceBaritoneBreakingDisabled() {
        if (baritoneConfigSaved) {
            CONFIG.client.extra.pathfinder.allowBreak = false;
        }
    }

    private void releaseBaritoneBreakingForYield() {
        if (baritoneConfigSaved) {
            CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
        }
    }

    private void reacquireBaritoneBreakingAfterYield() {
        enforceBaritoneBreakingDisabled();
    }

    // Restore Baritone allowBreak.
    private void restoreBaritoneBreaking() {
        if (!baritoneConfigSaved) return;
        CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
        baritoneConfigSaved = false;
        info("Baritone block breaking restored (allowBreak={})", savedAllowBreak);
    }

    private String formatPos(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }
}
