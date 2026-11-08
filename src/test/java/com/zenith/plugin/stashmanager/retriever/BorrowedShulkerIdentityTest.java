package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BorrowedShulkerIdentityTest {
    @Test
    void selectsTheBorrowedBoxRatherThanAnEarlierContentMatch() {
        assertEquals(30, StashRetriever.uniqueBorrowedSourceSlot(raw -> raw == 30));
    }

    @Test
    void refusesAnIndistinguishableDuplicate() {
        assertEquals(-2, StashRetriever.uniqueBorrowedSourceSlot(raw -> raw == 14 || raw == 30));
    }

    @Test
    void waitsIfTheBorrowedBoxHasNotArrived() {
        assertEquals(-1, StashRetriever.uniqueBorrowedSourceSlot(raw -> false));
    }
}
