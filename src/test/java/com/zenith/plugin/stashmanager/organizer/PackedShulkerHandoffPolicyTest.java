package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedShulkerHandoffPolicyTest {
    @Test
    void sameContentsDoNotAuthorizeAnotherColoredTaskBox() {
        assertTrue(PackedShulkerHandoffPolicy.uniquePickupCandidate(
                "black_shulker_box", "black_shulker_box", 1, false));
        assertFalse(PackedShulkerHandoffPolicy.uniquePickupCandidate(
                "black_shulker_box", "orange_shulker_box", 1, false));
        assertFalse(PackedShulkerHandoffPolicy.uniquePickupCandidate(
                "black_shulker_box", "black_shulker_box", 2, false));
    }

    @Test
    void handoffStopsAfterOneConfirmedBox() {
        assertTrue(PackedShulkerHandoffPolicy.maySubmit(0));
        assertFalse(PackedShulkerHandoffPolicy.maySubmit(1));
        assertFalse(PackedShulkerHandoffPolicy.maySubmit(2));
    }

    @Test
    void legacyUnknownPickupRequiresSoleMovableBox() {
        assertFalse(PackedShulkerHandoffPolicy.uniquePickupCandidate(
                null, "orange_shulker_box", 0, false));
        assertTrue(PackedShulkerHandoffPolicy.uniquePickupCandidate(
                null, "black_shulker_box", 0, true));
    }
}
