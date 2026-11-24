package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerPickupSweepTest {
    @Test
    void beginsAtDropThenSweepsNearbyBlocks() {
        int[] origin = {10, 64, 20};

        assertArrayEquals(new int[]{10, 64, 20}, ShulkerPickupSweep.target(origin, 0));
        assertArrayEquals(new int[]{11, 64, 20}, ShulkerPickupSweep.target(origin, 1));
        assertArrayEquals(new int[]{10, 64, 21}, ShulkerPickupSweep.target(origin, 2));
        assertArrayEquals(origin,
                ShulkerPickupSweep.target(origin, ShulkerPickupSweep.targetCount()));
    }

    @Test
    void pathRequestsAreCadencedAndNeverReplaceAnActivePath() {
        assertTrue(ShulkerPickupSweep.shouldIssuePath(1, -1, false));
        assertFalse(ShulkerPickupSweep.shouldIssuePath(20, 1, false));
        assertTrue(ShulkerPickupSweep.shouldIssuePath(21, 1, false));
        assertFalse(ShulkerPickupSweep.shouldIssuePath(100, 1, true));
    }
}
