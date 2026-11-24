package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedShulkerPlaybookTest {
    @Test
    void reservesAStagingSlotForTheRecoveredShell() {
        assertEquals(4, MixedShulkerPlaybook.minimumStagingSlotsWithShell(Map.of(
                "end_crystal", 65,
                "diamond_sword", 1
        )));
    }

    @Test
    void evacuatesInventoryBeforeContinuingMixedBoxes() {
        assertEquals(
                List.of("empty-shell", "bulk-box", "known-mixed", "new-mixed"),
                MixedShulkerPlaybook.inventoryRecoveryOrder(
                        List.of("empty-shell", "bulk-box"),
                        List.of("known-mixed"),
                        List.of("new-mixed")));
    }

    @Test
    void admitsOneMixedBoxWithBoundedBatchAndReserve() {
        MixedShulkerPlaybook.InventoryAdmission admission =
                MixedShulkerPlaybook.assessInventoryAdmission(5, 0, 27);

        assertTrue(admission.ready());
        assertEquals(5, admission.requiredFreeSlots());
    }

    @Test
    void existingCargoMustBeRecoveredBeforeAnotherMixedBox() {
        MixedShulkerPlaybook.InventoryAdmission admission =
                MixedShulkerPlaybook.assessInventoryAdmission(20, 2, 9);

        assertEquals(MixedShulkerPlaybook.AdmissionDecision.RECOVER_EXISTING_CARGO,
                admission.decision());
        assertFalse(admission.ready());
    }

    @Test
    void nonStackableKitNeedsBatchHeadroom() {
        MixedShulkerPlaybook.InventoryAdmission admission =
                MixedShulkerPlaybook.assessInventoryAdmission(4, 0, 27);

        assertEquals(MixedShulkerPlaybook.AdmissionDecision.INSUFFICIENT_HEADROOM,
                admission.decision());
        assertFalse(admission.ready());
    }

    @Test
    void stagesCurrentBatchBeforeConsumingReserve() {
        assertTrue(MixedShulkerPlaybook.shouldStageBeforeNextTransfer(1, 4));
        assertTrue(MixedShulkerPlaybook.shouldStageBeforeNextTransfer(2, 4));
        assertFalse(MixedShulkerPlaybook.shouldStageBeforeNextTransfer(2, 3));
        assertFalse(MixedShulkerPlaybook.shouldStageBeforeNextTransfer(1, 0));
    }
}
