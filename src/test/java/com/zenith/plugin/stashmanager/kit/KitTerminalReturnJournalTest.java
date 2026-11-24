package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitTerminalReturnJournalTest {
    @TempDir Path temp;

    private KitTerminalReturnJournal.Job initial() {
        return new KitTerminalReturnJournal.Job(1, "cpvp", "a".repeat(64),
                new int[] {4, 5, 6},
                Map.of(13, new KitInventoryAppleOwnership.Slot(64, false, "b".repeat(64)),
                        14, new KitInventoryAppleOwnership.Slot(2, false, "b".repeat(64))),
                Map.of(36, new KitTerminalReturnJournal.StackFingerprint(
                        "totem_of_undying", 1, "c".repeat(64))),
                List.of(13, 14), 0, -1, KitTerminalReturnJournal.Phase.READY,
                null, System.currentTimeMillis());
    }

    @Test void persistsEveryBoundaryForRestartAndDoesNotLoseSlotIdentity() throws Exception {
        var store = new KitTerminalReturnJournal(temp.resolve("return.json"));
        var ready = initial();
        store.save(ready);
        var opening = store.load().orElseThrow().with(0, 13,
                KitTerminalReturnJournal.Phase.OPENING, null);
        store.save(opening);
        var submitted = store.load().orElseThrow().with(0, 13,
                KitTerminalReturnJournal.Phase.SUBMITTED, null);
        store.save(submitted);
        var recovered = new KitTerminalReturnJournal(temp.resolve("return.json"))
                .load().orElseThrow();
        assertEquals(KitTerminalReturnJournal.Phase.SUBMITTED, recovered.phase());
        assertEquals(13, recovered.activeRawSlot());
        assertEquals(2, recovered.protectedSlots().get(14).amount());
        assertEquals("totem_of_undying", recovered.otherSlots().get(36).itemId());
        assertArrayEquals(new int[] {4, 5, 6}, recovered.target());
    }

    @Test void refusesInvalidAdvanceAndMissingFingerprint() {
        var ready = initial();
        assertThrows(IllegalArgumentException.class, () -> KitTerminalReturnJournal.validate(
                ready.with(2, 14, KitTerminalReturnJournal.Phase.SUBMITTED, null)));
        var missing = new KitTerminalReturnJournal.Job(1, "cpvp", "a".repeat(64),
                new int[] {4, 5, 6}, ready.protectedSlots(),
                Map.of(36, new KitTerminalReturnJournal.StackFingerprint(
                        "totem_of_undying", 1, "not-a-hash")),
                ready.order(), 0, -1, KitTerminalReturnJournal.Phase.READY,
                null, System.currentTimeMillis());
        assertThrows(IllegalArgumentException.class,
                () -> KitTerminalReturnJournal.validate(missing));
    }

    @Test void onlyTheReviewedUncertainFirstClickCanBeRearmed() {
        var submitted = initial().with(0, 13,
                KitTerminalReturnJournal.Phase.SUBMITTED, null);
        var uncertain = submitted.with(0, 13,
                KitTerminalReturnJournal.Phase.REVIEW_REQUIRED,
                "exporter_export_postclose_verification_mismatch");
        assertTrue(KitTerminalReturnRunner.reviewedFirstTransferEligible(uncertain));
        assertFalse(KitTerminalReturnRunner.reviewedFirstTransferEligible(submitted));
        assertFalse(KitTerminalReturnRunner.reviewedFirstTransferEligible(
                uncertain.with(0, 13, KitTerminalReturnJournal.Phase.REVIEW_REQUIRED,
                        "return_source_changed_before_click")));
        assertFalse(KitTerminalReturnRunner.reviewedFirstTransferEligible(
                uncertain.with(1, 14, KitTerminalReturnJournal.Phase.REVIEW_REQUIRED,
                        "exporter_export_postclose_verification_mismatch")));
    }
}
