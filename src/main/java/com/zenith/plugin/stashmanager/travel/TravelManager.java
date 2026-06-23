package com.zenith.plugin.stashmanager.travel;

import com.zenith.plugin.stashmanager.StashManagerPlugin;
import com.zenith.plugin.stashmanager.StashManagerNotifications;
import com.zenith.plugin.stashmanager.travel.bridge.TravelBaritoneBridge;
import com.zenith.plugin.stashmanager.travel.delivery.ChestDeposit;
import com.zenith.plugin.stashmanager.travel.delivery.DimensionHelper;
import com.zenith.plugin.stashmanager.travel.delivery.GatherOperation;
import com.zenith.plugin.stashmanager.travel.delivery.HomeTracker;
import com.zenith.plugin.stashmanager.travel.delivery.PortalDestroyer;
import com.zenith.plugin.stashmanager.travel.delivery.PortalSequence;
import com.zenith.plugin.stashmanager.travel.tunnel.TunnelManager;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Controls all travel missions. Drives a tick-based state machine through
// tunnel routing, traversal, and the full delivery phase sequence.
public final class TravelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/Travel");
    private static final StashManagerNotifications NOTIFICATIONS = new StashManagerNotifications();

    private static final TravelManager INSTANCE = new TravelManager();
    public static TravelManager get() { return INSTANCE; }

    private final TravelState state = new TravelState();
    private final TravelBaritoneBridge bridge = TravelBaritoneBridge.get();

    // Lazily initialised so DatabaseManager has time to start.
    private TunnelManager tunnelManager = null;

    // ── Auto-resume ───────────────────────────────────────────────────────────

    private int autoResumeTicks    = 0;
    private int autoResumeAttempts = 0;
    private static final int AUTO_RESUME_DELAY_TICKS = 100;
    static final int MAX_AUTO_RESUME_ATTEMPTS = 10;

    private TravelManager() {}

    // ── Public API ────────────────────────────────────────────────────────────

    // Returns false if a mission is already active.
    public synchronized boolean start(TravelMission mission) {
        if (state.phase != TravelPhase.IDLE) {
            LOGGER.warn("start() rejected: phase={} (not IDLE)", state.phase);
            return false;
        }
        state.reset();
        state.mission = mission;
        state.missionStartMs = System.currentTimeMillis();
        autoResumeAttempts = 0;
        autoResumeTicks    = 0;
        transition(TravelPhase.PLANNING, "user start: " + mission);
        LOGGER.info("Travel mission started: {}", mission);
        if (mission.isDelivery) {
            NOTIFICATIONS.sendDeliveryStarted(
                    mission.destination[0], mission.destination[2],
                    mission.itemIds, mission.quantities);
        }
        return true;
    }

    /** Stop the current mission immediately. */
    public synchronized void stop() {
        if (state.phase == TravelPhase.IDLE) return;
        autoResumeTicks = 0;
        releaseOwner(state.owner);
        state.abortReason = "user stop";
        transition(TravelPhase.ABORTED, "user stop");
        LOGGER.info("Travel mission stopped by user");
    }

    /** Pause the current mission (can be resumed later). */
    public synchronized void pause() {
        if (state.phase == TravelPhase.IDLE || state.phase == TravelPhase.PAUSED) return;
        state.pausedFromPhase = state.phase;
        releaseOwner(state.owner);
        state.owner = MovementOwner.NONE;
        TravelPhase from = state.phase;
        state.phase = TravelPhase.PAUSED;
        state.ticksInPhase = 0;
        state.lastTransitionReason = "user pause from " + from;
        LOGGER.info("Travel mission paused");
    }

    /** Resume a paused mission, or re-plan from the current position after an abort. */
    public synchronized void resume() {
        if (state.phase == TravelPhase.PAUSED) {
            TravelPhase target = state.pausedFromPhase != null
                    ? state.pausedFromPhase : TravelPhase.PLANNING;
            transition(target, "user resume");
            LOGGER.info("Travel mission resumed");
            return;
        }
        if (state.phase == TravelPhase.IDLE && state.mission != null) {
            TravelMission m = state.mission;
            state.reset();
            state.mission = m;
            transition(TravelPhase.PLANNING, "user resume");
            LOGGER.info("Travel mission restarted");
        }
    }

    public synchronized TravelPhase currentPhase() { return state.phase; }
    public synchronized TravelState getState()     { return state; }
    public boolean isActive() {
        return state.phase != TravelPhase.IDLE && state.phase != TravelPhase.ABORTED;
    }

    /** Returns the active GatherOperation if gathering is in progress, else null. */
    public GatherOperation getActiveGatherOp() {
        return (state.phase == TravelPhase.GATHERING && state.gatherOp != null && state.gatherOp.isActive())
                ? state.gatherOp : null;
    }

    /** Returns the active ChestDeposit if a deposit is in progress, else null. */
    public ChestDeposit getActiveChestDeposit() {
        return (state.phase == TravelPhase.DELIVERY && state.chestDeposit != null && state.chestDeposit.isActive())
                ? state.chestDeposit : null;
    }


    // ── Tick ──────────────────────────────────────────────────────────────────

    public synchronized void tick() {
        if (state.phase == TravelPhase.IDLE) {
            if (autoResumeTicks > 0) {
                autoResumeTicks--;
                if (autoResumeTicks == 0 && state.mission != null) {
                    LOGGER.info("Auto-resuming after abort (attempt {}/{})",
                            autoResumeAttempts, MAX_AUTO_RESUME_ATTEMPTS);
                    TravelMission m = state.mission;
                    state.reset();
                    state.mission = m;
                    transition(TravelPhase.PLANNING,
                            "auto-resume attempt " + autoResumeAttempts);
                }
            }
            return;
        }

        state.ticksInPhase++;
        state.missionTicks++;

        driveOwner();

        switch (state.phase) {
            case IDLE             -> { /* handled above */ }
            case PLANNING         -> tickPlanning();
            case TUNNEL_PLANNING  -> tickTunnelPlanning();
            case TUNNEL_TRAVERSE  -> tickTunnelTraverse();
            // ── Delivery phases ──────────────────────────────────
            case DELIVERY_INIT    -> tickDeliveryInit();
            case GATHERING        -> tickGathering();
            case DIM_DETECT       -> tickDimDetect();
            case HOME_SETUP       -> tickHomeSetup();
            case PORTAL_CREATE    -> tickPortalCreate();
            case PORTAL_ENTER     -> tickPortalEnter();
            case PORTAL_DESTROY   -> tickPortalDestroy();
            case MINING_TO_DEST   -> tickMiningToDest();
            case DELIVERY         -> tickDelivery();
            case RETURN_HOME      -> tickReturnHome();
            // ── Terminal ─────────────────────────────────────────
            case ARRIVED, ABORTED -> tickTerminal();
            case PAUSED           -> { /* no-op */ }
        }
    }

    // ── Movement owner management ─────────────────────────────────────────────

    private void driveOwner() {
        switch (state.owner) {
            case BARITONE -> bridge.tick();
            case TUNNEL   -> { TunnelManager tm = getTunnelManager(); if (tm != null) tm.tick(); }
            default       -> { /* NONE, DELIVERY — driven by phase handlers */ }
        }
    }

    private void releaseOwner(MovementOwner owner) {
        switch (owner) {
            case BARITONE -> bridge.cancelAll();
            case TUNNEL   -> { TunnelManager tm = getTunnelManager(); if (tm != null) tm.cancel(); }
            default       -> { }
        }
    }

    // ── Phase: PLANNING ───────────────────────────────────────────────────────

    private void tickPlanning() {
        if (state.mission == null) {
            abort("Invalid mission: null");
            return;
        }
        // Delivery missions start with initialization first
        if (state.mission.isDelivery) {
            transition(TravelPhase.DELIVERY_INIT, "delivery initialization");
            return;
        }
        // Plain travel: kick off tunnel route lookup
        startTunnelRoute();
    }

    private void startTunnelRoute() {
        TunnelManager tm = getTunnelManager();
        if (tm == null) {
            abort("TunnelManager unavailable (database not initialized?)");
            return;
        }
        int[] dest = state.mission.destination;
        tm.requestRoute(dest[0], dest[2]);
        state.owner = MovementOwner.TUNNEL;
        transition(TravelPhase.TUNNEL_PLANNING, "requesting tunnel route");
    }

    // ── Phase: TUNNEL_PLANNING ────────────────────────────────────────────────

    private void tickTunnelPlanning() {
        TunnelManager tm = getTunnelManager();
        if (tm == null) { abort("TunnelManager lost"); return; }

        if (tm.isReady()) {
            // A tunnel was found or built — walk its entry point, then traverse
            var tunnel = tm.getActiveTunnel();
            LOGGER.info("Tunnel ready: {}", tunnel);
            // Path to the tunnel entry column using Baritone
            bridge.walkToExact(new int[]{tunnel.startX, tunnel.floorY + 1, tunnel.startZ});
            state.owner = MovementOwner.BARITONE;
            // Re-use TUNNEL_TRAVERSE for the actual traversal; owner switches to TUNNEL
            // once we arrive at the entry
            transition(TravelPhase.TUNNEL_TRAVERSE, "route ready, walking to tunnel entry");
        } else if (tm.isBuildFailed()) {
            abort("Tunnel routing/build failed: " + tm.getFailReason());
        }
        // Otherwise still scanning / routing / building — keep waiting
    }

    // ── Phase: TUNNEL_TRAVERSE ────────────────────────────────────────────────

    private void tickTunnelTraverse() {
        TunnelManager tm = getTunnelManager();

        // Sub-state A: walking to tunnel entry via Baritone
        if (state.owner == MovementOwner.BARITONE) {
            if (bridge.isArrived()) {
                LOGGER.info("At tunnel entry — starting corridor traversal");
                var tunnel = tm.getActiveTunnel();
                bridge.walkToExact(new int[]{tunnel.endX, tunnel.floorY + 1, tunnel.endZ});
                // Switch to TUNNEL owner so sub-state B is entered next iteration
                state.owner = MovementOwner.TUNNEL;
            } else if (bridge.isStuck()) {
                abort("Stuck walking to tunnel entry");
            }
            return;
        }

        // Sub-state B: Baritone traversing the tunnel corridor to exit (owner == TUNNEL)
        if (bridge.isArrived()) {
            LOGGER.info("Traversal complete — exited tunnel");
            if (tm != null) tm.notifyTraversalComplete();
            state.owner = MovementOwner.NONE;
            int[] dest = state.mission.destination;
            bridge.walkNear(dest, 4);
            state.owner = MovementOwner.BARITONE;
            transition(TravelPhase.MINING_TO_DEST, "exited tunnel, walking to destination");
        } else if (bridge.isStuck()) {
            abort("Stuck traversing tunnel");
        }
    }

    // ── Phase: MINING_TO_DEST ─────────────────────────────────────────────────

    private void tickMiningToDest() {
        if (bridge.isArrived()) {
            LOGGER.info("Arrived at destination");
            state.owner = MovementOwner.NONE;
            if (state.mission != null && state.mission.isDelivery) {
                transition(TravelPhase.DELIVERY, "begin item delivery");
            } else {
                transition(TravelPhase.ARRIVED, "destination reached");
            }
        } else if (bridge.isStuck()) {
            abort("Stuck walking to destination from tunnel exit");
        }
    }

    // ── Delivery phase handlers (stubs) ───────────────────────────────────────

    private void tickDeliveryInit() {
        // Record home position once on first tick, then proceed to gathering
        if (state.ticksInPhase == 0) {
            state.homeTracker = new HomeTracker();
            state.homeTracker.recordHome(bridge.getPlayerPos(), bridge.getDimension());
        }
        transition(TravelPhase.GATHERING, "home recorded at ["
                + state.homeTracker.getHomePos()[0] + ","
                + state.homeTracker.getHomePos()[1] + ","
                + state.homeTracker.getHomePos()[2] + "]");
    }

    private void tickGathering() {
        var index = StashManagerPlugin.getIndex();
        if (index == null) {
            abort("ContainerIndex unavailable during GATHERING");
            return;
        }

        // Initialise gather operation on first tick
        if (state.gatherOp == null) {
            state.gatherOp = new GatherOperation();
            if (!state.gatherOp.start(state.mission, index)) {
                LOGGER.warn("Gather skipped: {}", state.gatherOp.getFailReason());
                transition(TravelPhase.DIM_DETECT, "gathering skipped");
                return;
            }
        }

        state.gatherOp.tick();

        if (state.gatherOp.isDone()) {
            transition(TravelPhase.DIM_DETECT, "gathering complete");
        } else if (state.gatherOp.isFailed()) {
            abort("Gathering failed: " + state.gatherOp.getFailReason());
        }
    }

    private void tickDimDetect() {
        String dim = bridge.getDimension();
        LOGGER.info("Dimension detection: {}", dim);

        if (DimensionHelper.isNether()) {
            // Already in nether — no portal needed, start tunnel travel directly
            LOGGER.info("Already in nether — skipping portal sequence");
            startTunnelRoute();
        } else {
            // Overworld (or unknown) — need to build and enter a portal
            transition(TravelPhase.HOME_SETUP, "dimension: " + dim);
        }
    }

    private void tickHomeSetup() {
        // Home position was already recorded in DELIVERY_INIT.
        // Bed/anchor placement is not implemented; skip straight to portal creation.
        LOGGER.info("Home setup complete (position recorded, no bed placement)");
        transition(TravelPhase.PORTAL_CREATE, "home setup complete");
    }

    private void tickPortalCreate() {
        // Initialise portal sequence on first tick
        if (state.portalSequence == null) {
            state.portalSequence = new PortalSequence();
            int[] pos = bridge.getPlayerPos();
            if (!state.portalSequence.start(pos)) {
                abort("Portal creation failed: " + state.portalSequence.getFailReason());
                return;
            }
        }

        state.portalSequence.tick();

        if (state.portalSequence.getPhase() == PortalSequence.Phase.ENTERING) {
            // Portal built and player is walking into it — hand off to PORTAL_ENTER
            transition(TravelPhase.PORTAL_ENTER, "portal frame complete, entering");
        } else if (state.portalSequence.isFailed()) {
            abort("Portal creation failed: " + state.portalSequence.getFailReason());
        }
    }

    private void tickPortalEnter() {
        // Continue ticking the portal sequence through ENTERING → WAITING_DIM → DONE
        if (state.portalSequence == null) {
            abort("Portal sequence missing during PORTAL_ENTER");
            return;
        }

        state.portalSequence.tick();

        if (state.portalSequence.isDone()) {
            if (state.mission.destroyPortalAfterUse) {
                transition(TravelPhase.PORTAL_DESTROY, "entered nether via portal");
            } else {
                startTunnelRoute();
            }
        } else if (state.portalSequence.isFailed()) {
            abort("Portal enter failed: " + state.portalSequence.getFailReason());
        }
    }

    private void tickPortalDestroy() {
        if (state.portalDestroyer == null) {
            state.portalDestroyer = new PortalDestroyer();
            state.portalDestroyer.start(bridge.getPlayerPos());
        }

        state.portalDestroyer.tick();

        if (state.portalDestroyer.isDone()) {
            startTunnelRoute();
        }
    }

    private void tickDelivery() {
        var index = StashManagerPlugin.getIndex();
        if (index == null) {
            abort("ContainerIndex unavailable during DELIVERY");
            return;
        }

        // Initialise chest deposit on first tick
        if (state.chestDeposit == null) {
            state.chestDeposit = new ChestDeposit();
            int[] dest = state.mission.destination;
            if (!state.chestDeposit.start(dest, state.mission.itemIds, index)) {
                LOGGER.warn("ChestDeposit failed to start: {}", state.chestDeposit.getFailReason());
                transition(TravelPhase.RETURN_HOME, "deposit skipped (no chests found)");
                return;
            }
        }

        state.chestDeposit.tick();

        if (state.chestDeposit.isDone()) {
            transition(TravelPhase.RETURN_HOME, "delivery complete");
        } else if (state.chestDeposit.isFailed()) {
            LOGGER.warn("Deposit failed: {}", state.chestDeposit.getFailReason());
            transition(TravelPhase.RETURN_HOME, "deposit failed — returning home anyway");
        }
    }

    private void tickReturnHome() {
        if (state.homeTracker == null) {
            transition(TravelPhase.ARRIVED, "no home tracker, mission done");
            return;
        }

        // Step 1: send /kill on the first tick
        if (state.ticksInPhase == 1) {
            LOGGER.info("Sending /kill to return home...");
            state.homeTracker.sendKill();
            return;
        }

        // Step 2: once dead, send respawn
        if (state.homeTracker.isDead()) {
            if (state.ticksInPhase % 10 == 0) {
                state.homeTracker.sendRespawn();
            }
            return;
        }

        // Step 3: alive again (respawned at bed/anchor) — mission complete
        if (state.homeTracker.isAlive() && state.ticksInPhase > 10) {
            transition(TravelPhase.ARRIVED, "respawned at home");
        }
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    private void tickTerminal() {
        // Fire delivery notifications exactly once on the first terminal tick.
        if (state.ticksInPhase == 1 && state.mission != null && state.mission.isDelivery) {
            long durationMs = System.currentTimeMillis() - state.missionStartMs;
            int dx = state.mission.destination[0];
            int dz = state.mission.destination[2];
            if (state.phase == TravelPhase.ARRIVED) {
                NOTIFICATIONS.sendDeliveryComplete(dx, dz, durationMs);
            } else if (state.phase == TravelPhase.ABORTED) {
                NOTIFICATIONS.sendDeliveryFailed(dx, dz, state.abortReason, durationMs);
            }
        }

        if (state.ticksInPhase > 20) {
            if (state.phase == TravelPhase.ABORTED
                    && state.mission != null
                    && state.mission.autoResume
                    && autoResumeAttempts < MAX_AUTO_RESUME_ATTEMPTS) {
                autoResumeAttempts++;
                autoResumeTicks = AUTO_RESUME_DELAY_TICKS;
                LOGGER.info("Scheduling auto-resume in {} ticks (attempt {})",
                        autoResumeTicks, autoResumeAttempts);
            } else {
                state.reset();
            }
        }
    }

    // ── Phase transition ──────────────────────────────────────────────────────

    private void transition(TravelPhase next, String reason) {
        TravelPhase prev = state.phase;
        state.phase = next;
        state.ticksInPhase = 0;
        state.lastTransitionReason = reason;
        LOGGER.info("[Travel] {} → {} | {}", prev, next, reason);
    }

    private void abort(String reason) {
        releaseOwner(state.owner);
        state.abortReason = reason;
        transition(TravelPhase.ABORTED, reason);
        LOGGER.warn("Travel aborted: {}", reason);
    }

    // ── TunnelManager lazy init ───────────────────────────────────────────────

    /** Public accessor for commands and external tools. */
    public TunnelManager tunnelManager() { return getTunnelManager(); }

    private TunnelManager getTunnelManager() {
        if (tunnelManager == null) {
            try {
                var db = StashManagerPlugin.getDatabase();
                if (db != null && db.isInitialized()) {
                    tunnelManager = new TunnelManager(bridge, new TunnelRepository(db));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to initialize TunnelManager", e);
            }
        }
        return tunnelManager;
    }
}
