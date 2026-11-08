package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitResumeCadenceTest {
    @Test
    void waitsForBothDeadlineAndQuietWindow() {
        KitResumeCadence cadence = new KitResumeCadence(3);
        assertFalse(cadence.tick(100, 500, true));
        assertFalse(cadence.tick(200, 500, true));
        assertFalse(cadence.tick(300, 500, true));
        assertFalse(cadence.tick(500, 500, false));
        assertFalse(cadence.tick(501, 500, true));
        assertFalse(cadence.tick(502, 500, true));
        assertTrue(cadence.tick(503, 500, true));
    }
}
