package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BorrowedShulkerPlacementTest {
    @Test
    void mirrorsZenithPlacementSearchOrder() {
        assertEquals(45, StashRetriever.firstPlacementItemSlot(slot -> slot == 45 || slot == 36));
        assertEquals(38, StashRetriever.firstPlacementItemSlot(slot -> slot == 38 || slot == 42 || slot == 12));
        assertEquals(12, StashRetriever.firstPlacementItemSlot(slot -> slot == 12 || slot == 15));
        assertEquals(-1, StashRetriever.firstPlacementItemSlot(slot -> false));
    }
}
