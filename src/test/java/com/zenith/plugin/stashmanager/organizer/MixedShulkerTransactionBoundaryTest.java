package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixedShulkerTransactionBoundaryTest {
    @Test
    void mixedDecompositionOwnsOnePhysicalBoxPerTask() {
        StashOrganizer.MoveTask task = StashOrganizer.MoveTask.mixedInInventory(
                new int[]{1, 2, 3},
                new int[]{4, 5, 6},
                "gray_shulker_box",
                "fingerprint",
                Map.of("piston", 64));

        assertEquals(1, StashOrganizer.inventoryCargoUnitsForTask(task, 2));
        assertEquals(1, StashOrganizer.inventoryCargoUnitsForTask(task, 1));
        assertEquals(0, StashOrganizer.inventoryCargoUnitsForTask(task, 0));
    }

    @Test
    void ordinaryInventoryMoveStillOwnsEveryMatchingUnit() {
        StashOrganizer.MoveTask task = new StashOrganizer.MoveTask(
                new int[]{1, 2, 3},
                new int[]{4, 5, 6},
                "piston",
                null,
                true);

        assertEquals(128, StashOrganizer.inventoryCargoUnitsForTask(task, 128));
    }
}
