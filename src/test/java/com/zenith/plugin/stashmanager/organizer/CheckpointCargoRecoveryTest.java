package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.zenith.plugin.stashmanager.organizer.CheckpointCargoRecovery.Disposition.COMPLETE_DESTINATION;
import static com.zenith.plugin.stashmanager.organizer.CheckpointCargoRecovery.Disposition.RESUME_HANDOFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointCargoRecoveryTest {
    @Test
    void restoredOverflowRetriesWhenExactCargoIsStillAboard() {
        assertEquals(RESUME_HANDOFF, CheckpointCargoRecovery.disposition(1));
        assertEquals(RESUME_HANDOFF, CheckpointCargoRecovery.disposition(64));
    }

    @Test
    void restoredDestinationCompletesWhenItsExactCargoIsGone() {
        assertEquals(COMPLETE_DESTINATION, CheckpointCargoRecovery.disposition(0));
    }

    @Test
    void restoredLedgerRecognizesCargoMergedIntoFiniteProtectedStacks() {
        assertEquals(13, CheckpointCargoRecovery.provableCargoUnits(13, 0, 13));
        assertEquals(13, CheckpointCargoRecovery.provableCargoUnits(13, 13, 13));
        assertEquals(13, CheckpointCargoRecovery.provableCargoUnits(13, 106, 0));
        assertEquals(0, CheckpointCargoRecovery.provableCargoUnits(13, 0, 0));
    }

    @Test
    void legacyCheckpointStillUsesObservedWholeStackCargo() {
        assertEquals(64, CheckpointCargoRecovery.provableCargoUnits(0, 64, 0));
    }

    @Test
    void recoveredPackedBoxReturnsToItsRealDestination() {
        assertTrue(StashOrganizer.shouldResumePackedShulkerHandoff(false, true, true));
        assertFalse(StashOrganizer.shouldResumePackedShulkerHandoff(true, true, true));
        assertFalse(StashOrganizer.shouldResumePackedShulkerHandoff(false, false, true));
        assertFalse(StashOrganizer.shouldResumePackedShulkerHandoff(false, true, false));
    }

    @Test
    void collectionEvidenceBridgesTheInventoryCacheDelay() {
        assertTrue(StashOrganizer.pickupEvidenceMatchesPackedShulker(
                true, "diamond_sword", "diamond_sword"));
        assertTrue(StashOrganizer.pickupEvidenceMatchesPackedShulker(
                true, "diamond_pickaxe[fortune]", "diamond_pickaxe[fortune]"));
        assertFalse(StashOrganizer.pickupEvidenceMatchesPackedShulker(
                true, "diamond_pickaxe[fortune]", "diamond_pickaxe[silk_touch]"));
        assertFalse(StashOrganizer.pickupEvidenceMatchesPackedShulker(
                false, "diamond_sword", "diamond_sword"));
    }

    @Test
    void packedPickupRequiresCollectionAndAnInventoryCandidate() {
        String stale = "9:shulker_box:partial-chorus;";

        assertFalse(StashOrganizer.packedShulkerPickupReady(
                stale, stale, false, true));
        assertFalse(StashOrganizer.packedShulkerPickupReady(
                stale, "9:shulker_box:filled-chorus;", false, true));
        assertTrue(StashOrganizer.packedShulkerPickupReady(
                stale, stale, true, true));
        assertFalse(StashOrganizer.packedShulkerPickupReady(
                null, stale, false, true));
        assertFalse(StashOrganizer.packedShulkerPickupReady(
                stale, "9:shulker_box:filled-chorus;", true, false));
    }

    @Test
    void rollbackRecoveryKeepsMixedShellsOutOfThePackedVerifier() {
        assertEquals("mixed_shell_pickup_inventory_timeout",
                StashOrganizer.pickupRollbackRecoveryTrigger(true));
        assertEquals("packed_shulker_inventory_sync",
                StashOrganizer.pickupRollbackRecoveryTrigger(false));
    }

    @Test
    void recoveredMixedShellIsNotCountedAsLostDuringResume() {
        assertFalse(StashOrganizer.shouldSubstituteMissingMixedShell(
                "temporary_shulker_pickup_recovery_failed", true));
        assertTrue(StashOrganizer.shouldSubstituteMissingMixedShell(
                "temporary_shulker_pickup_recovery_failed", false));
        assertFalse(StashOrganizer.shouldSubstituteMissingMixedShell(
                "some_other_failure", false));
    }

    @Test
    void updatedPickupStrategyRearmsOnlyLegacyTerminalRecovery() {
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                0, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                1, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                2, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                3, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                4, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(0, StashOrganizer.migratedPackedShulkerRecoveryCount(
                5, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(3, StashOrganizer.migratedPackedShulkerRecoveryCount(
                6, 3, "packed_shulker_inventory_sync_unresolved"));
        assertEquals(3, StashOrganizer.migratedPackedShulkerRecoveryCount(
                0, 3, "some_other_failure"));
    }

    @Test
    void failedResumeIsNotReportedAsActive() {
        assertFalse(StashOrganizer.isActiveState(StashOrganizer.State.FAILED));
        assertFalse(StashOrganizer.isActiveState(StashOrganizer.State.DONE));
        assertTrue(StashOrganizer.isActiveState(StashOrganizer.State.SHULKER_PICKUP));
    }

    @Test
    void legacyPackingRecoveryRequiresOneExactMixedCargoMatch() {
        var expected = new StashOrganizer.LegacyMixedPackingCandidate(
                40, "shulker_box", "mixed-a",
                Map.of("diamond_sword", 19, "iron_block", 47, "chorus_fruit", 48, "elytra", 2));
        var unrelated = new StashOrganizer.LegacyMixedPackingCandidate(
                10, "yellow_shulker_box", "mixed-b",
                Map.of("enchanted_golden_apple", 1344, "filled_map", 2));

        assertEquals(expected, StashOrganizer.selectLegacyMixedPackingCandidate(
                "minecraft:diamond_sword", 19, List.of(unrelated, expected)).orElseThrow());
        assertTrue(StashOrganizer.selectLegacyMixedPackingCandidate(
                "diamond_sword", 18, List.of(expected)).isEmpty());
        assertTrue(StashOrganizer.selectLegacyMixedPackingCandidate(
                "diamond_sword", 19, List.of(expected, expected)).isEmpty());
    }

    @Test
    void shulkerPlacementRequiresBothHeldSlotAndExactStackIdentity() {
        assertTrue(StashOrganizer.hotbarSelectionMatches(
                37, "shulker_box\u0000123", 1, 37, "shulker_box\u0000123"));
        assertFalse(StashOrganizer.hotbarSelectionMatches(
                37, "shulker_box\u0000123", 4, 40, "shulker_box\u0000123"));
        assertFalse(StashOrganizer.hotbarSelectionMatches(
                37, "shulker_box\u0000123", 1, 37, "shulker_box\u0000999"));
    }

    @Test
    void packingReserveIsKeptUnlessTheIncomingStackCanMergeCompletely() {
        assertTrue(PackingHeadroomPolicy.canTakeWithoutConsumingReserve(2, 0, 64, 1));
        assertTrue(PackingHeadroomPolicy.canTakeWithoutConsumingReserve(1, 64, 64, 1));
        assertFalse(PackingHeadroomPolicy.canTakeWithoutConsumingReserve(1, 63, 64, 1));
        assertFalse(PackingHeadroomPolicy.canTakeWithoutConsumingReserve(0, 64, 65, 1));
    }

    @Test
    void occupiedShulkerCanStillAcceptMatchingStackHeadroom() {
        assertTrue(ShulkerFillPolicy.hasCapacity(0, 1));
        assertTrue(ShulkerFillPolicy.hasCapacity(1, 0));
        assertFalse(ShulkerFillPolicy.hasCapacity(0, 0));
    }

    @Test
    void remainingCargoWithoutAnyObservedFillMovementStopsTheCycle() {
        assertTrue(ShulkerFillPolicy.stalled(0, 1));
        assertFalse(ShulkerFillPolicy.stalled(1, 1));
        assertFalse(ShulkerFillPolicy.stalled(0, 0));
    }

}
