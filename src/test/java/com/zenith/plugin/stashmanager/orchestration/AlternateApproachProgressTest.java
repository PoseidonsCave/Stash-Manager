package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlternateApproachProgressTest {
    @Test
    void rejectsStationaryApproachBeforeNormalWalkTimeout() {
        var progress = new AlternateApproachProgress();
        for (int tick = 1; tick < AlternateApproachProgress.IDLE_LIMIT_TICKS; tick++) {
            assertFalse(progress.stalled(5, 64, 0, 0.5, 64, 0.5));
        }
        assertTrue(progress.stalled(5, 64, 0, 0.5, 64, 0.5));
    }

    @Test
    void movementResetsIdleButCirclingStillHitsTripLimit() {
        var progress = new AlternateApproachProgress();
        for (int tick = 0; tick < AlternateApproachProgress.TRIP_LIMIT_TICKS - 1; tick++) {
            double x = tick % 2 == 0 ? 0.5 : 1.0;
            assertFalse(progress.stalled(5, 64, 0, x, 64, 0.5));
        }
        assertTrue(progress.stalled(5, 64, 0, 0.5, 64, 0.5));
    }

    @Test
    void newGoalStartsAFreshBudget() {
        var progress = new AlternateApproachProgress();
        for (int tick = 0; tick < AlternateApproachProgress.IDLE_LIMIT_TICKS - 1; tick++) {
            progress.stalled(5, 64, 0, 0.5, 64, 0.5);
        }
        assertFalse(progress.stalled(6, 64, 0, 0.5, 64, 0.5));
        assertEquals(1, progress.elapsedTicks());
    }
}
