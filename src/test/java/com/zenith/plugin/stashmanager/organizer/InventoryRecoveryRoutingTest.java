package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRecoveryRoutingTest {
    private static final int[] SOURCE = {0, 64, 0};
    private static final int[] DESTINATION = {8, 64, 0};

    @Test
    void unassignedLooseItemMustBePackedBeforeImportHandoff() {
        var task = StashOrganizer.inventoryRecoveryTask(
                SOURCE, DESTINATION, "obsidian", null, false);
        assertTrue(task.alreadyInInventory());
        assertTrue(task.mixedBatchConsolidation());
        assertFalse(task.mixedDecomposition());
        // The contract survives promotion out of the consolidation queue.
        assertTrue(task.requireInventoryDeposit().mixedBatchConsolidation());
    }

    @Test
    void assignedLaneAndExistingShulkerRetainTheirOwnHandoff() {
        var lane = StashOrganizer.inventoryRecoveryTask(
                SOURCE, DESTINATION, "obsidian", null, true);
        var boxed = StashOrganizer.inventoryRecoveryTask(
                SOURCE, DESTINATION, "purple_shulker_box", "obsidian", false);
        assertFalse(lane.mixedBatchConsolidation());
        assertFalse(boxed.mixedBatchConsolidation());
    }
}
