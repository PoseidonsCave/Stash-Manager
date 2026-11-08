package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KitExportAuditTest {
    @Test
    void incompleteExportCannotBeAuditedAsAFullKit() {
        var snapshot = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128),
                Map.of("enchanted_golden_apple", 110), 10, 2,
                "enchanted_golden_apple", 18, 38, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_export_failed:export_close_timeout",
                100L, null, 0L, List.of(), -1, List.of(), Map.of(),
                1, Map.of("enchanted_golden_apple", 12),
                Map.of("enchanted_golden_apple", 18));
        assertNotNull(KitExportAudit.blocker(snapshot, 3, 3, 0, 0, 0));
    }

    @Test
    void fullExportAuditRetainsPriorIncompleteSummary() {
        var snapshot = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128),
                Map.of("enchanted_golden_apple", 128), 10, 2,
                null, 0, 38, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_export_failed:export_close_timeout",
                100L, null, 0L, List.of(), -1, List.of(), Map.of(),
                1, Map.of("enchanted_golden_apple", 12), Map.of());
        assertEquals(null, KitExportAudit.blocker(snapshot, 3, 3, 0, 0, 0));
        var audited = KitExportAudit.reconciledSnapshot(snapshot, 200L);
        assertEquals(1, audited.incompleteBoxes());
        assertEquals(Map.of("enchanted_golden_apple", 12), audited.missingTotals());
    }
}
