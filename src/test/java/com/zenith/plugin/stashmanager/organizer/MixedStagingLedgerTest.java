package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MixedStagingLedgerTest {
    private final int[] a = {1, 64, 0}, b = {2, 64, 0};

    @Test
    void twoTypesInDifferentChestsProduceTwoTasksNotFour() {
        var ledger = new MixedStagingLedger();
        ledger.begin("stone", a); ledger.confirm();
        ledger.begin("end_crystal", b); ledger.confirm();
        var pairs = ledger.sources(List.of("stone", "end_crystal"), List.of(a, b));
        assertEquals(2, pairs.size());
        assertTrue(pairs.contains(new MixedStagingLedger.Source("stone", 1, 64, 0)));
        assertFalse(pairs.contains(new MixedStagingLedger.Source("stone", 2, 64, 0)));
    }

    @Test
    void partialDepositsAndRetriesDeduplicateButSplitDestinationsRemain() {
        var ledger = new MixedStagingLedger();
        ledger.begin("stone", a); ledger.confirm();
        ledger.begin("stone", a); ledger.confirm();
        ledger.begin("stone", b); ledger.confirm();
        assertEquals(2, ledger.sources(List.of("stone"), List.of(a, b)).size());
    }

    @Test
    void uncertainAttemptSurvivesRestartWithoutCreatingOtherItemChestPairs() {
        var ledger = new MixedStagingLedger();
        ledger.begin("stone", a); ledger.confirm();
        ledger.begin("end_crystal", b);
        var restored = new MixedStagingLedger();
        restored.restore(ledger.snapshot());
        assertEquals(2, restored.sources(List.of("stone", "end_crystal"), List.of(a, b)).size());
        assertEquals(1, restored.snapshot().uncertain().size());
        restored.begin("end_crystal", b); restored.confirm();
        assertEquals(2, restored.snapshot().confirmed().size());
        assertTrue(restored.snapshot().uncertain().isEmpty());
    }

    @Test
    void oldCheckpointUsesConservativeFallbackUntilNextBox() {
        var ledger = new MixedStagingLedger();
        ledger.restore(null);
        assertFalse(ledger.snapshot().exact());
        assertEquals(4, ledger.sources(List.of("stone", "end_crystal"), List.of(a, b)).size());
        var restoredAgain = new MixedStagingLedger();
        restoredAgain.restore(ledger.snapshot());
        assertEquals(4, restoredAgain.sources(List.of("stone", "end_crystal"), List.of(a, b)).size());
        ledger.reset();
        assertTrue(ledger.sources(List.of("stone", "end_crystal"), List.of(a, b)).isEmpty());
    }

    @Test
    void exactLedgerNeverInventsAStagingTaskForCargoStillInInventory() {
        var ledger = new MixedStagingLedger();
        assertTrue(ledger.sources(List.of("stone"), List.of(a)).isEmpty());
    }
}
