package com.zenith.plugin.stashmanager;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientLoginFailedEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.client.ClientStartConnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.event.module.AutoReconnectEvent;
import com.zenith.event.module.HealthAutoDisconnectEvent;
import com.zenith.event.player.PlayerLoginEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.debug.DebugRecorder;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.orchestration.CooperativePreemptionGate;
import com.zenith.plugin.stashmanager.orchestration.ConnectionRecoveryTracker;
import com.zenith.plugin.stashmanager.orchestration.JobContinuanceManager;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.OpenRetryCadence;
import com.zenith.plugin.stashmanager.orchestration.ScanCompletionPolicy;
import com.zenith.plugin.stashmanager.orchestration.ScanNavigationPolicy;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.orchestration.TailRetryTracker;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.plugin.stashmanager.retriever.StashRetriever;
import com.zenith.plugin.stashmanager.scanner.ContainerReader;
import com.zenith.plugin.stashmanager.scanner.RegionScanner;
import com.zenith.plugin.stashmanager.scanner.RegionScanner.ContainerLocation;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import com.zenith.plugin.stashmanager.travel.tunnel.network.sync.SyncWorker;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final StashManagerNotifications notifications = new StashManagerNotifications();

    // Organizer integration
    private StashOrganizer organizer;
    private final StashRetriever retriever;

    private volatile ScanState state = ScanState.IDLE;
    private List<ContainerLocation> pendingContainers = new ArrayList<>();
    private int currentContainerIndex = 0;
    private int tickCounter = 0;
    private int openTimeoutCounter = 0;
    private int openInteractionAttempts = 0;
    private int lastOpenInteractionTick = -1;
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
    private volatile @Nullable String controllingPlayerName;
    private static final int SCAN_PREEMPTION_QUIET_TICKS = 40;
    private static final int LATE_OPEN_QUARANTINE_TICKS = 100;

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
    private static final int WALK_TIMEOUT_TICKS = 600; // 30 seconds at 20tps

    // Statistics
    private int containersFound = 0;
    private int containersIndexed = 0;
    private int containersFailed = 0;

    // Database integration
    private DatabaseManager database;
    private long currentScanId = -1;
    private boolean latestScanTrusted = false;

    // Tunnel network sync
    private final SyncWorker tunnelNetworkSyncWorker;

    // Exportable troubleshooting log (see /stash debug)
    private final DebugRecorder debugRecorder = new DebugRecorder();

    public StashManagerModule(StashManagerConfig config, ContainerIndex index) {
        this.config = config;
        this.index = index;
        this.scannerPreemptionGate = newScannerPreemptionGate();
        this.organizerPreemptionGate = newOrganizerPreemptionGate();
        this.regionScanner = new RegionScanner();
        this.containerReader = new ContainerReader(index);
        this.retriever = new StashRetriever();
        this.retriever.setEventCallback(this::handleAutomationEvent);
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
        return organizer == null
                ? LaneCapacityReport.unavailable(LaneCapacityReport.Status.NO_SCANNED_CONTAINERS)
                : organizer.calculateLaneCapacity();
    }

    public StashRetriever getRetriever() {
        return retriever;
    }

    public DebugRecorder getDebugRecorder() {
        return debugRecorder;
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
            of(AutoReconnectEvent.class, this::onAutoReconnectScheduled),
            of(ClientStartConnectEvent.class, this::onClientStartConnect),
            of(ClientConnectEvent.class, this::onClientConnect),
            of(ClientLoginFailedEvent.class, this::onClientLoginFailed),
            of(ClientOnlineEvent.class, this::onClientOnline)
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
                    } else if ((state == ScanState.CLOSING
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
                organizerPreemptionGate = newOrganizerPreemptionGate();
                organizerPreemptionGate.yield();
                organizerPreemptionCount = 1;
                organizerPickupRecoveryDeferred = false;
                lastOrganizerRecoveryBlocker = null;
                info("Organizer restart checkpoint armed; resume will wait for the configured cooldown and a quiet automation window");
            } else if (restored == StashOrganizer.DurableRestoreResult.INVALID) {
                warn("Organizer restart checkpoint needs attention: {}",
                        organizer.getDurableRecoveryError());
            }
        }
        if (activeResumableJob() != JobContinuanceManager.Job.NONE
                && !Proxy.getInstance().isConnected()) {
            beginConnectionOutage("module_enabled_while_disconnected", false, "module_enable");
        }
        info("StashManager module enabled");
    }

    @Override
    public void onDisable() {
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
        if (isBotOutsideScanEnvelope()) {
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
            case NO_SCANNED_CONTAINERS -> "no scanned containers in the configured region";
            case NO_LANES_DETECTED -> shortageCanStage
                    ? null
                    : "no storage lanes detected in the configured region; register an import chest for temporary staging";
        };
    }

    private boolean hasImportStagingInventory() {
        if (config.pos1 == null || config.pos2 == null) return false;
        return index.getInRegion(config.pos1, config.pos2).stream().anyMatch(index::isImportChest);
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
        return organizer.start();
    }

    /** Cancel organizer work and retire any cooldown gate owned by that job. */
    public boolean stopOrganizer() {
        if (organizer == null || !organizer.isActive()) return false;
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
        if (organizer == null) return false;
        refreshLatestScanTrust();
        if (getOrganizerCheckpointResumeBlocker() != null) return false;
        if (!organizer.isDurableRecoveryLoaded()) {
            StashOrganizer.DurableRestoreResult restored = organizer.restoreDurableCheckpoint();
            if (restored != StashOrganizer.DurableRestoreResult.RESTORED) return false;
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionGate.yield();
            lastOrganizerRecoveryBlocker = null;
        }
        if (!organizer.isYielded()) return false;
        if (getOrganizerCheckpointResumeBlocker() != null) return false;
        if (!organizerPreemptionGate.isYielded()) {
            organizerPreemptionGate = newOrganizerPreemptionGate();
            organizerPreemptionGate.yield();
        }
        return true;
    }

    public @Nullable String getOrganizerCheckpointResumeBlocker() {
        if (!latestScanTrusted) return "latest_scan_incomplete_or_unhealthy";
        return organizer == null ? "organizer_unavailable" : organizer.getDurableResumeBlocker();
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
            getReservedContainerKeys()
        );
    }

    private Set<Long> getReservedContainerKeys() {
        Set<Long> reserved = new HashSet<>();
        for (int[] pos : config.supplyChests) {
            if (pos != null && pos.length >= 3) reserved.add(posKey(pos[0], pos[1], pos[2]));
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
        String blocker = getScanStartBlocker();
        if (blocker != null) {
            warn("Cannot start scan: {}", blocker);
            recordAndFireScanFailure("scan_start_blocked", Map.of("reason", blocker));
            return false;
        }

        resetScanState();
        latestScanTrusted = false;

        // Clear any existing Baritone state before starting
        BARITONE.stop();

        // Record start position for return nav
        var playerCache = CACHE.getPlayerCache();
        startX = playerCache.getX();
        startY = playerCache.getY();
        startZ = playerCache.getZ();
        hasStartPosition = true;
        info("Recorded starting position: {}, {}, {}",
            String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));

        // Disable Baritone block breaking during scan
        saveAndDisableBaritoneBreaking();

        // Record scan in DB
        if (database != null && database.isInitialized()) {
            try {
                currentScanId = database.recordScanStart(config.pos1, config.pos2);
            } catch (Exception e) {
                warn("Failed to record scan start in database: {}", e.getMessage());
                debugRecorder.record("scan_start_db_error", "Failed to record scan start", e);
                fireWebhookEvent("scan_start_db_error", Map.of("message", e.getMessage()));
            }
        }

        state = ScanState.ZONE_SCANNING;
        info("Starting container scan in region ({}) to ({})",
            formatPos(config.pos1), formatPos(config.pos2));
        debugRecorder.record("scan_started",
            "pos1=" + formatPos(config.pos1)
                + ", pos2=" + formatPos(config.pos2)
                + ", start_position=" + String.format("%.1f, %.1f, %.1f", startX, startY, startZ));
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

    public @Nullable String getScanStartBlocker() {
        if (config.pos1 == null || config.pos2 == null) {
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
        return isBotOutsideScanEnvelope()
                ? "bot is outside the configured stash area; move it near the region before scanning"
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

    private void onTick(ClientBotTick event) {
        // Tick TravelManager independently (it manages its own state)
        com.zenith.plugin.stashmanager.travel.TravelManager.get().tick();
        tunnelNetworkSyncWorker.tick();

        // Delegate tick to organizer when active
        if (organizer != null && organizer.isActive()) {
            if (organizer.isYielded()) {
                tickOrganizerYielded();
                return;
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
                config.pos1, config.pos2, remainingCapacity, getReservedContainerKeys())
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
        var unscanned = regionScanner.getUnscannedChunks(config.pos1, config.pos2);
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

        double dist = distanceToContainer(target);

        // Always check distance — may have arrived regardless of Baritone state
        if (isAtContainerAccessPosition(target)) {
            stopOwnedBaritoneProcess();
            walkRetryCount = 0;
            state = ScanState.OPENING;
            containerOpenGate.reset();
            tickCounter = 0;
            openTimeoutCounter = 0;
            openInteractionAttempts = 0;
            lastOpenInteractionTick = -1;
            containerDataReceived = false;
            return;
        }

        // Timeout failsafe
        if (walkingTickCount >= WALK_TIMEOUT_TICKS) {
            warn("Walking timeout for container at {} (dist={})",
                currentContainerPos(), String.format("%.1f", dist));
            retryCurrentContainerAtTail("container_walk_timeout", Map.of(
                "container", currentContainerPos(),
                "distance", String.format("%.1f", dist)));
            stopOwnedBaritoneProcess();
            advanceToNextContainer();
            return;
        }

        // Use CustomGoalProcess.isActive() — not Baritone.isActive() which depends on
        // runtime gates (chunks loaded, teleport delay) that may not have passed yet
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            if (walkRetryCount < MAX_WALK_RETRIES) {
                walkRetryCount++;
                info("Re-pathing to container at {}, {}, {} (dist={}, attempt={})",
                    target.x(), target.y(), target.z(), String.format("%.1f", dist), walkRetryCount);
                pathToContainer(target);
            } else {
                warn("Failed to reach container at {}, {}, {} after {} attempts (dist={})",
                    target.x(), target.y(), target.z(), MAX_WALK_RETRIES, String.format("%.1f", dist));
                retryCurrentContainerAtTail("container_unreachable", Map.of(
                    "container", target.x() + ", " + target.y() + ", " + target.z(),
                    "distance", String.format("%.1f", dist),
                    "attempts", MAX_WALK_RETRIES));
                advanceToNextContainer();
            }
        }
    }

    private void tickOpening() {
        if (containerDataReceived) {
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

        int effectiveOpenTimeoutTicks = Math.max(
            config.openTimeoutTicks, MIN_CONTAINER_OPEN_TIMEOUT_TICKS);
        if (openTimeoutCounter >= effectiveOpenTimeoutTicks) {
            warn("Timeout waiting for container open at {}", currentContainerPos());
            retryCurrentContainerAtTail("container_open_timeout", Map.of(
                "container", currentContainerPos(),
                "timeout_ticks", effectiveOpenTimeoutTicks,
                "interaction_attempts", openInteractionAttempts,
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
            ContainerLocation target = currentContainer();
            if (target != null) interactWithContainer(target);
        }
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
        containerDataReceived = false;
        walkRetryCount = 0;
        walkingTickCount = 0;
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
    private boolean isBotOutsideScanEnvelope() {
        var playerCache = CACHE.getPlayerCache();
        return ScanNavigationPolicy.isOutsideRegionEnvelope(
                playerCache.getX(), playerCache.getY(), playerCache.getZ(),
                config.pos1, config.pos2,
                SCAN_HORIZONTAL_RECOVERY_MARGIN,
                SCAN_VERTICAL_RECOVERY_MARGIN);
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
        }
    }

    private void advanceToNextContainer() {
        currentContainerIndex++;

        if (currentContainerIndex >= pendingContainers.size()) {
            // Walk toward unscanned chunks
            var unscanned = regionScanner.getUnscannedChunks(config.pos1, config.pos2);
            if (!unscanned.isEmpty() && containersFound < config.maxContainers) {
                startWalkingToUnscannedChunk(unscanned.get(0));
                return;
            }

            info("All containers processed. Found={}, Indexed={}, Failed={}",
                containersFound, containersIndexed, containersFailed);
            int unresolved = ScanCompletionPolicy.unresolvedContainers(
                    containersFound, containersIndexed, containersFailed);
            if (unresolved > 0) {
                containersFailed = (int) Math.min(
                        Integer.MAX_VALUE,
                        (long) containersFailed + unresolved);
                debugRecorder.record("scan_unresolved_targets_counted",
                        "unresolved=" + unresolved
                                + ", disposition=counted_as_terminal_failures"
                                + ", found=" + containersFound
                                + ", indexed=" + containersIndexed
                                + ", failed=" + containersFailed);
            }
            ScanCompletionPolicy.Assessment completion = ScanCompletionPolicy.assess(
                    containersFound, containersIndexed, containersFailed);
            boolean unhealthySnapshot = !completion.accepted();
            if (unhealthySnapshot) {
                abortScanAfterReturnReason = completion.rejectionReason();
                debugRecorder.record("scan_completion_rejected",
                        "reason=" + completion.rejectionReason()
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
                config.pos1,
                config.pos2);
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
        // Right-click the container block at exact coordinates
        openInteractionAttempts++;
        lastOpenInteractionTick = openTimeoutCounter;
        ownInteraction(BARITONE.rightClickBlock(loc.x(), loc.y(), loc.z()));
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
        ownCustomGoal(BARITONE.pathTo(new GoalGetToBlock(new BlockPos(loc.x(), loc.y(), loc.z()))));
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
        }

        Map<String, Object> payload = new HashMap<>(details);
        payload.put("attempt", attempt);
        payload.put("max_attempts", MAX_CONTAINER_ATTEMPTS);
        payload.put("disposition", retryQueued ? "retry_tail" : "terminal_failure");
        payload.put("unique_total", containersFound);
        payload.put("queue_entries", Math.max(0, pendingContainers.size() - currentContainerIndex - 1));
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

        // A scan is a snapshot of the configured region, not an append-only observation log.
        // Remove entries no longer discovered, including obsolete double-chest partner rows.
        // Failed targets remain in discoveredContainerKeys and therefore retain any prior data.
        try {
            int pruned = index.pruneRegionExcept(config.pos1, config.pos2, discoveredContainerKeys);
            if (pruned > 0) {
                info("Pruned {} stale container record(s) from the scanned region.", pruned);
                debugRecorder.record("scan_stale_entries_pruned", "count=" + pruned);
            }
        } catch (Exception e) {
            warn("Failed to prune stale scan rows: {}", e.getMessage());
            debugRecorder.record("scan_stale_prune_failed", "Failed to prune stale scan rows", e);
        }

        // Record scan completion in DB
        if (database != null && database.isInitialized() && currentScanId >= 0) {
            try {
                database.recordScanComplete(currentScanId, containersFound, containersIndexed, containersFailed);
            } catch (Exception e) {
                warn("Failed to record scan completion in database: {}", e.getMessage());
                debugRecorder.record("scan_complete_db_error", "Failed to record scan completion", e);
            }
        }
        latestScanTrusted = true;

        // Fire webhook notification
        fireWebhookEvent("scan_complete");
        notifications.sendScanFinished(containersFound, containersIndexed, containersFailed);

        int fifoLanes = index.detectFifoLanes().size();
        if (fifoLanes > 0) {
            info("Detected {} FIFO lane(s) (chest -> hopper -> chest).", fifoLanes);
        }

        if (organizer != null) {
            LaneCapacityReport capacity = organizer.calculateLaneCapacity();
            info("Lane capacity: status={}, detected={}, assignable={}, required={}, spare={}, shortfall={}, protected={}",
                    capacity.status(), capacity.detectedLanes(), capacity.assignableLanes(),
                    capacity.requiredStorageClasses(), capacity.spareLanes(), capacity.laneShortfall(),
                    capacity.protectedLanes());
            debugRecorder.record("lane_capacity_calculated",
                    "status=" + capacity.status()
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
        info("Scan complete. Found={}, Indexed={}, Failed={}",
            containersFound, containersIndexed, containersFailed);
        debugRecorder.record("scan_completed",
            "found=" + containersFound
                + ", indexed=" + containersIndexed
                + ", failed=" + containersFailed);
        clearProxyControlCheckpoint(JobContinuanceManager.Job.SCAN, "scan_completed");
        inGameAlert("<green>Scan complete.</green> <gray>Found=" + containersFound
            + ", Indexed=" + containersIndexed + ", Failed=" + containersFailed + "</gray>");
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

        if (extraFields != null) {
            for (var entry : extraFields.entrySet()) {
                embed.addField(formatEventTitle(entry.getKey()), String.valueOf(entry.getValue()), true);
            }
        }
        DISCORD.sendEmbedMessage(embed);
    }

    private static boolean isFailureEvent(String event) {
        return event.endsWith("_failed") || event.endsWith("_blocked") || event.endsWith("_error")
            || event.endsWith("_timeout") || event.endsWith("_miss") || event.endsWith("_rejected")
            || event.endsWith("_aborted") || event.contains("unreachable");
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
        containerRetries.clear();
        currentContainerIndex = -1;
        tickCounter = 0;
        openTimeoutCounter = 0;
        openInteractionAttempts = 0;
        lastOpenInteractionTick = -1;
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
        scannerPreemptionGate = newScannerPreemptionGate();
        clearOwnedAutomation();
        scanResumeMode = ScanResumeMode.RETRY_CURRENT;
        lateOpenQuarantineTicks = 0;
        scanPreemptionCount = 0;
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
                            && !ScanCompletionPolicy.shouldAbort(
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

    private void handleAutomationEvent(String event, Map<String, Object> payload) {
        // Every transition remains in console/debug. Discord receives only job starts and
        // actionable blockers here; completion/failure use the richer dedicated embeds below.
        if (AutomationNotificationPolicy.sendGenericDiscord(event, payload)) {
            fireWebhookEvent(event, payload);
        }
        // Keep successful transitions too. Long headless jobs need a usable baseline even when
        // nothing has failed yet, especially before a pause/resume handoff.
        debugRecorder.record(event, formatPayloadDetail(payload));
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
                    intValue(payload, "permanent_lane_gaps")
                );
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
