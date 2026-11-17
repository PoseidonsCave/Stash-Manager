package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitInventoryAdmissionPolicyTest {
    @Test
    void auditedAppleBatchMustNotConsumeTheBaselineKeepQuota() {
        var slots = List.of(
                new KitInventoryAdmissionPolicy.Slot(9, "enchanted_golden_apple", 64, true),
                new KitInventoryAdmissionPolicy.Slot(10, "enchanted_golden_apple", 55, true),
                new KitInventoryAdmissionPolicy.Slot(11, "enchanted_golden_apple", 64, true),
                new KitInventoryAdmissionPolicy.Slot(12, "enchanted_golden_apple", 3, false),
                new KitInventoryAdmissionPolicy.Slot(29, "enchanted_golden_apple", 1, false),
                new KitInventoryAdmissionPolicy.Slot(30, "enchanted_golden_apple", 64, false),
                new KitInventoryAdmissionPolicy.Slot(36, "enchanted_golden_apple", 4, true));
        var keep = Map.of("enchanted_golden_apple", 128);

        assertEquals(List.of(12, 29, 30, 36), KitInventoryAdmissionPolicy.assess(
                keep, slots, Set.of()).unexpectedSlots());
        assertEquals(List.of(), KitInventoryAdmissionPolicy.assess(
                keep, slots, Set.of(9, 11)).unexpectedSlots());
    }

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
