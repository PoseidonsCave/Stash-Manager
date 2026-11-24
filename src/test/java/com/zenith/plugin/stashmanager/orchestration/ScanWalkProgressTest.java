package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanWalkProgressTest {
    @Test
    void longMovingWalkOutlivesFormerThirtySecondTimeout() {
        ScanWalkProgress progress = new ScanWalkProgress();
        assertFalse(progress.stalled(0, 64, 0));
        for (int tick = 1; tick <= 900; tick++) {
            assertFalse(progress.stalled(tick / 10.0, 64, 0));
        }
    }

    @Test
    void stationaryWalkStillTimesOutAndResetStartsFreshAttempt() {
        ScanWalkProgress progress = new ScanWalkProgress();
        assertFalse(progress.stalled(0, 64, 0));
        for (int tick = 1; tick < ScanWalkProgress.IDLE_LIMIT_TICKS; tick++) {
            assertFalse(progress.stalled(0, 64, 0));
        }
        assertTrue(progress.stalled(0, 64, 0));
        progress.reset();
        assertFalse(progress.stalled(0, 64, 0));
    }

    @Test
    void movementCannotKeepOneTripAliveForever() {
        ScanWalkProgress progress = new ScanWalkProgress();
        assertFalse(progress.stalled(0, 64, 0));
        for (int tick = 1; tick < ScanWalkProgress.TRIP_LIMIT_TICKS; tick++) {
            assertFalse(progress.stalled(tick, 64, 0));
        }
        assertTrue(progress.stalled(ScanWalkProgress.TRIP_LIMIT_TICKS, 64, 0));
    }
}
