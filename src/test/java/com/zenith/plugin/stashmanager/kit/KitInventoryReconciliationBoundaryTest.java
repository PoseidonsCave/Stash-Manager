package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitInventoryReconciliationBoundaryTest {
    @Test void noKitCheckpointAllowsInventoryOnlyPreparationWithoutPinnedShell() {
        var boundary = KitInventoryReconciliationBoundary.select(null);
        assertFalse(boundary.hasOwnedShell());
        assertEquals(Set.of(), boundary.pinnedSlots());
        assertTrue(KitInventoryReconciliationBoundary.matchesActive(null, Set.of()));
        assertFalse(KitInventoryReconciliationBoundary.matchesActive(null, Set.of(37)));
    }

    @Test void reviewedOldKitBoundaryPinsOnlyItsOwnedShell() {
        var checkpoint = checkpoint(null);
        var boundary = KitInventoryReconciliationBoundary.select(checkpoint);
        assertTrue(boundary.hasOwnedShell());
        assertEquals(Set.of(37), boundary.pinnedSlots());
        assertTrue(KitInventoryReconciliationBoundary.matchesActive(checkpoint, Set.of(37)));
        assertFalse(KitInventoryReconciliationBoundary.matchesActive(checkpoint, Set.of()));
    }

    @Test void activeOrUnreviewedKitCheckpointCannotBeReconciled() {
        assertNull(KitInventoryReconciliationBoundary.select(checkpoint("NEED_CARGO")));
        var active = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "ACTIVE",
                Map.of("obsidian", 64), Map.of(), 1, 0, "obsidian", 64,
                37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                null, 100, null, 0);
        assertNull(KitInventoryReconciliationBoundary.select(active));
        assertFalse(KitInventoryReconciliationBoundary.matchesActive(active, Set.of()));
    }

    @Test void completedKitWithUnmappedDisplacedApplesCannotStartGenericLaneHandoff() {
        var terminal = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128), Map.of(),
                25, 25, null, 0, -1, new int[]{1, 64, 2}, null,
                null, null, Map.of(), "kit_displaced_apples_require_reconciliation",
                100, null, 0, List.of(), -1, List.of(), Map.of(),
                0, Map.of(), Map.of(),
                Map.of(16, new KitInventoryAppleOwnership.Slot(64, true, "a".repeat(64))));
        assertNull(KitInventoryReconciliationBoundary.select(terminal));
        assertFalse(KitInventoryReconciliationBoundary.matchesActive(terminal, Set.of(16)));
    }

    private static KitFulfillmentJournalStore.Snapshot checkpoint(String resumeState) {
        var loss = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "black_shulker_box", Map.of("obsidian", 64));
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 64), Map.of(), 1, 0, "obsidian", 64,
                37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "lost_borrowed_source_operator_attested_needs_cargo_audit",
                100, resumeState, 0, List.of(), -1, List.of(loss), Map.of(),
                0, Map.of(), Map.of());
    }
}
