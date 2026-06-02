package com.zenith.plugin.stashmanager.travel.tunnel;

import com.zenith.plugin.stashmanager.travel.bridge.TravelBaritoneBridge;
import com.zenith.plugin.stashmanager.travel.delivery.DimensionHelper;
import com.zenith.plugin.stashmanager.travel.tunnel.builder.TunnelBuildPhase;
import com.zenith.plugin.stashmanager.travel.tunnel.builder.TunnelBuilder;
import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import com.zenith.plugin.stashmanager.travel.tunnel.scanner.TunnelScanner;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.zenith.Globals.CACHE;

// Coordinates tunnel route lookup, scanning, building, persistence, and traversal.
public final class TunnelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelManager");

    /** Maximum distance (blocks) from the player's position to an existing tunnel entry. */
    private static final int MAX_ENTRY_DETOUR = 500;

    /** Minimum confidence required to reuse a stored tunnel. */
    private static final double MIN_CONFIDENCE = 0.5;

    private enum State { IDLE, SCANNING, ROUTING, BUILDING, TRAVERSING, DONE, FAILED }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final TravelBaritoneBridge bridge;
    private final TunnelRepository     repository;
    private final TunnelScanner        scanner;
    private final TunnelBuilder        builder;

    // ── Mission ───────────────────────────────────────────────────────────────

    private State       state       = State.IDLE;
    private int         destX, destZ;
    private int         surfaceY;
    private Tunnel      activeTunnel = null;
    private String      failReason   = null;

    /** Ticks spent in BUILDING or TRAVERSING. For progress display. */
    private int stateTicks = 0;

    /** Y to resurface to at the destination. Defaults to ~112 (typical nether surface). */
    private static final int DEFAULT_SURFACE_Y = 112;

    // ── Construction ─────────────────────────────────────────────────────────

    public TunnelManager(TravelBaritoneBridge bridge, TunnelRepository repository) {
        this.bridge     = bridge;
        this.repository = repository;
        this.scanner    = new TunnelScanner();
        this.builder    = new TunnelBuilder(bridge);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    // Returns immediately; poll tick() then check isReady() / isBuildFailed().
    public void requestRoute(int destX, int destZ) {
        requestRoute(destX, destZ, DEFAULT_SURFACE_Y);
    }

    // Request with explicit surface resurfacing Y.
    public void requestRoute(int destX, int destZ, int surfaceY) {
        if (state != State.IDLE) {
            LOGGER.warn("requestRoute() called while not IDLE (state={}), ignoring", state);
            return;
        }
        this.destX    = destX;
        this.destZ    = destZ;
        this.surfaceY = surfaceY;
        this.activeTunnel = null;
        this.failReason   = null;
        this.stateTicks   = 0;

        LOGGER.info("TunnelManager: route requested to [{},{}]", destX, destZ);
        transition(State.SCANNING);
    }

    // Call every game tick while a route is pending or a build is active.
    public void tick() {
        stateTicks++;
        switch (state) {
            case SCANNING    -> tickScanning();
            case ROUTING     -> tickRouting();
            case BUILDING    -> tickBuilding();
            case TRAVERSING  -> tickTraversing();
            default          -> { /* IDLE / DONE / FAILED — nothing to do */ }
        }
    }

    public boolean isIdle()        { return state == State.IDLE;       }
    public boolean isReady()       { return state == State.DONE;       }
    public boolean isBuilding()    { return state == State.BUILDING;   }
    public boolean isTraversing()  { return state == State.TRAVERSING; }
    public boolean isBuildFailed() { return state == State.FAILED;     }

    /** The tunnel selected or built for the current request. Null until ready. */
    public Tunnel getActiveTunnel() { return activeTunnel; }
    /** Fail reason. Null unless isBuildFailed(). */
    public String getFailReason()   { return failReason;   }

    /** 0.0–1.0 progress fraction during a build. */
    public double getBuildProgress() {
        if (state == State.BUILDING) return builder.progressFraction();
        if (state == State.DONE)     return 1.0;
        return 0.0;
    }

    /** Current builder phase (IDLE when not building). */
    public TunnelBuildPhase getBuildPhase() {
        return builder.getPhase();
    }

    /**
     * Notify TunnelManager that the active tunnel was successfully traversed.
     * Updates statistics in the repository.
     */
    public void notifyTraversalComplete() {
        if (activeTunnel == null) return;
        activeTunnel.recordUse();
        if (activeTunnel.id > 0) {
            repository.recordUse(activeTunnel.id);
        }
        LOGGER.info("Traversal complete for tunnel id={}", activeTunnel.id);
        transition(State.IDLE);
    }

    /**
     * Reset state machine to IDLE, cancelling any in-progress build.
     */
    public void cancel() {
        if (state == State.BUILDING) {
            builder.cancel();
        }
        transition(State.IDLE);
    }

    // ── Tick handlers ─────────────────────────────────────────────────────────

    /**
     * SCANNING: Run TunnelScanner on loaded chunks, persist any new tunnels,
     * then immediately proceed to ROUTING.
     *
     * Scanning is done once (synchronous) then we move on.
     */
    private void tickScanning() {
        int[] playerPos = bridge.getPlayerPos();
        if (playerPos == null) {
            fail("cannot get player position for scan");
            return;
        }

        try {
            List<Tunnel> candidates = scanner.scan(playerPos[0], playerPos[2]);
            int saved = 0;
            for (Tunnel candidate : candidates) {
                var idOpt = repository.save(candidate);
                if (idOpt.isPresent()) saved++;
            }
            if (saved > 0) {
                LOGGER.info("TunnelScanner: saved {} new tunnels from chunk cache", saved);
            }
        } catch (Exception e) {
            LOGGER.debug("Chunk scan failed gracefully: {}", e.getMessage());
        }

        transition(State.ROUTING);
    }

    /**
     * ROUTING: Search the database for an existing tunnel that can be used.
     * If found, we're DONE (tunnel is ready). If not found, start BUILDING.
     */
    private void tickRouting() {
        int[] playerPos = bridge.getPlayerPos();
        if (playerPos == null) {
            fail("cannot get player position for routing");
            return;
        }

        Optional<Tunnel> best = findBestExistingTunnel(playerPos[0], playerPos[2]);

        if (best.isPresent()) {
            activeTunnel = best.get();
            LOGGER.info("Found existing tunnel: {}", activeTunnel);
            transition(State.DONE);
        } else {
            LOGGER.info("No existing tunnel found — starting build to [{},{}]", destX, destZ);
            startBuild(playerPos[0], playerPos[2]);
        }
    }

    private void tickBuilding() {
        builder.tick();

        if (builder.isComplete()) {
            activeTunnel = builder.getBuiltTunnel();
            repository.save(activeTunnel);
            LOGGER.info("Tunnel build complete, saved as id={}", activeTunnel.id);
            transition(State.DONE);
        } else if (builder.isFailed()) {
            fail("TunnelBuilder failed: " + builder.getFailReason());
        }
    }

    private void tickTraversing() {
        // Traversal is driven externally by TravelManager (Baritone is active).
        // TunnelManager only tracks progress — TravelManager calls
        // notifyTraversalComplete() when Baritone arrives at the destination.
    }

    // ── Route selection ───────────────────────────────────────────────────────

    // Ranks candidates by confidence × (1 / detour); checks forward and reversed direction.
    private Optional<Tunnel> findBestExistingTunnel(int ox, int oz) {
        String dim = DimensionHelper.currentDimName();
        if (dim.isEmpty()) dim = "minecraft:the_nether"; // safe default
        List<Tunnel> nearStart = repository.findNearStart(ox, oz, MAX_ENTRY_DETOUR, MIN_CONFIDENCE, dim);
        List<Tunnel> nearEnd   = repository.findNearEnd(ox, oz, MAX_ENTRY_DETOUR, MIN_CONFIDENCE, dim);

        // From nearStart candidates: select those whose end is also near destX/destZ
        // From nearEnd candidates: select those whose start is near destX/destZ (reversed traversal)
        return nearStart.stream()
                .filter(t -> closeEnoughToDestination(t.endX, t.endZ))
                .max(Comparator.comparingDouble(t ->
                        t.confidence / (1.0 + t.entryDistanceFrom(ox, oz))))
                .or(() -> nearEnd.stream()
                        .filter(t -> closeEnoughToDestination(t.startX, t.startZ))
                        .peek(t -> {
                            // Reverse the tunnel so start = player-side
                            int tmp;
                            tmp = t.startX; t.startX = t.endX; t.endX = tmp;
                            tmp = t.startZ; t.startZ = t.endZ; t.endZ = tmp;
                        })
                        .max(Comparator.comparingDouble(t ->
                                t.confidence / (1.0 + t.entryDistanceFrom(ox, oz)))));
    }

    private boolean closeEnoughToDestination(int x, int z) {
        int dx = x - destX;
        int dz = z - destZ;
        double dist = Math.sqrt(dx * (double) dx + dz * (double) dz);
        return dist <= MAX_ENTRY_DETOUR;
    }

    // ── Build launch ──────────────────────────────────────────────────────────

    private void startBuild(int originX, int originZ) {
        try {
            builder.start(originX, originZ, destX, destZ, surfaceY);
            transition(State.BUILDING);
        } catch (Exception e) {
            fail("Could not start tunnel builder: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void transition(State next) {
        LOGGER.info("TunnelManager: {} → {}", state, next);
        state = next;
        stateTicks = 0;
    }

    private void fail(String reason) {
        LOGGER.warn("TunnelManager failed: {}", reason);
        failReason = reason;
        state = State.FAILED;
    }

    /**
     * Quick verification of the active tunnel's endpoints using the scanner.
     * Updates the repository if confidence changed significantly.
     */
    public void verifyActiveTunnel() {
        if (activeTunnel == null) return;
        double newConfidence = scanner.verifyTunnel(activeTunnel);
        TunnelStatus newStatus;
        if (newConfidence >= 0.9)      newStatus = TunnelStatus.INTACT;
        else if (newConfidence >= 0.4) newStatus = TunnelStatus.PARTIAL;
        else                           newStatus = TunnelStatus.COMPROMISED;

        if (Math.abs(newConfidence - activeTunnel.confidence) > 0.1) {
            activeTunnel.confidence = newConfidence;
            activeTunnel.status     = newStatus;
            if (activeTunnel.id > 0) {
                repository.updateStatus(activeTunnel.id, newStatus, newConfidence);
            }
        }
    }
}
