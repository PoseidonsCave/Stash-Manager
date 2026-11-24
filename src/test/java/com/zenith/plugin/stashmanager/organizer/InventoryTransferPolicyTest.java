package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryTransferPolicyTest {
    @Test
    void waitsForTheRequestAndAuthoritativeSlotUpdate() {
        assertEquals(InventoryTransferPolicy.Result.WAIT,
                InventoryTransferPolicy.assess(true, false, false,
                        false, false, 0, 40));
        assertEquals(InventoryTransferPolicy.Result.WAIT,
                InventoryTransferPolicy.assess(true, true, true,
                        false, false, 39, 40));
    }

    @Test
    void distinguishesDrainedAndPartialSourceUpdates() {
        assertEquals(InventoryTransferPolicy.Result.CONFIRMED_DRAINED,
                InventoryTransferPolicy.assess(true, false, false,
                        true, false, 0, 40));
        assertEquals(InventoryTransferPolicy.Result.CONFIRMED_PARTIAL,
                InventoryTransferPolicy.assess(true, true, true,
                        false, true, 0, 40));
    }

    @Test
    void retriesRejectedUnacknowledgedAndMissingWindowTransfers() {
        assertEquals(InventoryTransferPolicy.Result.RETRY,
                InventoryTransferPolicy.assess(true, true, false,
                        false, false, 0, 40));
        assertEquals(InventoryTransferPolicy.Result.RETRY,
                InventoryTransferPolicy.assess(true, true, true,
                        false, false, 40, 40));
        assertEquals(InventoryTransferPolicy.Result.RETRY,
                InventoryTransferPolicy.assess(true, false, false,
                        false, false, 40, 40));
        assertEquals(InventoryTransferPolicy.Result.RETRY,
                InventoryTransferPolicy.assess(false, true, true,
                        false, false, 0, 40));
    }

    @Test
    void adoptsExactCargoGrowthFromThePlayerHalfOfAStaleWindow() {
        assertEquals(1, InventoryTransferPolicy.observedTaskCargoDelta(0, 1, 1));
        assertEquals(16, InventoryTransferPolicy.observedTaskCargoDelta(32, 48, 64));
        assertEquals(0, InventoryTransferPolicy.observedTaskCargoDelta(1, 1, 1));
        assertEquals(0, InventoryTransferPolicy.observedTaskCargoDelta(-1, 1, 1));
    }
}
