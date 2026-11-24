package com.zenith.plugin.stashmanager;

import com.zenith.plugin.stashmanager.kit.KitFulfillmentJournalStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitVariantSourceAuditBoundaryTest {
    @Test
    void acceptsOnlyTheAlreadyAuditedPackedStackSlotAfterWorksiteRecovery() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{5, 130, 6}, "pink_shulker_box",
                Map.of("ender_chest", 48));
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 132, 4}, 54, -1, "a".repeat(64),
                "ender_chest", 6, 17, 18, "a".repeat(64),
                "b".repeat(64), "c".repeat(64), -1, null, null,
                9, 2, true);
        var recovered = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("ender_chest", 16, "experience_bottle", 128),
                Map.of("ender_chest", 16), 54, 39, "ender_chest", 16, 36,
                new int[]{1, 131, 2}, null, null, null, Map.of(),
                "kit_variant_source_cargo_unverified", 100L, null, 0L,
                List.of(source), -1, List.of(), Map.of(), 0, Map.of(),
                Map.of(), Map.of(), null, repair);

        assertTrue(StashManagerModule.recoveredPackedStackSourceAuditBoundary(
                recovered, 9, null, null));
        assertFalse(StashManagerModule.recoveredPackedStackSourceAuditBoundary(
                recovered, 10, null, null));
        assertFalse(StashManagerModule.recoveredPackedStackSourceAuditBoundary(
                recovered, 9, "a".repeat(64), null));
        assertFalse(StashManagerModule.recoveredPackedStackSourceAuditBoundary(
                null, 9, null, null));
    }
}
