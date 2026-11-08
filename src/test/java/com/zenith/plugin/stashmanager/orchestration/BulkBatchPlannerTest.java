package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkBatchPlannerTest {
    @Test
    void gathersSameExactVariantAcrossSources() {
        assertTrue(BulkBatchPlanner.shouldCollectNext(
                "diamond_pickaxe[fortune]", "diamond_pickaxe[fortune]", false, true));
    }

    @Test
    void stopsBeforeAnotherVariantOrWhenInventoryIsFull() {
        assertFalse(BulkBatchPlanner.shouldCollectNext(
                "diamond_pickaxe[fortune]", "diamond_pickaxe[silk_touch]", false, true));
        assertFalse(BulkBatchPlanner.shouldCollectNext(
                "end_crystal", "end_crystal", false, false));
    }

    @Test
    void inventoryRecoveryTaskEndsTheSourceGatheringBatch() {
        assertFalse(BulkBatchPlanner.shouldCollectNext(
                "end_crystal", "end_crystal", true, true));
    }
}
