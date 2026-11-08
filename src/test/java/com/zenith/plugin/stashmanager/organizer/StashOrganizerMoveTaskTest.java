package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StashOrganizerMoveTaskTest {
    @Test
    void restoredMixedCargoKeepsDecompositionWhenMarkedInInventory() {
        StashOrganizer.MoveTask task = StashOrganizer.MoveTask.mixed(
                new int[]{1, 2, 3}, new int[]{4, 5, 6}, "pink_shulker_box",
                "fingerprint", Map.of("diamond_sword", 1, "end_crystal", 64));

        StashOrganizer.MoveTask restored = task.markAlreadyInInventory();

        assertTrue(restored.alreadyInInventory());
        assertTrue(restored.mixedDecomposition());
    }
}
