package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.zenith.plugin.stashmanager.organizer.KitInventoryDispositionPolicy.Route.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KitInventoryDispositionPolicyTest {
    @Test
    void usesPersistedLaneBeforeImportAndDoesNotInventOne() {
        var unexpected = List.of(slot(12, "obsidian"), slot(13, "ender_pearl"));
        var decisions = KitInventoryDispositionPolicy.plan(unexpected,
                Map.of("obsidian", new int[]{10, 64, 10}),
                List.of(new int[]{20, 64, 20}));
        assertEquals(ASSIGNED_LANE, decisions.get(0).route());
        assertEquals(SAME_ITEM_SHULKER_IN_IMPORT, decisions.get(1).route());
    }

    @Test
    void unknownShulkerRequiresIdentityReviewEvenIfItsColorHasALane() {
        var decisions = KitInventoryDispositionPolicy.plan(
                List.of(slot(12, "black_shulker_box")),
                Map.of("black_shulker_box", new int[]{10, 64, 10}),
                List.of(new int[]{20, 64, 20}));
        assertEquals(SHULKER_IDENTITY_REVIEW, decisions.getFirst().route());
    }

    @Test
    void absentImportFailsClosed() {
        var decisions = KitInventoryDispositionPolicy.plan(
                List.of(slot(12, "ender_pearl")), Map.of(), List.of());
        assertEquals(NEEDS_REGISTERED_IMPORT, decisions.getFirst().route());
    }

    @Test
    void refusesAutomaticMovesFromCraftingOrOffhandSlots() {
        var decisions = KitInventoryDispositionPolicy.plan(
                List.of(slot(1, "obsidian"), slot(45, "obsidian")),
                Map.of("obsidian", new int[]{10, 64, 10}), List.of());
        assertEquals(NON_TRANSFERABLE_SLOT_REVIEW, decisions.get(0).route());
        assertEquals(NON_TRANSFERABLE_SLOT_REVIEW, decisions.get(1).route());
    }

    private static KitInventoryAdmissionPolicy.Slot slot(int raw, String itemId) {
        return new KitInventoryAdmissionPolicy.Slot(raw, itemId, 1, false);
    }
}
