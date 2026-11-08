package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSourceReturnPolicyTest {
    @Test
    void unknownOriginStillRequiresRegisteredScannedImportSeparateFromExport() {
        int[] target = {3, 64, 4};
        int[] export = {1, 64, 2};
        var importChest = new ContainerEntry(3, 64, 4, "minecraft:chest",
                false, Map.of(), 0, List.of(), 100L);
        var exportChest = new ContainerEntry(1, 64, 2, "minecraft:chest",
                false, Map.of(), 0, List.of(), 100L);
        var scanned = List.of(importChest, exportChest);
        var registered = List.of(target, export);
        assertTrue(KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(
                scanned, registered, Set.of(), new int[]{0, 60, 0},
                new int[]{5, 70, 5}, null, export, target));
        assertFalse(KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(
                scanned, registered, Set.of(), new int[]{0, 60, 0},
                new int[]{5, 70, 5}, null, export, export));
        assertArrayEquals(target, KitSourceReturnPolicy.capacityImports(
                scanned, registered, Set.of(), new int[]{0, 60, 0},
                new int[]{5, 70, 5}, null, export).getFirst());
    }
}
