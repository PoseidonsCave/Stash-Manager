package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitBorrowedBatchRecoveryTest {
    private static final String APPLE = "enchanted_golden_apple";
    private static final int[] ORIGIN = {100, 70, 200};

    private static KitFulfillmentJournalStore.Snapshot checkpoint() {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of(APPLE, 128), Map.of("totem_of_undying", 6), 54, 36,
                APPLE, 128, 36, new int[]{1, 2, 3}, null, null, null, Map.of(),
                "kit_cargo_retrieval_incomplete", 1L, null, 0L,
                List.of(
                        new KitFulfillmentJournalStore.BorrowedBox(ORIGIN,
                                "yellow_shulker_box", Map.of(APPLE, 724)),
                        new KitFulfillmentJournalStore.BorrowedBox(ORIGIN,
                                "gray_shulker_box", Map.of(APPLE, 1298)),
                        new KitFulfillmentJournalStore.BorrowedBox(ORIGIN,
                                "yellow_shulker_box", Map.of(APPLE, 1664))));
    }

    private static KitProvenanceJournal.BlockedRetrieval evidence() {
        return new KitProvenanceJournal.BlockedRetrieval(
                Map.of(10, 55, 12, 3), Set.of(10, 12, 36),
                Set.of(9, 10, 11, 12, 16, 33, 36, 40, 42, 44),
                "100, 70, 200");
    }

    private static Map<Integer, KitBorrowedBatchRecovery.Slot> inventory() {
        Map<Integer, KitBorrowedBatchRecovery.Slot> live = new HashMap<>();
        live.put(9, new KitBorrowedBatchRecovery.Slot(APPLE, 14, Map.of()));
        live.put(10, new KitBorrowedBatchRecovery.Slot(APPLE, 55, Map.of()));
        live.put(11, new KitBorrowedBatchRecovery.Slot(APPLE, 5, Map.of()));
        live.put(12, new KitBorrowedBatchRecovery.Slot(APPLE, 3, Map.of()));
        live.put(16, new KitBorrowedBatchRecovery.Slot(APPLE, 64, Map.of()));
        live.put(33, new KitBorrowedBatchRecovery.Slot(
                "light_blue_shulker_box", 1, Map.of(APPLE, 1640)));
        live.put(36, new KitBorrowedBatchRecovery.Slot(
                "shulker_box", 1, Map.of("totem_of_undying", 6)));
        live.put(40, new KitBorrowedBatchRecovery.Slot(
                "yellow_shulker_box", 1, Map.of(APPLE, 1664)));
        live.put(42, new KitBorrowedBatchRecovery.Slot(
                "gray_shulker_box", 1, Map.of(APPLE, 1298)));
        live.put(44, new KitBorrowedBatchRecovery.Slot(
                "yellow_shulker_box", 1, Map.of(APPLE, 724)));
        return live;
    }

    @Test void acceptsOnlyTheExactObservedCargoAndAllFourBoxes() {
        var plan = KitBorrowedBatchRecovery.inspect(checkpoint(), evidence(), inventory());
        assertTrue(plan.accepted(), plan.blocker());
        assertEquals(Map.of(9, 14, 11, 5, 16, 64), plan.cargoSlots());
        assertEquals(4, plan.sources().size());
        assertEquals(Map.of(APPLE, 1640), plan.sources().getLast().contents());
    }

    @Test void refusesChangedBaselineOrUntrackedSource() {
        var changed = inventory();
        changed.put(10, new KitBorrowedBatchRecovery.Slot(APPLE, 54, Map.of()));
        assertFalse(KitBorrowedBatchRecovery.inspect(checkpoint(), evidence(), changed).accepted());
        var extra = inventory();
        extra.put(33, new KitBorrowedBatchRecovery.Slot(
                "light_blue_shulker_box", 1, Map.of(APPLE, 1639)));
        extra.put(35, new KitBorrowedBatchRecovery.Slot(
                "light_blue_shulker_box", 1, Map.of(APPLE, 1)));
        assertFalse(KitBorrowedBatchRecovery.inspect(checkpoint(), evidence(), extra).accepted());
        var alreadyHeld = new KitProvenanceJournal.BlockedRetrieval(
                Map.of(10, 55, 12, 3), Set.of(10, 12, 33, 36),
                evidence().blockedOccupiedSlots(), evidence().sourcePosition());
        assertFalse(KitBorrowedBatchRecovery.inspect(
                checkpoint(), alreadyHeld, inventory()).accepted());
    }
}
