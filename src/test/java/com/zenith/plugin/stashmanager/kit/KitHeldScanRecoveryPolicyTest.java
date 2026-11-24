package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitHeldScanRecoveryPolicyTest {
    @Test
    void onlyOneFailedContainerWithIndexedSourceMayUsePartialScan() {
        assertTrue(KitHeldScanRecoveryPolicy.accepts(false, true,
                2798, 2797, 1, "partial"));
        assertFalse(KitHeldScanRecoveryPolicy.accepts(false, false,
                2798, 2797, 1, "partial"));
        assertFalse(KitHeldScanRecoveryPolicy.accepts(false, true,
                2798, 2796, 2, "partial"));
        assertFalse(KitHeldScanRecoveryPolicy.accepts(false, true,
                2798, 2797, 1, "aborted"));
        assertFalse(KitHeldScanRecoveryPolicy.accepts(false, true,
                2798, 2797, 0, "partial"));
        assertTrue(KitHeldScanRecoveryPolicy.accepts(true, true,
                2798, 2798, 0, "complete"));
    }
}
