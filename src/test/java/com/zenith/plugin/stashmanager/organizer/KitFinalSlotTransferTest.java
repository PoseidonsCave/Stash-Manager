package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitFinalSlotTransferTest {
    @Test void directPlacementChoosesTheFirstEmptyBoxSlot() {
        assertEquals(26, KitPacker.firstEmptyBoxSlot(27, slot -> slot < 26));
        assertEquals(-1, KitPacker.firstEmptyBoxSlot(27, slot -> true));
        assertEquals(25, KitPacker.firstEmptyBoxSlot(27, slot -> slot < 25));
    }
}
