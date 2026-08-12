package com.zenith.plugin.stashmanager.travel.tunnel.builder;

import com.zenith.plugin.stashmanager.travel.bridge.TravelBaritoneBridge;
import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelDiscovery;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.zenith.Globals.CACHE;

// Carves a bedrock-floor tunnel via three sequential Baritone goals.
// Phases: DESCENDING (dig to floorY) → TRAVERSING (mine horizontal) → ASCENDING (dig to surface).
// Stuck detection: fails after STUCK_TIMEOUT_TICKS ticks without arrival.
public final class TunnelBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelBuilder");

    // Ticks (20 TPS) to wait before declaring a phase stuck. ~5 minutes.
    private static final int STUCK_TIMEOUT_TICKS = 6_000;

    // Y coordinate all horizontal tunnels run along.
    public static final int TUNNEL_Y = 8;

    // State
    private TunnelBuildPhase phase = TunnelBuildPhase.IDLE;
    private int phaseTickCount = 0;
    private String failReason = null;

    private final TravelBaritoneBridge bridge;

    // Origin / destination
    private int originX, originZ;
    private int destX, destZ;
    private int surfaceDestY;

    // Result
    private Tunnel builtTunnel = null;

    // Construction
    public TunnelBuilder(TravelBaritoneBridge bridge) {
        this.bridge = bridge;
    }

    // Public API
    // Throws if already active.
    public void start(int originX, int originZ, int destX, int destZ, int surfaceDestY) {
        if (phase != TunnelBuildPhase.IDLE) {
            throw new IllegalStateException("TunnelBuilder already active: " + phase);
        }
        this.originX = originX;
        this.originZ = originZ;
        this.destX   = destX;
        this.destZ   = destZ;
        this.surfaceDestY = surfaceDestY;
        this.builtTunnel  = null;
        this.failReason   = null;
        this.phaseTickCount = 0;

        enterDescend();
    }

    // Cancel the build and release Baritone.
    public void cancel() {
        if (phase == TunnelBuildPhase.IDLE || phase == TunnelBuildPhase.COMPLETE
                || phase == TunnelBuildPhase.FAILED) return;
        bridge.cancelAll();
        fail("cancelled by caller");
    }

    // Drive the build. Call every tick while isActive().
    public void tick() {
        if (!isActive()) return;

        bridge.tick();
        phaseTickCount++;

        switch (phase) {
            case DESCENDING  -> tickDescending();
            case TRAVERSING  -> tickTraversing();
            case ASCENDING   -> tickAscending();
            default          -> { }
        }
    }

    public boolean isActive() {
        return phase == TunnelBuildPhase.DESCENDING
                || phase == TunnelBuildPhase.TRAVERSING
                || phase == TunnelBuildPhase.ASCENDING;
    }

    public boolean isComplete() { return phase == TunnelBuildPhase.COMPLETE; }
    public boolean isFailed()   { return phase == TunnelBuildPhase.FAILED;   }

    public TunnelBuildPhase getPhase() { return phase; }
    public String getFailReason() { return failReason; }

    // Returns the completed tunnel once isComplete(). Null otherwise.
    public Tunnel getBuiltTunnel() { return builtTunnel; }

    // Phase entry
    private void enterDescend() {
        LOGGER.info("TunnelBuilder DESCENDING: pathTo({},{},{})", originX, TUNNEL_Y, originZ);
        bridge.walkToExact(new int[]{originX, TUNNEL_Y, originZ});
        phase = TunnelBuildPhase.DESCENDING;
        phaseTickCount = 0;
    }

    private void enterTraverse() {
        LOGGER.info("TunnelBuilder TRAVERSING: pathTo({},{},{})", destX, TUNNEL_Y, destZ);
        bridge.walkToExact(new int[]{destX, TUNNEL_Y, destZ});
        phase = TunnelBuildPhase.TRAVERSING;
        phaseTickCount = 0;
    }

    private void enterAscend() {
        LOGGER.info("TunnelBuilder ASCENDING: pathTo({},{},{})", destX, surfaceDestY, destZ);
        bridge.walkToExact(new int[]{destX, surfaceDestY, destZ});
        phase = TunnelBuildPhase.ASCENDING;
        phaseTickCount = 0;
    }

    // Phase tick handlers
    private void tickDescending() {
        if (stuckTimeout()) {
            fail("stuck during DESCENDING after " + phaseTickCount + " ticks");
            return;
        }
        if (bridge.isArrived()) {
            LOGGER.info("Descent complete — at bedrock floor Y={}", TUNNEL_Y);
            enterTraverse();
        }
    }

    private void tickTraversing() {
        if (stuckTimeout()) {
            fail("stuck during TRAVERSING after " + phaseTickCount + " ticks");
            return;
        }
        if (bridge.isArrived()) {
            LOGGER.info("Horizontal traverse complete");
            enterAscend();
        }
    }

    private void tickAscending() {
        if (stuckTimeout()) {
            fail("stuck during ASCENDING after " + phaseTickCount + " ticks");
            return;
        }
        if (bridge.isArrived()) {
            LOGGER.info("Tunnel build complete — surfaced at destination");
            produceTunnel();
        }
    }

    // Helpers
    private boolean stuckTimeout() {
        if (phaseTickCount > STUCK_TIMEOUT_TICKS) {
            return true;
        }
        // Also use the bridge's positional-stuck check as a quicker heuristic
        return bridge.isStuck();
    }

    private void produceTunnel() {
        Tunnel t = new Tunnel();
        t.startX     = originX;
        t.startZ     = originZ;
        t.endX       = destX;
        t.endZ       = destZ;
        t.floorY     = TUNNEL_Y;
        t.discovery  = TunnelDiscovery.SELF_BUILT;
        t.status     = TunnelStatus.INTACT;
        t.confidence = 1.0;
        // No intermediate waypoints for a simple straight tunnel

        builtTunnel = t;
        phase = TunnelBuildPhase.COMPLETE;
        LOGGER.info("Produced tunnel: {}", t);
    }

    private void fail(String reason) {
        LOGGER.warn("TunnelBuilder failed: {}", reason);
        failReason = reason;
        phase = TunnelBuildPhase.FAILED;
        bridge.cancelAll();
    }

    // Progress reporting
    // Approximate 0.0–1.0 progress through the whole build.
    public double progressFraction() {
        return switch (phase) {
            case IDLE       -> 0.0;
            case DESCENDING -> 0.1;
            case TRAVERSING -> {
                // Estimate based on how far we've moved from origin
                try {
                    var player = CACHE.getPlayerCache();
                    int curX = (int) Math.floor(player.getX());
                    int curZ = (int) Math.floor(player.getZ());
                    double total = Math.sqrt(Math.pow(destX - originX, 2) + Math.pow(destZ - originZ, 2));
                    double done  = Math.sqrt(Math.pow(curX - originX, 2) + Math.pow(curZ - originZ, 2));
                    yield 0.1 + 0.8 * Math.min(1.0, total > 0 ? done / total : 0);
                } catch (Exception e) {
                    yield 0.5;
                }
            }
            case ASCENDING  -> 0.9;
            case COMPLETE   -> 1.0;
            case FAILED     -> 0.0;
        };
    }
}
