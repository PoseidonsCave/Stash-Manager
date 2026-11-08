package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanNavigationPolicyTest {
    private static final int[] POS1 = {100, 64, 100};
    private static final int[] POS2 = {120, 80, 120};

    @Test void transitCorridorIncludesStartAndRegionButNotUnrelatedSpace() {
        assertFalse(ScanNavigationPolicy.isOutsideTransitEnvelope(
                60, 70, 110, 40, 70, 110, POS1, POS2, 4, 2));
        assertFalse(ScanNavigationPolicy.isOutsideTransitEnvelope(
                110, 70, 110, 40, 70, 110, POS1, POS2, 4, 2));
        assertTrue(ScanNavigationPolicy.isOutsideTransitEnvelope(
                60, 70, 150, 40, 70, 110, POS1, POS2, 4, 2));
        assertTrue(ScanNavigationPolicy.isOutsideTransitEnvelope(
                60, 90, 110, 40, 70, 110, POS1, POS2, 4, 2));
    }

    @Test void standardRegionEnvelopeStillRejectsDistantStart() {
        assertTrue(ScanNavigationPolicy.isOutsideRegionEnvelope(
                40, 70, 110, POS1, POS2, 4, 2));
        assertFalse(ScanNavigationPolicy.isOutsideRegionEnvelope(
                99, 70, 110, POS1, POS2, 4, 2));
    }
}
