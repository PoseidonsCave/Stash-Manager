package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiRegionImportRoutePolicyTest {
    @Test
    void pinsRegisteredImportsFromMultipleIntakesButNotOtherContainers() {
        var storage = region("storage", 100, DatabaseManager.RegionRole.STORAGE);
        var first = region("farm", 0, DatabaseManager.RegionRole.INTAKE);
        var second = region("trading", 30, DatabaseManager.RegionRole.INTAKE);
        var empty = region("future", 60, DatabaseManager.RegionRole.INTAKE);
        var scanOnly = region("private", 200, DatabaseManager.RegionRole.SCAN_ONLY);
        var selection = MultiRegionImportRoutePolicy.select(storage,
                List.of(storage, first, second, empty, scanOnly),
                List.of(pos(1), pos(31), pos(201)),
                List.of(chest(1), chest(2), chest(31), chest(32), chest(201)),
                List.of(), List.of());

        assertTrue(selection.ready());
        assertEquals(2, selection.importBlocks());
        assertEquals(List.of("farm", "trading"),
                selection.intakes().stream().map(intake -> intake.name()).toList());
        assertEquals(1, selection.intakes().get(0).importBlocks().size());
        assertEquals(1, selection.intakes().get(1).importBlocks().size());
    }

    @Test
    void rejectsImportClaimedByOverlappingIntakeRegions() {
        var storage = region("storage", 100, DatabaseManager.RegionRole.STORAGE);
        var first = region("farm", 0, DatabaseManager.RegionRole.INTAKE);
        var overlapping = region("farm-two", 5, DatabaseManager.RegionRole.INTAKE);
        var selection = MultiRegionImportRoutePolicy.select(storage,
                List.of(storage, first, overlapping), List.of(pos(6)),
                List.of(chest(6)), List.of(), List.of());
        assertFalse(selection.ready());
        assertTrue(selection.blocker().contains("multiple intake"));
    }

    @Test
    void rejectsHalfRegisteredDoubleChest() {
        var storage = region("storage", 100, DatabaseManager.RegionRole.STORAGE);
        var intake = region("farm", 0, DatabaseManager.RegionRole.INTAKE);
        var selection = MultiRegionImportRoutePolicy.select(storage,
                List.of(storage, intake), List.of(pos(1)),
                List.of(doubleChest(1), doubleChest(2)), List.of(), List.of());
        assertFalse(selection.ready());
        assertTrue(selection.blocker().contains("both halves"));
    }

    @Test
    void acceptsFullyRegisteredDoubleChestAsOneIntakeInventory() {
        var storage = region("storage", 100, DatabaseManager.RegionRole.STORAGE);
        var intake = region("farm", 0, DatabaseManager.RegionRole.INTAKE);
        var selection = MultiRegionImportRoutePolicy.select(storage,
                List.of(storage, intake), List.of(pos(1), pos(2)),
                List.of(doubleChest(1), doubleChest(2)), List.of(), List.of());
        assertTrue(selection.ready());
        assertEquals(2, selection.importBlocks());
        assertEquals(1, selection.intakes().size());
    }

    private static DatabaseManager.SavedRegion region(String name, int x,
                                                      DatabaseManager.RegionRole role) {
        return new DatabaseManager.SavedRegion(name, pos(x), pos(x + 10), role);
    }

    private static int[] pos(int x) { return new int[]{x, 64, 0}; }

    private static ContainerEntry chest(int x) {
        return new ContainerEntry(x, 64, 0, "minecraft:chest", false,
                Map.of(), 0, List.of(), 1L);
    }

    private static ContainerEntry doubleChest(int x) {
        return new ContainerEntry(x, 64, 0, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1L, null, null,
                1, 64, 0, true, "X");
    }
}
