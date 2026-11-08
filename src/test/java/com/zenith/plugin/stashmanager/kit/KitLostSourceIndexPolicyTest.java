package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitLostSourceIndexPolicyTest {
    private static final KitFulfillmentJournalStore.BorrowedBox LOST =
            new KitFulfillmentJournalStore.BorrowedBox(
                    new int[]{5, 64, 6}, "shulker_box", Map.of("target_boots", 2));

    private static ContainerEntry entry(List<ContainerEntry.ShulkerDetail> details) {
        return new ContainerEntry(5, 64, 6, "minecraft:chest", false,
                Map.of("shulker_box", details.size(), "target_boots", 3,
                        "other_boots", 23), details.size(), details, 100L);
    }

    @Test void removesOnlyTheOneLostPhysicalBox() {
        var original = entry(List.of(
                new ContainerEntry.ShulkerDetail(0, "unknown",
                        Map.of("target_boots", 3)),
                new ContainerEntry.ShulkerDetail(1, "unknown",
                        Map.of("other_boots", 23))));
        var adjusted = KitLostSourceIndexPolicy.withoutLostBox(
                original, LOST, "target_boots", 1);
        assertEquals(1, adjusted.shulkerCount());
        assertEquals(Map.of("shulker_box", 1, "other_boots", 23), adjusted.items());
        assertEquals(1, adjusted.shulkerDetails().size());
        assertEquals(1, adjusted.shulkerDetails().getFirst().slot());
        assertEquals(2, original.shulkerCount());
    }

    @Test void ambiguousOrStalePhysicalDetailsAreNotAdjusted() {
        var duplicate = entry(List.of(
                new ContainerEntry.ShulkerDetail(0, "unknown",
                        Map.of("target_boots", 3)),
                new ContainerEntry.ShulkerDetail(1, "unknown",
                        Map.of("target_boots", 3))));
        assertNull(KitLostSourceIndexPolicy.withoutLostBox(
                duplicate, LOST, "target_boots", 1));
        var stale = entry(List.of(new ContainerEntry.ShulkerDetail(0, "unknown",
                Map.of("target_boots", 2))));
        assertNull(KitLostSourceIndexPolicy.withoutLostBox(
                stale, LOST, "target_boots", 1));
    }
}
