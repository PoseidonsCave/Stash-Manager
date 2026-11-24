package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerWorksitePolicyTest {
    @Test
    void acceptsOrdinarySolidFloorAtPlayerLevel() {
        assertTrue(ShulkerWorksitePolicy.isSafe(
                0, true, true, false, true, false, false, false));
    }

    @Test
    void rejectsChestOrOtherInteractableFloor() {
        assertFalse(ShulkerWorksitePolicy.isSafe(
                0, true, true, false, true, true, false, true));
    }

    @Test
    void rejectsIndexedContainerEvenWhenLiveCacheLooksSafe() {
        assertFalse(ShulkerWorksitePolicy.isSafe(
                0, true, true, false, true, false, false, true));
    }

    @Test
    void rejectsShelfAboveOrBelowPlayerLevel() {
        assertFalse(ShulkerWorksitePolicy.isSafe(
                1, true, true, false, true, false, false, false));
        assertFalse(ShulkerWorksitePolicy.isSafe(
                -1, true, true, false, true, false, false, false));
    }

    @Test
    void rejectsBlockedHeadSpaceAndInteractiveNeighbors() {
        assertFalse(ShulkerWorksitePolicy.isSafe(
                0, true, false, false, true, false, false, false));
        assertFalse(ShulkerWorksitePolicy.isSafe(
                0, true, true, false, true, false, true, false));
    }
}
