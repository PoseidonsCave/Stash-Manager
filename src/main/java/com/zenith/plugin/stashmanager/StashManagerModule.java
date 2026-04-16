package com.zenith.plugin.stashmanager;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.plugin.stashmanager.scanner.ContainerReader;
import com.zenith.plugin.stashmanager.scanner.RegionScanner;
import com.zenith.plugin.stashmanager.scanner.RegionScanner.ContainerLocation;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

// Tick-driven container scanning state machine: IDLE → ZONE_SCANNING → WALKING → OPENING → READING → CLOSING → RETURNING → DONE
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
        DONE
    }

    private final StashManagerConfig config;
    private final ContainerIndex index;
    private final RegionScanner regionScanner;
    private ContainerReader containerReader;

    // Organizer integration
    private StashOrganizer organizer;

    private volatile ScanState state = ScanState.IDLE;
    private List<ContainerLocation> pendingContainers = new ArrayList<>();
    private int currentContainerIndex = 0;
    private int tickCounter = 0;
    private int openTimeoutCounter = 0;
    private boolean containerDataReceived = false;

    // Starting position — used for return-to-start
    private double startX, startY, startZ;
    private boolean hasStartPosition = false;

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

    public StashManagerModule(StashManagerConfig config, ContainerIndex index) {
        this.config = config;
        this.index = index;
        this.regionScanner = new RegionScanner();
        this.containerReader = new ContainerReader(index);
    }

    public void setDatabase(DatabaseManager database) {
        this.database = database;
    }

    public void setOrganizer(StashOrganizer organizer) {
        this.organizer = organizer;
    }

    public StashOrganizer getOrganizer() {
        return organizer;
    }

    @Override
    public boolean enabledSetting() {
        return config.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onTickStarting)
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
                    }
                    // Forward container data to organizer
                    if (organizer != null && organizer.isActive()) {
                        organizer.onContainerData(session, packet);
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
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            info("StashManager module disabled — aborting scan");
            abortScan();
        }
        info("StashManager module disabled");
    }

    // ── Public API ──────────────────────────────────────────────────────

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

    public int getPendingCount() {
        return Math.max(0, pendingContainers.size() - currentContainerIndex);
    }

    // Start a scan. Returns true if started.
    public boolean startScan() {
        if (config.pos1 == null || config.pos2 == null) {
            warn("Cannot start scan: region not defined (set pos1 and pos2 first)");
            return false;
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Cannot start scan: already scanning (state={})", state);
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
            }
        }

        state = ScanState.ZONE_SCANNING;
        info("Starting container scan in region ({}) to ({})",
            formatPos(config.pos1), formatPos(config.pos2));
        return true;
    }

    // Abort scan in progress.
    public void abortScan() {
        if (state == ScanState.IDLE) return;

        // Close any open container
        closeCurrentContainer();

        // Restore Baritone config
        restoreBaritoneBreaking();

        state = ScanState.IDLE;
        info("Scan aborted. Found={}, Indexed={}, Failed={}",
            containersFound, containersIndexed, containersFailed);
    }

    // Return to recorded start position. Returns true if nav started.
    public boolean returnToStart() {
        if (!hasStartPosition) {
            warn("No starting position recorded");
            return false;
        }
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Cannot return while scan is active (state={})", state);
            return false;
        }
        info("Returning to starting position: {}, {}, {}",
            String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
        BARITONE.pathTo((int) startX, (int) startY, (int) startZ);
        state = ScanState.RETURNING;
        return true;
    }

    // True if a start position was recorded.
    public boolean hasStartPosition() {
        return hasStartPosition;
    }

    public double getStartX() { return startX; }
    public double getStartY() { return startY; }
    public double getStartZ() { return startZ; }

    // Region size in blocks.
    public int[] getRegionDimensions() {
        if (config.pos1 == null || config.pos2 == null) return null;
        return new int[]{
            Math.abs(config.pos1[0] - config.pos2[0]) + 1,
            Math.abs(config.pos1[1] - config.pos2[1]) + 1,
            Math.abs(config.pos1[2] - config.pos2[2]) + 1
        };
    }

    // ── Tick Handlers ───────────────────────────────────────────────────

    private void onTickStarting(ClientBotTick.Starting event) {
        // Re-init on reconnect
        if (state != ScanState.IDLE && state != ScanState.DONE) {
            warn("Bot reconnected during scan — resetting state");
            state = ScanState.IDLE;
        }
    }

    private void onTick(ClientBotTick event) {
        // Delegate tick to organizer when active
        if (organizer != null && organizer.isActive()) {
            organizer.tick();
            return;
        }

        if (state == ScanState.IDLE || state == ScanState.DONE) return;

        switch (state) {
            case ZONE_SCANNING -> tickZoneScanning();
            case WALKING -> tickWalking();
            case OPENING -> tickOpening();
            case READING -> tickReading();
            case CLOSING -> tickClosing();
            case WALKING_TO_ZONE -> tickWalkingToZone();
            case RETURNING -> tickReturning();
            default -> {}
        }
    }

    // ── State Implementations ───────────────────────────────────────────

    private void tickZoneScanning() {
        List<ContainerLocation> found = regionScanner.scanRegion(
            config.pos1, config.pos2, config.maxContainers);

        pendingContainers.addAll(found);
        containersFound = pendingContainers.size();

        info("Zone scan complete: {} containers discovered", found.size());

        // Check for chunks beyond render distance
        var unscanned = regionScanner.getUnscannedChunks(config.pos1, config.pos2);
        if (!unscanned.isEmpty() && pendingContainers.size() < config.maxContainers) {
            info("{} chunks still unloaded — will walk to load them", unscanned.size());
        }

        if (pendingContainers.isEmpty()) {
            state = ScanState.DONE;
            info("No containers found in region");
            return;
        }

        currentContainerIndex = -1;
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
        if (dist <= 5.0) {
            BARITONE.stop();
            walkRetryCount = 0;
            state = ScanState.OPENING;
            tickCounter = 0;
            openTimeoutCounter = 0;
            containerDataReceived = false;
            interactWithContainer(target);
            return;
        }

        // Timeout failsafe
        if (walkingTickCount >= WALK_TIMEOUT_TICKS) {
            warn("Walking timeout for container at {} (dist={})",
                currentContainerPos(), String.format("%.1f", dist));
            BARITONE.stop();
            containersFailed++;
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
                containersFailed++;
                advanceToNextContainer();
            }
        }
    }

    private void tickOpening() {
        openTimeoutCounter++;

        if (containerDataReceived) {
            // Buffer ticks for container content
            state = ScanState.READING;
            tickCounter = 0;
            return;
        }

        if (openTimeoutCounter >= config.openTimeoutTicks) {
            warn("Timeout waiting for container open at {}", currentContainerPos());
            containersFailed++;
            closeCurrentContainer();
            advanceToNextContainer();
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
        } else {
            containersFailed++;
            warn("Failed to read container at {}", currentContainerPos());
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
            } else {
                warn("Could not reach starting position (dist={}). Finishing scan.",
                    String.format("%.1f", dist));
            }

            finishScan();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void advanceToNextContainer() {
        currentContainerIndex++;

        if (currentContainerIndex >= pendingContainers.size()) {
            // Walk toward unscanned chunks
            var unscanned = regionScanner.getUnscannedChunks(config.pos1, config.pos2);
            if (!unscanned.isEmpty() && containersFound < config.maxContainers) {
                // Walk toward unscanned area
                int[] target = unscanned.get(0);
                int targetX = target[0] * 16 + 8;
                int targetZ = target[1] * 16 + 8;
                info("Walking toward unscanned chunk at {}, {}", targetX, targetZ);
                BARITONE.pathTo(targetX, targetZ);
                state = ScanState.WALKING_TO_ZONE;
                return;
            }

            info("All containers processed. Found={}, Indexed={}, Failed={}",
                containersFound, containersIndexed, containersFailed);

            // Return to start if enabled
            if (config.returnToStart && hasStartPosition) {
                info("Returning to starting position: {}, {}, {}",
                    String.format("%.1f", startX), String.format("%.1f", startY), String.format("%.1f", startZ));
                BARITONE.pathTo((int) startX, (int) startY, (int) startZ);
                state = ScanState.RETURNING;
                return;
            }

            finishScan();
            return;
        }

        ContainerLocation next = currentContainer();
        if (next == null) {
            state = ScanState.DONE;
            return;
        }

        // Skip double-chest partners
        if (next.type() == BlockEntityType.CHEST || next.type() == BlockEntityType.TRAPPED_CHEST) {
            if (isDoubleChestPartner(next)) {
                debug("Skipping double chest partner at {}, {}, {}",
                    next.x(), next.y(), next.z());
                advanceToNextContainer();
                return;
            }
        }

        // Stop any lingering Baritone process before starting new action
        BARITONE.stop();

        double dist = distanceToContainer(next);
        walkRetryCount = 0;
        walkingTickCount = 0;
        if (dist <= 5.0) {
            // Already in range
            state = ScanState.OPENING;
            tickCounter = 0;
            openTimeoutCounter = 0;
            containerDataReceived = false;
            interactWithContainer(next);
        } else {
            state = ScanState.WALKING;
            pathToContainer(next);
            info("Walking to container {}/{} at {}, {}, {} (dist={})",
                currentContainerIndex + 1, pendingContainers.size(),
                next.x(), next.y(), next.z(), String.format("%.1f", dist));
        }
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

    private void interactWithContainer(ContainerLocation loc) {
        // Right-click the container block at exact coordinates
        BARITONE.rightClickBlock(loc.x(), loc.y(), loc.z());
    }

    private void pathToContainer(ContainerLocation loc) {
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(loc.x(), loc.y(), loc.z())));
    }

    private void closeCurrentContainer() {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new CloseContainer())
                .priority(5000)
                .build());
        } catch (Exception e) {
            debug("Error closing container: {}", e.getMessage());
        }
    }

    // Is this a double chest?
    private boolean isDoubleChest(ContainerLocation loc) {
        if (loc.type() != BlockEntityType.CHEST && loc.type() != BlockEntityType.TRAPPED_CHEST) {
            return false;
        }
        // Check adjacent blocks
        return hasAdjacentChest(loc.x() + 1, loc.y(), loc.z(), loc.type())
            || hasAdjacentChest(loc.x() - 1, loc.y(), loc.z(), loc.type())
            || hasAdjacentChest(loc.x(), loc.y(), loc.z() + 1, loc.type())
            || hasAdjacentChest(loc.x(), loc.y(), loc.z() - 1, loc.type());
    }

    // Is this the partner half of a double chest? (skip to avoid double-indexing)
    private boolean isDoubleChestPartner(ContainerLocation loc) {
        if (!isDoubleChest(loc)) return false;

        // Find the adjacent matching chest
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            int nx = loc.x() + offset[0];
            int nz = loc.z() + offset[1];
            if (hasAdjacentChest(nx, loc.y(), nz, loc.type())) {
                // Keep lower x/z as primary
                if (loc.x() > nx || (loc.x() == nx && loc.z() > nz)) {
                    return true; // partner — skip
                }
                return false;
            }
        }
        return false;
    }

    private boolean hasAdjacentChest(int x, int y, int z, BlockEntityType type) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!CACHE.getChunkCache().isChunkLoaded(chunkX, chunkZ)) return false;

        var chunk = CACHE.getChunkCache().get(chunkX, chunkZ);
        if (chunk == null) return false;

        for (var be : chunk.getBlockEntities()) {
            int wx = (chunkX * 16) + be.getX();
            int wz = (chunkZ * 16) + be.getZ();
            if (wx == x && be.getY() == y && wz == z && be.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private void finishScan() {
        // Restore Baritone config
        restoreBaritoneBreaking();

        // Record scan completion in DB
        if (database != null && database.isInitialized() && currentScanId >= 0) {
            try {
                database.recordScanComplete(currentScanId, containersFound, containersIndexed, containersFailed);
            } catch (Exception e) {
                warn("Failed to record scan completion in database: {}", e.getMessage());
            }
        }

        // Fire webhook notification
        fireWebhook();

        state = ScanState.DONE;
        info("Scan complete. Found={}, Indexed={}, Failed={}",
            containersFound, containersIndexed, containersFailed);
    }

    private void fireWebhook() {
        if (config.webhookUrl == null || config.webhookUrl.isBlank()) return;

        try {
            String json = "{" +
                "\"event\": \"scan_complete\"," +
                "\"containers_found\": " + containersFound + "," +
                "\"containers_indexed\": " + containersIndexed + "," +
                "\"containers_failed\": " + containersFailed + "," +
                "\"timestamp\": " + System.currentTimeMillis() +
                "}";

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> debug("Webhook response: {} {}", resp.statusCode(), resp.body()))
                .exceptionally(e -> {
                    warn("Webhook failed: {}", e.getMessage());
                    return null;
                });
        } catch (Exception e) {
            warn("Failed to fire webhook: {}", e.getMessage());
        }
    }

    private void resetScanState() {
        regionScanner.reset();
        pendingContainers.clear();
        currentContainerIndex = 0;
        tickCounter = 0;
        openTimeoutCounter = 0;
        containerDataReceived = false;
        containersFound = 0;
        containersIndexed = 0;
        containersFailed = 0;
        currentScanId = -1;
        hasStartPosition = false;
        walkRetryCount = 0;
        walkingTickCount = 0;
    }

    // Save Baritone allowBreak and disable it.
    private void saveAndDisableBaritoneBreaking() {
        var pathfinderConfig = CONFIG.client.extra.pathfinder;
        savedAllowBreak = pathfinderConfig.allowBreak;
        pathfinderConfig.allowBreak = false;
        baritoneConfigSaved = true;
        info("Baritone block breaking disabled for scan (was={})", savedAllowBreak);
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
