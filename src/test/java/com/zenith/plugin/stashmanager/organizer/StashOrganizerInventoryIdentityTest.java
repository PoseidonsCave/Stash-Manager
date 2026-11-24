package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashOrganizerInventoryIdentityTest {
    @Test
    void keepsOnlyFreshestObservationOfPersistedDoubleChestInventory() {
        ContainerEntry left = chest(10, 64, 20, true, 10, 64, 20, true, 100);
        ContainerEntry right = chest(11, 64, 20, true, 10, 64, 20, true, 200);

        List<ContainerEntry> result = StashOrganizer.deduplicateDoubleChestInventories(
                List.of(left, right));

        assertEquals(1, result.size());
        assertEquals(11, result.get(0).x());
    }

    @Test
    void doesNotGuessThatLegacyDoubleChestRowsShareAnInventory() {
        ContainerEntry left = chest(10, 64, 20, true, 10, 64, 20, false, 100);
        ContainerEntry right = chest(11, 64, 20, true, 11, 64, 20, false, 200);

        assertEquals(2, StashOrganizer.deduplicateDoubleChestInventories(
                List.of(left, right)).size());
    }

    @Test
    void countsDoubleChestAsFiftyFourSlotsEvenWhenOnlyOneHalfIsLaneGeometry() {
        ContainerEntry entry = chest(10, 64, 20, true, 10, 64, 20, true, 100);
        StashOrganizer.Column column = new StashOrganizer.Column(
                3, List.of(new int[]{10, 64, 20}));

        var lane = StashOrganizer.toStorageLane(column, Map.of(entry.posKey(), entry));

        assertEquals(54, lane.shulkerSlots());
    }

    @Test
    void legacyConstructorMarksSinglesKnownAndDoublesUnknown() {
        ContainerEntry single = new ContainerEntry(1, 2, 3, "minecraft:chest", false,
                Map.of(), 0, List.of(), 1L);
        ContainerEntry legacyDouble = new ContainerEntry(1, 2, 3, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1L);

        assertTrue(single.inventoryIdentityKnown());
        assertFalse(legacyDouble.inventoryIdentityKnown());
        assertTrue(single.inventoryFootprintKnown());
        assertFalse(legacyDouble.inventoryFootprintKnown());
    }

    private static ContainerEntry chest(
            int x, int y, int z,
            boolean isDouble,
            int inventoryX, int inventoryY, int inventoryZ,
            boolean identityKnown,
            long timestamp) {
        return new ContainerEntry(x, y, z, "minecraft:chest", isDouble,
                Map.of(), 0, List.of(), timestamp, null, null,
                inventoryX, inventoryY, inventoryZ, identityKnown);
    }
}
