package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShulkerPlacementPriorityTest {
    @Test
    void earlierSameColorHotbarBoxWinsOverSelectedKitShell() {
        assertEquals(37, ShulkerPlacementPriority.firstMatchingSlot(
                Set.of(37, 40)::contains));
    }

    @Test
    void offhandWinsAndMustBeTreatedAsASelectionConflict() {
        assertEquals(45, ShulkerPlacementPriority.firstMatchingSlot(
                Set.of(37, 40, 45)::contains));
    }

    @Test
    void mainInventoryIsConsideredOnlyAfterHotbar() {
        assertEquals(40, ShulkerPlacementPriority.firstMatchingSlot(
                Set.of(12, 40)::contains));
    }
}
