package com.zenith.plugin.stashmanager.travel;

import com.zenith.plugin.stashmanager.travel.delivery.ChestDeposit;
import com.zenith.plugin.stashmanager.travel.delivery.GatherOperation;
import com.zenith.plugin.stashmanager.travel.delivery.HomeTracker;
import com.zenith.plugin.stashmanager.travel.delivery.PortalDestroyer;
import com.zenith.plugin.stashmanager.travel.delivery.PortalSequence;

/** Mutable runtime state for the active travel mission. */
public final class TravelState {

    /** Current phase. IDLE means no active mission. */
    public TravelPhase phase = TravelPhase.IDLE;

    /** Who currently owns movement. */
    public MovementOwner owner = MovementOwner.NONE;

    /** Active mission, or null when IDLE. */
    public TravelMission mission;

    /** Client ticks since the last phase transition. */
    public int ticksInPhase;

    /** Total client ticks since the mission started. */
    public int missionTicks;

    /** Last transition reason — for telemetry & logs. */
    public String lastTransitionReason = "";

    /** When PAUSED, the phase to resume to. */
    public TravelPhase pausedFromPhase = TravelPhase.IDLE;

    /** Last abort reason, set before ABORTED. */
    public String abortReason = "";

    /** Wall-clock time when the mission was started (for duration telemetry). */
    public long missionStartMs = 0;

    // ── Delivery-specific runtime state ───────────────────────────────────────

    /** Recorded home position [x, y, z] — set at DELIVERY_INIT. */
    public HomeTracker homeTracker = null;

    /** Active gather operation during GATHERING phase. */
    public GatherOperation gatherOp = null;

    /** Active portal build sequence during PORTAL_CREATE / PORTAL_ENTER. */
    public PortalSequence portalSequence = null;

    /** Active chest deposit during DELIVERY phase. */
    public ChestDeposit chestDeposit = null;

    /** Active portal frame demolition during PORTAL_DESTROY phase. */
    public PortalDestroyer portalDestroyer = null;

    /** Reset everything to a fresh IDLE state. */
    public void reset() {
        phase = TravelPhase.IDLE;
        owner = MovementOwner.NONE;
        mission = null;
        ticksInPhase = 0;
        missionTicks = 0;
        lastTransitionReason = "";
        pausedFromPhase = TravelPhase.IDLE;
        abortReason = "";
        missionStartMs = 0;
        homeTracker = null;
        gatherOp = null;
        portalSequence = null;
        chestDeposit = null;
        portalDestroyer = null;
    }
}

