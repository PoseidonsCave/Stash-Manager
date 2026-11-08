package com.zenith.plugin.stashmanager.organizer.lane;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IndexedStorageGeometryTest {
    @Test
    void exactObservationWinsInDenseStorage() {
        ContainerEntry exact = chest(10, 64, 20, false, 10, 64, 20, true, 20);
        ContainerEntry west = chest(9, 64, 20, true, 9, 64, 20, true, 30);
        ContainerEntry north = chest(10, 64, 19, true, 10, 64, 19, true, 40);

        var geometry = new IndexedStorageGeometry(List.of(exact, west, north));

        assertSame(exact, geometry.findAt(10, 64, 20, -1, 0));
    }

    @Test
    void canonicalDoubleRepresentsSkippedPositiveHalf() {
        ContainerEntry canonical = chest(9, 64, 20, true, 9, 64, 20, true, 30);
        var geometry = new IndexedStorageGeometry(List.of(canonical));

        assertSame(canonical, geometry.findAt(10, 64, 20, -1, 0));
    }

    @Test
    void preferredLaneAxisBreaksDenseFallbackTie() {
        ContainerEntry west = chest(9, 64, 20, true, 9, 64, 20, true, 30);
        ContainerEntry north = chest(10, 64, 19, true, 10, 64, 19, true, 40);
        var geometry = new IndexedStorageGeometry(List.of(west, north));

        assertSame(west, geometry.findAt(10, 64, 20, -1, 0));
        assertSame(north, geometry.findAt(10, 64, 20, 0, 1));
    }

    @Test
    void adjacentSingleDoesNotClaimMissingPhysicalBlock() {
        ContainerEntry adjacentSingle = chest(9, 64, 20, false, 9, 64, 20, true, 30);
        var geometry = new IndexedStorageGeometry(List.of(adjacentSingle));

        assertNull(geometry.findAt(10, 64, 20, -1, 0));
    }

    @Test
    void persistedAxisResolvesTheExactDoubleChestFootprintInDenseRows() {
        ContainerEntry west = chest(9, 64, 20, true, 9, 64, 20, true, 30, "X");
        ContainerEntry north = chest(10, 64, 19, true, 10, 64, 19, true, 40, "X");
        var geometry = new IndexedStorageGeometry(List.of(west, north));

        assertSame(west, geometry.findAt(10, 64, 20, 0, 1));
        assertSame(north, geometry.findAt(11, 64, 19, -1, 0));
    }

    @Test
    void persistedZAxisResolvesSouthernPartner() {
        ContainerEntry north = chest(10, 64, 19, true, 10, 64, 19, true, 40, "Z");
        var geometry = new IndexedStorageGeometry(List.of(north));

        assertSame(north, geometry.findAt(10, 64, 20, -1, 0));
    }

    private static ContainerEntry chest(
            int x, int y, int z, boolean isDouble,
            int inventoryX, int inventoryY, int inventoryZ,
            boolean identityKnown, long timestamp) {
        return new ContainerEntry(x, y, z, "minecraft:chest", isDouble,
                Map.of(), 0, List.of(), timestamp, null, null,
                inventoryX, inventoryY, inventoryZ, identityKnown);
    }

    private static ContainerEntry chest(
            int x, int y, int z, boolean isDouble,
            int inventoryX, int inventoryY, int inventoryZ,
            boolean identityKnown, long timestamp, String axis) {
        return new ContainerEntry(x, y, z, "minecraft:chest", isDouble,
                Map.of(), 0, List.of(), timestamp, null, null,
                inventoryX, inventoryY, inventoryZ, identityKnown, axis);
    }
}
