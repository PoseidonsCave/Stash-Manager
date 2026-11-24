package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StashOrganizerLaneGeometryTest {
    @Test
    void detectsWestboundStaircaseWhenHoppersTouchSkippedDoubleChestHalves() {
        List<ContainerEntry> geometry = List.of(
                chest(-30, 66, 69),
                hopper(-29, 65, 69, "WEST"),
                chest(-31, 65, 69),
                hopper(-31, 64, 69, "WEST"),
                chest(-33, 64, 69));

        var columns = StashOrganizer.detectStaircaseColumns(geometry);

        assertEquals(1, columns.size());
        assertEquals(3, columns.get(0).chests().size());
        assertEquals(-30, columns.get(0).top()[0]);
        assertEquals(66, columns.get(0).top()[1]);
        assertEquals(-33, columns.get(0).bottom()[0]);
    }

    @Test
    void detectsSouthboundInputThroughCanonicalNorthernHalf() {
        List<ContainerEntry> geometry = List.of(
                chestZ(10, 65, 19),
                hopper(10, 64, 20, "SOUTH"),
                chest(10, 64, 21));

        var columns = StashOrganizer.detectStaircaseColumns(geometry);

        assertEquals(1, columns.size());
        assertEquals(2, columns.get(0).chests().size());
    }

    @Test
    void joinsNarrowStaircaseStepsThroughTheirSharedInventory() {
        List<ContainerEntry> geometry = List.of(
                singleChest(10, 66, 20),
                hopper(10, 65, 20, "EAST"),
                singleChest(11, 65, 20),
                hopper(11, 64, 20, "EAST"),
                singleChest(12, 64, 20));

        var columns = StashOrganizer.detectStaircaseColumns(geometry);

        assertEquals(1, columns.size());
        assertEquals(3, columns.get(0).chests().size());
        assertArrayEquals(new int[]{10, 66, 20}, columns.get(0).top());
        assertArrayEquals(new int[]{12, 64, 20}, columns.get(0).bottom());
    }

    @Test
    void disconnectedHopperStepsRemainSeparateLanes() {
        List<ContainerEntry> geometry = List.of(
                singleChest(10, 66, 20),
                hopper(10, 65, 20, "EAST"),
                singleChest(11, 65, 20),
                singleChest(40, 66, 20),
                hopper(40, 65, 20, "EAST"),
                singleChest(41, 65, 20));

        var columns = StashOrganizer.detectStaircaseColumns(geometry);

        assertEquals(2, columns.size());
        assertEquals(2, columns.get(0).chests().size());
        assertEquals(2, columns.get(1).chests().size());
    }

    @Test
    void detectsStackedBanksAlongsideHopperLanes() {
        List<ContainerEntry> geometry = List.of(
                singleChest(10, 66, 20),
                hopper(10, 65, 20, "EAST"),
                singleChest(11, 65, 20),
                chest(20, 66, 30),
                chest(20, 65, 30),
                chest(22, 67, 30),
                chest(22, 66, 30),
                chest(22, 65, 30),
                chest(30, 65, 30));

        var columns = StashOrganizer.detectStorageColumns(geometry);

        assertEquals(3, columns.size(), "one hopper lane and two vertical banks");
        assertEquals(2, columns.get(0).chests().size());
        assertEquals(2, columns.get(1).chests().size());
        assertEquals(3, columns.get(2).chests().size());
    }

    @Test
    void verticalGapsSplitDirectAccessBanks() {
        List<ContainerEntry> geometry = List.of(
                chest(20, 68, 30),
                chest(20, 67, 30),
                chest(20, 65, 30),
                chest(20, 64, 30));

        var columns = StashOrganizer.detectStorageColumns(geometry);

        assertEquals(2, columns.size());
        assertEquals(2, columns.get(0).chests().size());
        assertEquals(2, columns.get(1).chests().size());
    }

    @Test
    void fourHighDoubleChestBanksAreIndependentVerticalLanes() {
        List<ContainerEntry> geometry = new ArrayList<>();
        for (int bank = 0; bank < 46; bank++) {
            for (int level = 0; level < 4; level++) {
                geometry.add(chest(bank * 2, 64 + level, 30));
            }
        }

        var columns = StashOrganizer.detectStorageColumns(geometry);

        assertEquals(46, columns.size());
        assertEquals(184, columns.stream().mapToInt(column -> column.chests().size()).sum());
        assertEquals(4, columns.getFirst().chests().size());
    }

    @Test
    void overlappingDetectorResultsCannotCreateDuplicateLanes() {
        List<ContainerEntry> geometry = List.of(
                singleChest(10, 66, 20),
                singleChest(11, 65, 20),
                singleChest(12, 64, 20));
        List<StashOrganizer.Column> candidates = List.of(
                new StashOrganizer.Column(1, List.of(
                        new int[]{10, 66, 20}, new int[]{11, 65, 20})),
                new StashOrganizer.Column(2, List.of(
                        new int[]{11, 65, 20}, new int[]{12, 64, 20})));

        var columns = StashOrganizer.deduplicateOverlappingColumns(candidates, geometry);

        assertEquals(1, columns.size());
        assertEquals(2, columns.get(0).chests().size());
    }

    private static ContainerEntry chest(int x, int y, int z) {
        return new ContainerEntry(x, y, z, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1L, null, null,
                x, y, z, true, "X");
    }

    private static ContainerEntry chestZ(int x, int y, int z) {
        return new ContainerEntry(x, y, z, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1L, null, null,
                x, y, z, true, "Z");
    }

    private static ContainerEntry singleChest(int x, int y, int z) {
        return new ContainerEntry(x, y, z, "minecraft:chest", false,
                Map.of(), 0, List.of(), 1L, null, null,
                x, y, z, true);
    }

    private static ContainerEntry hopper(int x, int y, int z, String facing) {
        return new ContainerEntry(x, y, z, "minecraft:hopper", false,
                Map.of(), 0, List.of(), 1L, null, facing,
                x, y, z, true);
    }
}
