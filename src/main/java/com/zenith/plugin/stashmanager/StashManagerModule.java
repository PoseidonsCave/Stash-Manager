package com.zenith.plugin.stashmanager;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
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
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.OpenRetryCadence;
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
    private boolean containerDataReceived = false;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    // Cooperative ownership of Zenith's global Baritone and InventoryManager. Futures let us
    // distinguish scanner work from a request submitted by PearlPlus or another plugin.
    private CooperativePreemptionGate scannerPreemptionGate;
    private @Nullable PathingRequestFuture ownedBaritoneRequest;
    private OwnedBaritoneProcess ownedBaritoneProcess = OwnedBaritoneProcess.NONE;
    private @Nullable RequestFuture ownedInventoryRequest;
    private ScanResumeMode scanResumeMode = ScanResumeMode.RETRY_CURRENT;
    private boolean resumeAbortedReturn = false;
    private int lateOpenQuarantineTicks = 0;
    private int scanPreemptionCount = 0;
    private static final int SCAN_PREEMPTION_QUIET_TICKS = 40;
    private static final int LATE_OPEN_QUARANTINE_TICKS = 100;

    // Starting position — used for return-to-start
    private double startX, startY, startZ;
    private boolean hasStartPosition = false;
    private boolean finishScanAfterReturn = false;

    // A scanner target owns no inventory cargo, so a transient failure can safely move the
    // untouched target to the tail. Counts stay tied to physical containers, not attempts.
    private final Set<Long> discoveredContainerKeys = new HashSet<>();
    private static final int MAX_CONTAINER_ATTEMPTS = 3;
    private final TailRetryTracker<Long> containerRetries =
        new TailRetryTracker<>(MAX_CONTAINER_ATTEMPTS);
    private static final int MIN_CONTAINER_OPEN_TIMEOUT_TICKS = 400;
    private static final int CONTAINER_OPEN_RETRY_INTERVAL_TICKS = 20;

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

    // Tunnel network sync
    private final SyncWorker tunnelNetworkSyncWorker;

    // Exportable troubleshooting log (see /stash debug)
    private final DebugRecorder debugRecorder = new DebugRecorder();

    public StashManagerModule(StashManagerConfig config, ContainerIndex index) {
        this.config = config;
        this.index = index;
        this.scannerPreemptionGate = newScannerPreemptionGate();
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
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onTickStarting),
            of(ClientBotTick.Stopped.class, this::onTickStopped)
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
        info("StashManager module enabled");
    }

    @Override
    public void onDisable() {
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
        if (retriever.isActive()) {
            return "retriever is active";
        }
        String automationBlocker = getAutomationUnavailableReason();
        if (automationBlocker != null) return automationBlocker;

        LaneCapacityReport capacity = organizer.calculateLaneCapacity();
        return switch (capacity.status()) {
            case READY -> null;
            case INSUFFICIENT_LANES -> "lane capacity is short by " + capacity.laneShortfall()
                    + " dedicated lane(s); run /stash lanes for details";
            case INSUFFICIENT_LANE_STORAGE -> capacity.laneStorage().unassigned().size()
                    + " bulk class(es) do not fit any assignable lane; run /stash lanes for details";
            case NEEDS_FRESH_SCAN -> "lane capacity data contains "
                    + capacity.unclassifiedShulkers() + " unclassified shulker(s); run a fresh /stash scan";
            case NEEDS_FRESH_CONTAINER_SCAN -> "double-chest inventory identities require a fresh /stash scan";
            case REGION_NOT_DEFINED -> "region not defined (set pos1 and pos2 first)";
            case NO_SCANNED_CONTAINERS -> "no scanned containers in the configured region";
            case NO_LANES_DETECTED -> "no storage lanes detected in the configured region";
        };
    }

    public boolean startOrganizer() {
        String blocker = getOrganizerBlocker();
        if (blocker != null) {
            warn("Cannot start organizer: {}", blocker);
            fireWebhookEvent("organize_start_blocked", Map.of("reason", blocker));
            return false;
        }
        return organizer.start();
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

        // Close out the scan_history row with partial counts so it doesn't stay
        // stuck at completed_at=NULL forever (a rescan will still safely
        // overwrite any already-indexed containers via ON CONFLICT upsert).
        if (database != null && database.isInitialized() && currentScanId >= 0) {
            try {
                database.recordScanComplete(currentScanId, containersFound, containersIndexed, containersFailed);
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

        if (returnAfterAbort && config.returnToStart && hasStartPosition) {
            info("Returning to starting position after scan abort: {}, {}, {}",
                String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
            if (wasYielded) {
                // Do not steal shared control back from the task that interrupted us. The
                // manual stop's return trip starts after the existing hold/quiet gate.
                scanResumeMode = ScanResumeMode.RETURN_TO_START;
                resumeAbortedReturn = true;
                state = ScanState.YIELDED;
            } else {
                ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
                state = ScanState.RETURNING;
            }
            fireWebhookEvent("return_to_start_started", Map.of(
                "reason", "scan_aborted",
                "start_position", String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
        } else {
            state = ScanState.IDLE;
            scannerPreemptionGate.reset();
            clearOwnedAutomation();
        }
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
        ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
        state = ScanState.RETURNING;
        finishScanAfterReturn = false;
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
        return getAutomationUnavailableReason();
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
    private void onTickStarting(ClientBotTick.Starting event) {
        // Re-init on reconnect
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Bot reconnected during scan — resetting state");
            abortScan("bot_reconnected");
        }
    }

    private void onTickStopped(ClientBotTick.Stopped event) {
        // Bot ticks stop whenever a player takes control, which also pauses Baritone.
        if (organizer != null && organizer.isActive()) {
            warn("Bot ticks stopped while organizer was active — stopping organizer");
            organizer.stop();
        }
        if (retriever.isActive()) {
            warn("Bot ticks stopped while retriever was active — stopping retriever");
            retriever.stop();
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Bot ticks stopped while scan was active — aborting scan");
            abortScan("bot_ticks_stopped");
        }
    }

    private void onTick(ClientBotTick event) {
        // Tick TravelManager independently (it manages its own state)
        com.zenith.plugin.stashmanager.travel.TravelManager.get().tick();
        tunnelNetworkSyncWorker.tick();

        // Delegate tick to organizer when active
        if (organizer != null && organizer.isActive()) {
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
            restoreBaritoneBreaking();
            state = ScanState.DONE;
            info("No containers found in region");
            fireWebhookEvent("scan_empty");
            notifications.sendScanFinished(0, 0, 0);
            return;
        }

        advanceToNextContainer();
    }

    private void tickWalking() {
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
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            state = ScanState.ZONE_SCANNING;
        }
    }

    private void tickReturning() {
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            double dist = Math.sqrt(
                Math.pow(CACHE.getPlayerCache().getX() - startX, 2)
                + Math.pow(CACHE.getPlayerCache().getY() - startY, 2)
                + Math.pow(CACHE.getPlayerCache().getZ() - startZ, 2)
            );

            if (dist <= 3.0) {
                info("Returned to starting position: {}, {}, {}",
                    String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
                inGameAlert("<green>Returned to starting position.</green>");
                fireWebhookEvent("returned_to_start", Map.of("start_position",
                    String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
                notifications.sendReturnToStartCompleted(startX, startY, startZ);
            } else {
                warn("Could not reach starting position (dist={}). Finishing scan.",
                    String.format("%.1f", dist));
                inGameAlert("<yellow>Could not reach starting position</yellow> <gray>(dist="
                    + String.format("%.1f", dist) + "). Finishing scan.</gray>");
                recordAndFireScanFailure("return_to_start_failed", Map.of(
                    "distance", String.format("%.1f", dist),
                    "start_position", String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
                notifications.sendReturnToStartFailed(startX, startY, startZ, dist);
            }

            if (finishScanAfterReturn) {
                finishScan();
            } else {
                state = ScanState.DONE;
            }
        }
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

        var transition = scannerPreemptionGate.tick(isSharedAutomationBusy());
        if (transition != CooperativePreemptionGate.Transition.RESUMED) return;

        int pausedTicks = scannerPreemptionGate.elapsedTicks();
        ScanResumeMode resumeMode = scanResumeMode;
        boolean abortedReturn = resumeAbortedReturn;
        resumeAbortedReturn = false;
        lateOpenQuarantineTicks = 0;
        clearOwnedAutomation();

        info("Shared automation is quiet; resuming scan checkpoint {} after {} seconds",
            resumeMode, pausedTicks / 20);
        debugRecorder.record("scan_resumed",
            "resume_mode=" + resumeMode
                + ", paused_seconds=" + pausedTicks / 20
                + ", target=" + currentContainerPos()
                + ", preemption_count=" + scanPreemptionCount
                + ", aborted_return=" + abortedReturn);

        if (!abortedReturn) reacquireBaritoneBreakingAfterYield();

        switch (resumeMode) {
            case RETRY_CURRENT -> resumeCurrentContainer();
            case ADVANCE_CURRENT -> advanceToNextContainer();
            case RESCAN_ZONE -> state = ScanState.ZONE_SCANNING;
            case RETURN_TO_START -> {
                ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
                state = ScanState.RETURNING;
            }
        }
    }

    private void resumeCurrentContainer() {
        ContainerLocation target = currentContainer();
        if (target == null) {
            advanceToNextContainer();
            return;
        }

        tickCounter = 0;
        openTimeoutCounter = 0;
        openInteractionAttempts = 0;
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

            // Return to start if enabled
            if (config.returnToStart && hasStartPosition) {
                info("Returning to starting position: {}, {}, {}",
                    String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
                inGameAlert("<aqua>Scan complete!</aqua> <gray>Found=" + containersFound
                    + ", Indexed=" + containersIndexed + ", Failed=" + containersFailed
                    + ". Returning to start position...</gray>");
                ownCustomGoal(BARITONE.pathTo((int) startX, (int) startY, (int) startZ));
                state = ScanState.RETURNING;
                finishScanAfterReturn = true;
                fireWebhookEvent("return_to_start_started", Map.of("start_position",
                    String.format("%.1f, %.1f, %.1f", startX, startY, startZ)));
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

        info("Walking toward unscanned chunk via waypoint at {}, {}", targetX, targetZ);
        ownCustomGoal(BARITONE.pathTo(targetX, targetZ));
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
        // Right-click the container block at exact coordinates
        openInteractionAttempts++;
        ownInteraction(BARITONE.rightClickBlock(loc.x(), loc.y(), loc.z()));
    }

    private boolean prepareStandingContainerInteraction() {
        stopOwnedBaritoneProcess();
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
        containerDataReceived = false;
        containersFound = 0;
        containersIndexed = 0;
        containersFailed = 0;
        currentScanId = -1;
        hasStartPosition = false;
        finishScanAfterReturn = false;
        walkRetryCount = 0;
        walkingTickCount = 0;
        scannerPreemptionGate = newScannerPreemptionGate();
        clearOwnedAutomation();
        scanResumeMode = ScanResumeMode.RETRY_CURRENT;
        resumeAbortedReturn = false;
        lateOpenQuarantineTicks = 0;
        scanPreemptionCount = 0;
    }

    private CooperativePreemptionGate newScannerPreemptionGate() {
        return new CooperativePreemptionGate(
            Math.max(1, config.scanPreemptionCooldownSeconds) * 20,
            SCAN_PREEMPTION_QUIET_TICKS);
    }

    // Reasons that are expected/benign noise in a hopper-fed or actively-changing stash (the
    // planned target simply isn't there anymore by the time the bot looks) — still worth
    // recording for /stash debug, but not worth a Discord ping every single occurrence.
    private static final Set<String> WEBHOOK_SUPPRESSED_REASONS = Set.of(
        "item_not_found_at_source", "nothing_to_deposit"
    );

    private void handleAutomationEvent(String event, Map<String, Object> payload) {
        boolean suppressWebhook = payload != null
            && WEBHOOK_SUPPRESSED_REASONS.contains(String.valueOf(payload.get("reason")));
        if (!suppressWebhook) {
            fireWebhookEvent(event, payload);
        }
        // Organizer/retriever failures (pathfinding, shulker open/close/break, etc.) only ever
        // reached Discord via fireWebhookEvent above — the debug recorder never saw them.
        if (isFailureEvent(event)) {
            debugRecorder.record(event, formatPayloadDetail(payload));
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
            case "organize_completed" -> notifications.sendOrganizerFinished(
                intValue(payload, "completed_tasks"),
                intValue(payload, "total_tasks"),
                intValue(payload, "overflow_types")
            );
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
