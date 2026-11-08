package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitInventoryReconciliationRoutingTest {
    @Test
    void laneLessLooseCargoMustBePackedBeforeAnImportHandoff() {
        var task = StashOrganizer.inventoryRecoveryTask(
                new int[]{1, 64, 1}, new int[]{2, 64, 2},
                "obsidian", null, false);
        assertTrue(task.alreadyInInventory());
        assertTrue(task.mixedBatchConsolidation());
    }

    @Test
    void PersistedLaneCargoMayUseDirectVerifiedDeposit() {
        var task = StashOrganizer.inventoryRecoveryTask(
                new int[]{1, 64, 1}, new int[]{2, 64, 2},
                "obsidian", null, true);
        assertTrue(task.alreadyInInventory());
        assertFalse(task.mixedBatchConsolidation());
    }

    @Test
    void mixedBoxCanBeStagedIntactOnlyUnderItsExactContentFingerprint() {
        var mixed = ShulkerClassification.classify(
                java.util.Map.of("totem_of_undying", 4, "firework_rocket", 64));
        var different = ShulkerClassification.classify(
                java.util.Map.of("totem_of_undying", 5, "firework_rocket", 64));
        var task = new StashOrganizer.MoveTask(new int[]{1, 64, 1},
                new int[]{2, 64, 2}, "pink_shulker_box",
                "@mixed:" + mixed.fingerprint(), true);

        assertFalse(task.mixedDecomposition());
        assertTrue(StashOrganizer.matchesShulkerTaskFilter(task, mixed));
        assertFalse(StashOrganizer.matchesShulkerTaskFilter(task, different));
    }

    @Test
    void remainderMayBeReplannedOnlyAfterAnExplicitZeroCargoQuiescentResume() {
        assertTrue(StashOrganizer.safeInventoryReconciliationRemainderBoundary(
                true, "inventory_reconciliation_incomplete", true, 0,
                false, false, false, false));
        assertFalse(StashOrganizer.safeInventoryReconciliationRemainderBoundary(
                true, "inventory_reconciliation_incomplete", true, 1,
                false, false, false, false));
        assertFalse(StashOrganizer.safeInventoryReconciliationRemainderBoundary(
                true, "inventory_reconciliation_incomplete", true, 0,
                false, true, false, false));
        assertFalse(StashOrganizer.safeInventoryReconciliationRemainderBoundary(
                true, "inventory_reconciliation_incomplete", true, 0,
                false, false, true, false));
        assertFalse(StashOrganizer.safeInventoryReconciliationRemainderBoundary(
                true, "packed_shulker_destination_unavailable_with_cargo", true, 0,
                false, false, false, false));
    }
}
