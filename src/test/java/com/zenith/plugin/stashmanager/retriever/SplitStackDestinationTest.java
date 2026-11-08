package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SplitStackDestinationTest {
    @Test
    void skipsSlotFilledByPreviousSplitUsingLiveWindowState() {
        int chestSlots = 27;
        int firstWindowSlot = chestSlots + 26 - 9;

        assertEquals(26, StashRetriever.firstEmptyTransferRawSlot(chestSlots,
                raw -> raw == 26 || raw == 27,
                slot -> slot == firstWindowSlot || slot == firstWindowSlot + 1));

        assertEquals(27, StashRetriever.firstEmptyTransferRawSlot(chestSlots,
                raw -> raw == 26 || raw == 27,
                slot -> slot == firstWindowSlot + 1));
    }
}
