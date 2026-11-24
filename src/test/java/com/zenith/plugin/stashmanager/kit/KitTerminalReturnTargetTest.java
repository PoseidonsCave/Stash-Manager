package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitTerminalReturnTargetTest {
    private static final int[] TARGET = {10, 64, 20};

    private static ContainerIndex index(Set<Integer> registeredHalves,
                                        boolean hopperBelow, boolean indexPartner) {
        ContainerIndex index = new ContainerIndex() {
            @Override public boolean isImportChest(int x, int y, int z) {
                return y == 64 && z == 20 && registeredHalves.contains(x);
            }
        };
        for (int x : indexPartner ? List.of(10, 11) : List.of(10)) {
            index.put(new ContainerEntry(x, 64, 20, "minecraft:chest", true,
                    Map.of(), 0, List.of(), 1L, null, null,
                    10, 64, 20, true, "X"));
        }
        if (hopperBelow) index.put(new ContainerEntry(10, 63, 20,
                "minecraft:hopper", false, Map.of(), 0, List.of(), 1L));
        return index;
    }

    private static ContainerIndex index(Set<Integer> registeredHalves, boolean hopperBelow) {
        return index(registeredHalves, hopperBelow, true);
    }

    @Test void refusesHopperDrainedAssignedLane() {
        assertFalse(KitTerminalReturnRunner.indexedTargetAllowed(
                index(Set.of(), true), TARGET, TARGET));
    }

    @Test void acceptsOnlyTheFullyRegisteredStableImport() {
        assertTrue(KitTerminalReturnRunner.indexedTargetAllowed(
                index(Set.of(10, 11), false), TARGET, null));
        // Production scans persist one canonical double-chest row, not both blocks.
        assertTrue(KitTerminalReturnRunner.indexedTargetAllowed(
                index(Set.of(10, 11), false, false), TARGET, null));
        assertFalse(KitTerminalReturnRunner.indexedTargetAllowed(
                index(Set.of(10), false), TARGET, null));
        assertFalse(KitTerminalReturnRunner.indexedTargetAllowed(
                index(Set.of(), false), TARGET, null));
    }

    @Test void partialScanTrustIsLimitedToFreshTargetWithOneFailure() {
        ContainerEntry fresh = index(Set.of(10, 11), false).get(10, 64, 20);
        var partial = new DatabaseManager.ScanSummary(100, 99, 1, "partial", 1L);
        assertTrue(KitTerminalReturnRunner.freshTargetInSingleFailureScan(partial, fresh));
        assertFalse(KitTerminalReturnRunner.freshTargetInSingleFailureScan(
                new DatabaseManager.ScanSummary(100, 98, 2, "partial", 1L), fresh));
        assertFalse(KitTerminalReturnRunner.freshTargetInSingleFailureScan(
                new DatabaseManager.ScanSummary(100, 99, 1, "partial", 2L), fresh));
        assertFalse(KitTerminalReturnRunner.freshTargetInSingleFailureScan(partial, null));
    }
}
