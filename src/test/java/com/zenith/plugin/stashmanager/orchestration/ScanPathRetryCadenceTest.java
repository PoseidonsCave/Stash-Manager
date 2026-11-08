package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanPathRetryCadenceTest {
    @Test
    void inactiveGoalCannotConsumeRetriesBeforePathSettles() {
        assertFalse(ScanPathRetryCadence.shouldHandleInactive(false, 1, 0, 20));
        assertFalse(ScanPathRetryCadence.shouldHandleInactive(false, 19, 0, 20));
        assertTrue(ScanPathRetryCadence.shouldHandleInactive(false, 20, 0, 20));
    }

    @Test
    void eachNewRequestGetsItsOwnSettleWindow() {
        assertFalse(ScanPathRetryCadence.shouldHandleInactive(false, 21, 20, 20));
        assertFalse(ScanPathRetryCadence.shouldHandleInactive(false, 39, 20, 20));
        assertTrue(ScanPathRetryCadence.shouldHandleInactive(false, 40, 20, 20));
    }

    @Test
    void activeGoalNeverConsumesARepathAttempt() {
        assertFalse(ScanPathRetryCadence.shouldHandleInactive(true, 100, 0, 20));
    }
}
