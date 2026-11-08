package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackingSupplySearchTest {
    @Test
    void stopsAfterThreeSpeculativeCandidatesButStillAllowsUsefulStock() {
        var search = new PackingSupplySearch();
        for (int i = 0; i < 3; i++) search.record(candidate(i, 3));
        for (int i = 3; i < 453; i++) assertFalse(search.canTry(candidate(i, 3)));
        assertTrue(search.canTry(candidate(454, 1)));
        assertTrue(search.canTry(candidate(455, 0)));
    }

    @Test
    void totalBudgetIncludesPromisingButStaleCandidates() {
        var search = new PackingSupplySearch();
        for (int i = 0; i < 8; i++) search.record(candidate(i, 0));
        assertFalse(search.canTry(candidate(9, 1)));
        assertThrows(IllegalStateException.class, () -> search.record(candidate(9, 1)));
    }

    @Test
    void restartKeepsBudgetsAndPhysicalIdentitiesWhileNewBatchResetsThem() {
        var search = new PackingSupplySearch();
        for (int i = 0; i < 3; i++) search.record(candidate(i, 2));
        var restored = new PackingSupplySearch();
        restored.restore(search.snapshot());
        assertEquals(search.snapshot(), restored.snapshot());
        assertFalse(restored.canTry(candidate(0, 0)));
        assertFalse(restored.canTry(candidate(4, 3)));
        restored.reset();
        assertTrue(restored.canTry(candidate(4, 3)));
        restored.restore(null);
        assertTrue(restored.tried().isEmpty());
    }

    private static PackingSourceSelector.Candidate candidate(int x, int rank) {
        return new PackingSourceSelector.Candidate(new ContainerEntry(x, 64, 0, "chest", false,
                Map.of("shulker_box", 1), 1, List.of(), 1), rank);
    }
}
