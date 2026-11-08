package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSourceDiagnosticsTest {
    private static final String AXE = "diamond_axe[efficiency=5,mending=1,sharpness=5]";

    @Test
    void reportsExactNestedStockWithoutTreatingItAsLooseCargo() {
        ContainerEntry nested = new ContainerEntry(10, 64, 10, "minecraft:chest", false,
                Map.of(AXE, 2), 1,
                List.of(new ContainerEntry.ShulkerDetail(3, "purple", Map.of(AXE, 2))),
                100L);
        ContainerEntry direct = new ContainerEntry(12, 64, 10, "minecraft:chest", false,
                Map.of(AXE, 1), 0, List.of(), 100L);

        var sources = KitSourceDiagnostics.find(List.of(direct, nested), AXE,
                12.5, 64, 10.5, 6);

        assertEquals(2, sources.size());
        assertEquals(nested, sources.getFirst().entry());
        assertEquals(2, sources.getFirst().nested());
        assertEquals(0, sources.getFirst().direct());
        assertEquals(1, sources.getFirst().matchingShulkers());
        assertEquals(1, sources.get(1).direct());
        assertEquals(0, sources.get(1).nested());
    }

    @Test
    void excludesDifferentItemIdentityAndHonorsLimit() {
        ContainerEntry desired = new ContainerEntry(10, 64, 10, "minecraft:chest", false,
                Map.of(AXE, 1), 0, List.of(), 100L);
        ContainerEntry other = new ContainerEntry(11, 64, 10, "minecraft:chest", false,
                Map.of("diamond_axe[efficiency=4]", 4), 0, List.of(), 100L);

        assertEquals(List.of(desired), KitSourceDiagnostics.find(
                List.of(other, desired), AXE, 0, 64, 0, 1).stream()
                .map(KitSourceDiagnostics.Source::entry).toList());
        assertTrue(KitSourceDiagnostics.find(List.of(desired), AXE, 0, 64, 0, 0).isEmpty());
    }

    @Test
    void doubleChestAliasesAppearAsOnePhysicalSource() {
        ContainerEntry first = new ContainerEntry(10, 64, 10, "minecraft:chest", true,
                Map.of(AXE, 2), 0, List.of(), 100L, null, null,
                10, 64, 10, true, "X");
        ContainerEntry second = new ContainerEntry(11, 64, 10, "minecraft:chest", true,
                Map.of(AXE, 2), 0, List.of(), 100L, null, null,
                10, 64, 10, true, "X");

        assertEquals(1, KitSourceDiagnostics.find(List.of(first, second), AXE,
                0, 64, 0, 6).size());
    }
}
