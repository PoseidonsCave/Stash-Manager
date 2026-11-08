package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitScanAdmissionTest {
    @Test
    void acceptsOnlyCompleteOrSingleTargetPartial() {
        assertTrue(KitScanAdmission.allows("complete", 2799, 2799, 0));
        assertTrue(KitScanAdmission.allows("partial", 2799, 2798, 1));
        assertFalse(KitScanAdmission.allows("partial", 2799, 2797, 2));
        assertFalse(KitScanAdmission.allows("partial", 99, 98, 1));
        assertFalse(KitScanAdmission.allows("aborted", 2799, 2798, 1));
        assertFalse(KitScanAdmission.allows("complete", 2799, 2798, 1));
    }
}
