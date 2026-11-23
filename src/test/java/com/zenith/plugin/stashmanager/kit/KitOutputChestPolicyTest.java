package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitOutputChestPolicyTest {
    @Test
    void rejectsHopperUnderEitherHalfOfIndexedDoubleChest() {
        var output = chest(-10642, 130, 251488, true, "X");
        var leftDrain = hopper(-10642, 129, 251488);
        var rightDrain = hopper(-10641, 129, 251488);
        assertTrue(KitOutputChestPolicy.hasIndexedHopperBelow(output,
                List.of(output, leftDrain)));
        assertTrue(KitOutputChestPolicy.hasIndexedHopperBelow(output,
                List.of(output, rightDrain)));
        var targetedRightHalf = new ContainerEntry(-10641, 130, 251488,
                "minecraft:chest", true, Map.of(), 0, List.of(), 1L,
                null, null, -10642, 130, 251488, true, "X");
        assertTrue(KitOutputChestPolicy.hasIndexedHopperBelow(targetedRightHalf,
                List.of(rightDrain)));
        assertFalse(KitOutputChestPolicy.hasIndexedHopperBelow(output,
                List.of(output, hopper(-10643, 129, 251488))));
    }

    @Test
    void checksTheSecondHalfAlongTheZAxisAndSinglesOnlyOnce() {
        var doubleChest = chest(5, 70, 8, true, "Z");
        var singleChest = chest(5, 70, 8, false, null);
        var secondHalfDrain = hopper(5, 69, 9);
        assertTrue(KitOutputChestPolicy.hasIndexedHopperBelow(doubleChest,
                List.of(secondHalfDrain)));
        assertFalse(KitOutputChestPolicy.hasIndexedHopperBelow(singleChest,
                List.of(secondHalfDrain)));
    }

    private static ContainerEntry chest(int x, int y, int z, boolean doubled,
                                        String axis) {
        return new ContainerEntry(x, y, z, "minecraft:chest", doubled,
                Map.of(), 0, List.of(), 1L, null, null,
                x, y, z, true, axis);
    }

    private static ContainerEntry hopper(int x, int y, int z) {
        return new ContainerEntry(x, y, z, "minecraft:hopper", false,
                Map.of(), 0, List.of(), 1L);
    }
}
