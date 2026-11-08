package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryShulkerRecoveryStatusTest {
    @Test
    void reportsPlacedBlockAsPreservedCargo() {
        var status = TemporaryShulkerRecoveryStatus.assess(true, 3, 2);

        assertTrue(status.blockPresent());
        assertFalse(status.inventoryRecovered());
        assertTrue(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.PLACED_BLOCK, status.cargoState());
    }

    @Test
    void reportsRecoveredInventory() {
        var status = TemporaryShulkerRecoveryStatus.assess(false, 3, 3);

        assertTrue(status.inventoryRecovered());
        assertTrue(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.INVENTORY, status.cargoState());
    }

    @Test
    void reportsUnverifiedDropWithoutTreatingZeroAsRecovered() {
        var status = TemporaryShulkerRecoveryStatus.assess(false, 0, 0);

        assertFalse(status.inventoryRecovered());
        assertFalse(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.UNVERIFIED_DROP, status.cargoState());
    }

    @Test
    void collectionPacketOverridesLaggingAggregateInventoryCount() {
        var status = TemporaryShulkerRecoveryStatus.assess(false, 26, 25, true, false);

        assertTrue(status.inventoryRecovered());
        assertTrue(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.INVENTORY, status.cargoState());
    }

    @Test
    void expectedRecoveredBoxRepairsLegacyCheckpointCount() {
        var status = TemporaryShulkerRecoveryStatus.assess(false, 26, 25, false, true);

        assertTrue(status.inventoryRecovered());
        assertTrue(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.INVENTORY, status.cargoState());
    }

    @Test
    void placedBlockOverridesAnotherMatchingBoxInInventory() {
        var status = TemporaryShulkerRecoveryStatus.assess(true, 26, 25, true, true);

        assertFalse(status.inventoryRecovered());
        assertTrue(status.cargoPreserved());
        assertEquals(TemporaryShulkerRecoveryStatus.CargoState.PLACED_BLOCK, status.cargoState());
    }
}
