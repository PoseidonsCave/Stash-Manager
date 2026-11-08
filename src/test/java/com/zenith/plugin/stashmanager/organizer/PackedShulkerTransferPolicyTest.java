package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedShulkerTransferPolicyTest {
    @Test
    void confirmsOnlyAfterSubmittedCargoLeavesTheLivePlayerWindow() {
        assertTrue(StashOrganizer.packedShulkerTransferConfirmed(1, 0, 1));
        assertTrue(StashOrganizer.packedShulkerTransferConfirmed(3, 1, 2));
    }

    @Test
    void doesNotTreatSubmissionOrAnUnchangedSnapshotAsDelivery() {
        assertFalse(StashOrganizer.packedShulkerTransferConfirmed(1, 1, 1));
        assertFalse(StashOrganizer.packedShulkerTransferConfirmed(1, 0, 0));
        assertFalse(StashOrganizer.packedShulkerTransferConfirmed(0, 0, 1));
    }

    @Test
    void confirmedFillWithOneRecoveredBoxRepairsAStalePackedShape() {
        assertTrue(StashOrganizer.packedShulkerTransactionProven(
                false, true, 128, 128, 0, 0, 1, true, false));
    }

    @Test
    void transactionRepairRejectsAmbiguityOrIncompleteCargoEvidence() {
        assertFalse(StashOrganizer.packedShulkerTransactionProven(
                false, true, 128, 128, 0, 0, 2, true, false));
        assertFalse(StashOrganizer.packedShulkerTransactionProven(
                false, true, 128, 128, 0, 64, 1, true, false));
        assertFalse(StashOrganizer.packedShulkerTransactionProven(
                false, true, 128, 128, 0, 0, 1, true, true));
    }

    @Test
    void exactWorksitePickupBridgesAMissingRawInventorySlot() {
        assertTrue(StashOrganizer.packedShulkerPickupTransactionProven(
                true, false, true, 1, 1, 0, 0, true, false));
        assertFalse(StashOrganizer.packedShulkerPickupTransactionProven(
                false, false, true, 1, 1, 0, 0, true, false));
        assertFalse(StashOrganizer.packedShulkerPickupTransactionProven(
                true, false, true, 1, 1, 0, 1, true, false));
        assertFalse(StashOrganizer.packedShulkerPickupTransactionProven(
                true, false, true, 1, 1, 0, 0, true, true));
    }

    @Test
    void confirmedPickupAllowsExtraSameClassCargoPackedFromInventory() {
        assertTrue(StashOrganizer.packedShulkerPickupTransactionProven(
                true, false, true, 350, 286, 0, 0, true, false));
        assertFalse(StashOrganizer.packedShulkerPickupTransactionProven(
                true, false, true, 350, 0, 0, 0, true, false));
    }

    @Test
    void recoveredEmptyShellCanOverrideOnlyItsUniqueStaleMixedSnapshot() {
        String empty = ShulkerClassification.classify(java.util.Map.of()).fingerprint();
        assertTrue(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                true, empty, false, 1, 1, 0,
                false, true, 64, 64, 0, 0, true, false));
        assertFalse(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                false, empty, false, 1, 1, 0,
                false, true, 64, 64, 0, 0, true, false));
        assertFalse(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                true, empty, false, 1, 2, 0,
                false, true, 64, 64, 0, 0, true, false));
        assertTrue(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                true, empty, true, 1, 2, 0,
                false, true, 64, 64, 0, 0, true, false));
        assertFalse(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                true, empty, true, 1, 2, 1,
                false, true, 64, 64, 0, 0, true, false));
        assertFalse(StashOrganizer.recoveredEmptyPackingShellTransactionProven(
                true, empty, true, 1, 2, 0,
                false, true, 64, 64, 0, 1, true, false));
    }

    @Test
    void packedImportProbeBudgetIsARealCeiling() {
        assertFalse(StashOrganizer.packedImportProbeBudgetReached(7));
        assertTrue(StashOrganizer.packedImportProbeBudgetReached(8));
        assertTrue(StashOrganizer.packedImportProbeBudgetReached(100));
    }

    @Test
    void looseCargoSamplesMoreImportsBeforeDeclaringExhaustion() {
        assertFalse(StashOrganizer.looseImportProbeBudgetReached(8));
        assertFalse(StashOrganizer.looseImportProbeBudgetReached(31));
        assertTrue(StashOrganizer.looseImportProbeBudgetReached(32));
        assertTrue(StashOrganizer.looseImportProbeBudgetReached(100));
    }
}
