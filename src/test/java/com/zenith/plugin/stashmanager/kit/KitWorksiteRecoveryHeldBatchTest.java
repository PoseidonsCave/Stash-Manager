package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitWorksiteRecoveryHeldBatchTest {
    private static final int[] SITE = {1, 130, 2};
    @TempDir Path temporary;

    private static KitFulfillmentJournalStore.Snapshot checkpoint(
            String state, int quantity, List<KitFulfillmentJournalStore.BorrowedBox> sources) {
        return checkpoint(state, null, null, quantity, sources);
    }

    private static KitFulfillmentJournalStore.Snapshot checkpoint(
            String state, String blocker, String resumeState, int quantity,
            List<KitFulfillmentJournalStore.BorrowedBox> sources) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", state,
                Map.of("obsidian", 128, "ender_pearl", 16), Map.of("obsidian", 128),
                25, 19, "ender_pearl", quantity, 39, new int[]{3, 130, 4},
                SITE, null, null, Map.of(), blocker, 1L, resumeState, 0L, sources,
                -1, List.of(), Map.of(), 0, Map.of(), Map.of(), Map.of(),
                new KitFulfillmentJournalStore.RetainedShell(33, "shulker_box", "hash"), null);
    }

    @Test void onlyAnUncommittedPackingBatchAtTheSameWorksiteIsEligible() {
        assertTrue(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("PACKING", 16, List.of()), SITE));
        assertFalse(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("BLOCKED", 16, List.of()), SITE));
        assertTrue(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("BLOCKED", "process_restarted", "PACKING", 16, List.of()), SITE));
        assertFalse(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("BLOCKED", "process_restarted", "WAIT_CARGO", 16, List.of()), SITE));
        assertFalse(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("PACKING", 17, List.of()), SITE));
        assertFalse(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("PACKING", 16, List.of()), new int[]{1, 130, 3}));
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{5, 130, 6}, "shulker_box", Map.of());
        assertFalse(KitWorksiteRecovery.canReconcileAlreadyHeldCompletedBatch(
                checkpoint("PACKING", 16, List.of(source)), SITE));
    }

    @Test void verifiedRetainedShellMustBeRebasedBeforeSavingTheRecoveredOwner()
            throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("recovered.json"));
        var shell = new KitFulfillmentJournalStore.RetainedShell(
                33, "shulker_box", "a".repeat(64));
        var recovered = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128, "ender_pearl", 16),
                Map.of("obsidian", 128, "ender_pearl", 16),
                25, 19, null, 0, 37, new int[]{3, 130, 4},
                null, null, null, Map.of(), "recovered_packed_batch_verified",
                2L, null, 0L, List.of(), -1, List.of(), Map.of(),
                0, Map.of(), Map.of(), Map.of(), shell, null);
        assertThrows(IOException.class, () -> store.save(recovered.withRetainedShell(
                new KitFulfillmentJournalStore.RetainedShell(
                        37, shell.itemId(), shell.componentsHash()))));
        store.save(recovered);
        assertEquals(33, store.load().orElseThrow().retainedShell().rawSlot());
    }
}
