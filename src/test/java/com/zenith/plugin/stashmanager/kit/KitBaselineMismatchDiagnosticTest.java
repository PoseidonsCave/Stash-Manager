package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitBaselineMismatchDiagnosticTest {
    @Test void looseDifferenceIsBoundedSortedAndSingleLine() {
        String detail = KitBaselineMismatchDiagnostic.looseTotals(
                Map.of("z_item", 1, "a_item", 2, "b_item", 3, "c_item", 4),
                Map.of("z_item", 0, "a_item", 1, "b_item", 2, "c_item", 3));
        assertEquals("loose_totals; a_item expected=2 observed=1"
                + "; b_item expected=3 observed=2"
                + "; c_item expected=4 observed=3; changed_items=4", detail);
        assertFalse(detail.contains("z_item"));
    }

    @Test void unsafeItemIdCannotInjectLineOrControlCharacters() {
        String detail = KitBaselineMismatchDiagnostic.looseTotals(
                Map.of("custom\nitem\u001b[31m", 1), Map.of());
        assertTrue(detail.contains("custom?item?[31m"));
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\u001b"));
    }

    @Test void shulkerDifferenceReportsIdentityCountsWithoutContents() {
        var expectedBox = new KitShulkerLedger.Identity("shulker_box",
                Map.of("obsidian", 1631));
        var changedBox = new KitShulkerLedger.Identity("shulker_box",
                Map.of("obsidian", 1630));
        String detail = KitBaselineMismatchDiagnostic.shulkerLedger(
                "retrieval_box_ledger", Map.of(expectedBox, 1), Map.of(changedBox, 1));
        assertEquals("shulker_ledger; reason=retrieval_box_ledger; expected_boxes=1"
                + "; observed_boxes=1; missing_boxes=1; extra_boxes=1", detail);
        assertFalse(detail.contains("obsidian"));
    }
}
