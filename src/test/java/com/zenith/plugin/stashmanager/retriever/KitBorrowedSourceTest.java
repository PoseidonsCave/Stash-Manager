package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitBorrowedSourceTest {
    @Test
    void kitSkipsMixedSignedBookSourceForOtherCargo() {
        assertFalse(StashRetriever.kitSourceWithoutSignedBooks(
                Map.of("firework_rocket", 960, "written_book[book=one]", 1),
                Map.of("firework_rocket", 576)));
        assertTrue(StashRetriever.kitSourceWithoutSignedBooks(
                Map.of("firework_rocket", 960), Map.of("firework_rocket", 576)));
        assertTrue(StashRetriever.kitSourceWithoutSignedBooks(
                Map.of("written_book[book=one]", 1),
                Map.of("written_book[book=one]", 1)));
    }
    @Test
    void recordsOnlyTheAmountTakenFromEachSourceBox() {
        assertEquals(Map.of("respawn_anchor", 16, "obsidian", 64),
                StashRetriever.borrowedContentsAfterTake(
                        Map.of("respawn_anchor", 32, "obsidian", 64),
                        "respawn_anchor", 32, 16));
        assertEquals(Map.of("obsidian", 64),
                StashRetriever.borrowedContentsAfterTake(
                        Map.of("respawn_anchor", 16, "obsidian", 64),
                        "respawn_anchor", 16, 0));
        assertNull(StashRetriever.borrowedContentsAfterTake(
                Map.of("respawn_anchor", 8), "respawn_anchor", 32, 0));
    }
}
