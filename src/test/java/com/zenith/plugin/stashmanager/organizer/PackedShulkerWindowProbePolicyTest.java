package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedShulkerWindowProbePolicyTest {
    @Test
    void laterOrdinaryFillCanUseExactCollectedBoxAfterPartialDelivery() {
        assertTrue(PackedShulkerWindowProbePolicy.partialHandoffTaskEligible(false, true));
        assertTrue(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(1, 1, 1));
        assertTrue(PackedShulkerWindowProbePolicy.taskCargoAccountedFor(248, 64, 184, 136));
        assertTrue(PackedShulkerWindowProbePolicy.matchesContents(
                java.util.Map.of("enchanted_golden_apple", 184),
                "enchanted_golden_apple", 184, 0));
    }

    @Test
    void firstOrdinaryFillCanLeaveLooseTaskUnitsWhenPacketAndLiveWindowAgree() {
        assertTrue(PackedShulkerWindowProbePolicy.partialHandoffTaskEligible(false, true));
        assertTrue(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(1, 1, 1));
        assertTrue(PackedShulkerWindowProbePolicy.taskCargoAccountedFor(128, 0, 250, 6));
        assertTrue(PackedShulkerWindowProbePolicy.matchesContents(
                java.util.Map.of("golden_carrot", 250), "golden_carrot", 250, 0));

        assertFalse(PackedShulkerWindowProbePolicy.partialHandoffTaskEligible(false, false));
        assertFalse(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(1, 2, 2));
        assertFalse(PackedShulkerWindowProbePolicy.taskCargoAccountedFor(128, 0, 120, 6));
        assertFalse(PackedShulkerWindowProbePolicy.matchesContents(
                java.util.Map.of("enchanted_golden_apple", 183),
                "enchanted_golden_apple", 184, 0));
    }

    @Test
    void unrelatedMixedBoxDoesNotInvalidateUniqueCollectedPartialShell() {
        assertTrue(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(2, 2, 1));
        assertTrue(PackedShulkerWindowProbePolicy.taskCargoAccountedFor(5, 4, 1, 0));
        assertTrue(PackedShulkerWindowProbePolicy.matchesContents(
                java.util.Map.of("totem_of_undying", 22), "totem_of_undying", 1, 1));
        assertFalse(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(2, 1, 1));
        assertFalse(PackedShulkerWindowProbePolicy.partialCollectedBoxCountProven(2, 2, 2));
    }
}
