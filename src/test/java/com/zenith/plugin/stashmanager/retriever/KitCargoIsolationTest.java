package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCargoIsolationTest {
    @Test
    void kitCargoAlwaysUsesAnEmptySlotEvenForAWholeStack() {
        assertTrue(StashRetriever.exactSlotTransferRequired(
                "kit_fulfillment:cpvp:0:end_crystal", "end_crystal", 64, 128));
        assertTrue(StashRetriever.exactSlotTransferRequired(
                "kit_fulfillment:cpvp:0:enchanted_golden_apple",
                "enchanted_golden_apple", 16, 16));
        assertFalse(StashRetriever.exactSlotTransferRequired(
                "kit_fulfillment:cpvp:0:empty_shell",
                StashRetriever.EMPTY_SHELL_REQUEST, 1, 1));
        assertFalse(StashRetriever.exactSlotTransferRequired(
                "regular_retrieval", "end_crystal", 64, 128));
    }

    @Test
    void kitCannotBorrowPreexistingInventoryShulkers() {
        assertFalse(StashRetriever.mayBorrowInventoryShulker(
                "kit_fulfillment:cpvp:0:end_crystal"));
        assertTrue(StashRetriever.mayBorrowInventoryShulker("regular_retrieval"));
        assertTrue(StashRetriever.mayBorrowInventoryShulker(null));
    }

    @Test
    void splitDepositSkipsAContainerSlotThatOnlyLooksEmptyInTheOldSnapshot() {
        Set<Integer> liveEmpty = Set.of(23, 24);
        Set<Integer> oldOpenEmpty = Set.of(27 + 22 - 9, 27 + 23 - 9,
                27 + 24 - 9);
        assertTrue(oldOpenEmpty.contains(40));
        assertEquals(23, StashRetriever.firstEmptyTransferRawSlot(27,
                liveEmpty::contains, oldOpenEmpty::contains));
    }
}
