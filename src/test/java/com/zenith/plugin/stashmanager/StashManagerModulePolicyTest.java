package com.zenith.plugin.stashmanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashManagerModulePolicyTest {
    @Test
    void liveFoodProbeDoesNotDependOnZenithWarningEvent() {
        assertTrue(StashManagerModule.shouldProbeFoodRefill(true, true, false, 14, 10));
        assertFalse(StashManagerModule.shouldProbeFoodRefill(true, true, false, 15, 10));
        assertFalse(StashManagerModule.shouldProbeFoodRefill(false, true, false, 0, 10));
        assertFalse(StashManagerModule.shouldProbeFoodRefill(true, false, false, 0, 10));
        assertFalse(StashManagerModule.shouldProbeFoodRefill(true, true, true, 0, 10));
    }

    @Test
    void manualStopDuringYieldCancelsInsteadOfSchedulingAResume() {
        assertFalse(StashManagerModule.shouldReturnToStartAfterAbort(
                true, true, true, true));
    }

    @Test
    void activeScanCanStillReturnImmediatelyAfterManualStop() {
        assertTrue(StashManagerModule.shouldReturnToStartAfterAbort(
                true, true, true, false));
    }

    @Test
    void returnRequiresTheCommandConfigAndStartPosition() {
        assertFalse(StashManagerModule.shouldReturnToStartAfterAbort(
                false, true, true, false));
        assertFalse(StashManagerModule.shouldReturnToStartAfterAbort(
                true, false, true, false));
        assertFalse(StashManagerModule.shouldReturnToStartAfterAbort(
                true, true, false, false));
    }
}
