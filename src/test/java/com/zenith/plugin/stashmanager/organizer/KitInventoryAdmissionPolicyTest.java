package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitInventoryAdmissionPolicyTest {
    @Test
    void journalOwnedRetainedShellIsAdmittedButOtherShulkersAreNot() {
        var slots = List.of(
                new KitInventoryAdmissionPolicy.Slot(37, "white_shulker_box", 1, false),
                new KitInventoryAdmissionPolicy.Slot(44, "white_shulker_box", 1, true),
                new KitInventoryAdmissionPolicy.Slot(43, "white_shulker_box", 1, false));

        assertEquals(List.of(44, 43), KitInventoryAdmissionPolicy.assess(
                Map.of("white_shulker_box", 64), slots, Set.of(37)).unexpectedSlots());
        assertEquals(List.of(43), KitInventoryAdmissionPolicy.assess(
                Map.of("white_shulker_box", 64), slots, Set.of(37, 44)).unexpectedSlots());
    }
}
