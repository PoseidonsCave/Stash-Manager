package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitPackerPickupTest {
    @Test
    void hotbarSwapCannotCreditTheMatchingSourceAsTheRecoveredShell() {
        // Source moved from raw 36 to raw 44 during shell selection. The packed
        // shell is subsequently picked up into raw 36.
        Set<Integer> afterSelection = Set.of(44);
        assertEquals(36, KitPacker.uniqueNewMatchingSlot(afterSelection,
                Set.of(36, 44)));
        assertEquals(-1, KitPacker.uniqueNewMatchingSlot(afterSelection,
                Set.of(36)));
        assertEquals(-1, KitPacker.uniqueNewMatchingSlot(afterSelection,
                Set.of(36, 40, 44)));
    }
}
