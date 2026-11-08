package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooperativePreemptionGateTest {
    private static final int FIVE_MINUTES = 300 * 20;
    private static final int TWO_SECONDS = 2 * 20;
    private static final long SECOND_NANOS = 1_000_000_000L;

    @Test
    void quietTaskStillWaitsFullMinimumHold() {
        var gate = new CooperativePreemptionGate(FIVE_MINUTES, TWO_SECONDS);
        assertEquals(CooperativePreemptionGate.Transition.YIELDED, gate.yield());

        for (int tick = 1; tick < FIVE_MINUTES; tick++) {
            assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        }
        assertTrue(gate.isYielded());
        assertEquals(CooperativePreemptionGate.Transition.RESUMED, gate.tick(false));
        assertFalse(gate.isYielded());
    }

    @Test
    void longTaskCountsTowardHoldAndOnlyNeedsQuietWindowAfterward() {
        var gate = new CooperativePreemptionGate(FIVE_MINUTES, TWO_SECONDS);
        gate.yield();

        for (int tick = 0; tick < FIVE_MINUTES + 200; tick++) {
            assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(true));
        }
        for (int tick = 1; tick < TWO_SECONDS; tick++) {
            assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        }
        assertEquals(CooperativePreemptionGate.Transition.RESUMED, gate.tick(false));
    }

    @Test
    void renewedActivityResetsQuietWindowButNotElapsedHold() {
        var gate = new CooperativePreemptionGate(5, 3);
        gate.yield();

        for (int tick = 0; tick < 5; tick++) gate.tick(true);
        gate.tick(false);
        gate.tick(false);
        int elapsedBeforeInterruption = gate.elapsedTicks();
        gate.tick(true);

        assertTrue(gate.elapsedTicks() > elapsedBeforeInterruption);
        assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        assertEquals(CooperativePreemptionGate.Transition.RESUMED, gate.tick(false));
    }

    @Test
    void suspendedBotTicksCountWallTimeButNotAsQuietTime() {
        var gate = new CooperativePreemptionGate(100, 3);
        gate.yield();
        for (int tick = 0; tick < 20; tick++) gate.tick(false);

        long stoppedAt = 1_000_000_000L;
        gate.suspendClock(stoppedAt);
        assertTrue(gate.isClockSuspended());
        assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        assertEquals(20, gate.elapsedTicks());

        assertEquals(100, gate.resumeClock(stoppedAt + 5_000_000_000L));
        assertFalse(gate.isClockSuspended());
        assertEquals(120, gate.elapsedTicks());
        assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        assertEquals(CooperativePreemptionGate.Transition.NONE, gate.tick(false));
        assertEquals(CooperativePreemptionGate.Transition.RESUMED, gate.tick(false));
    }

    @Test
    void resetClearsAControlSuspension() {
        var gate = new CooperativePreemptionGate(5, 2);
        gate.yield();
        gate.suspendClock(10_000L);

        gate.reset();

        assertFalse(gate.isYielded());
        assertFalse(gate.isClockSuspended());
        assertEquals(0, gate.resumeClock(Long.MAX_VALUE));
    }

    @Test
    void proxyControlReleaseKeepsOrganizerCheckpointInsideGraceWindow() {
        var manager = new JobContinuanceManager();
        assertEquals(JobContinuanceManager.Transition.CONTROL_STARTED,
                manager.beginControl(JobContinuanceManager.Job.ORGANIZE, 10L, 600).transition());

        assertEquals(540, manager.remainingSeconds(60L * SECOND_NANOS + 10L));
        var release = manager.tick(false, 60L * SECOND_NANOS + 10L);

        assertEquals(JobContinuanceManager.Transition.CONTROL_RELEASED, release.transition());
        assertEquals(JobContinuanceManager.Job.ORGANIZE, release.job());
        assertFalse(manager.isActive());
    }

    @Test
    void proxyControlRequestsAbortAtTenMinuteDeadline() {
        var manager = new JobContinuanceManager();
        long started = 25L * SECOND_NANOS;
        manager.beginControl(JobContinuanceManager.Job.SCAN, started, 600);

        assertEquals(JobContinuanceManager.Transition.NONE,
                manager.tick(true, started + 599L * SECOND_NANOS).transition());
        var expired = manager.tick(true, started + 600L * SECOND_NANOS);

        assertEquals(JobContinuanceManager.Transition.ABORT_REQUIRED, expired.transition());
        assertEquals(JobContinuanceManager.Job.SCAN, expired.job());
        assertTrue(manager.isActive());
        assertEquals(0, manager.remainingSeconds(started + 600L * SECOND_NANOS));
    }
}
