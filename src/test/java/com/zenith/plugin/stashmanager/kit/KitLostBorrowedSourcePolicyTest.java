package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitLostBorrowedSourcePolicyTest {
    private static final Map<String, Integer> APPLES = Map.of("enchanted_golden_apple", 1728);

    @Test
    void identicalPhysicalBoxesMayShareOneUnambiguousSourceInventory() {
        var left = chest(-100, 70, 200, -100, 70, 200, 12);
        var right = chest(-99, 70, 200, -100, 70, 200, 44);
        var origin = KitLostSourcePolicy.borrowedOrigin(List.of(left, right),
                "black_shulker_box", APPLES);
        assertNotNull(origin);
        assertEquals(left.inventoryKey(), origin.inventoryKey());
    }

    @Test
    void matchingBoxesInDifferentInventoriesAreAmbiguous() {
        var first = chest(-100, 70, 200, -100, 70, 200, 12);
        var second = chest(-90, 70, 200, -90, 70, 200, 12);
        assertNull(KitLostSourcePolicy.borrowedOrigin(List.of(first, second),
                "black_shulker_box", APPLES));
    }

    @Test
    void lossRequiresTheExactBlockedRecoveryBoundary() {
        var origin = chest(-100, 70, 200, -100, 70, 200, 12);
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128), Map.of(), 10, 8,
                "enchanted_golden_apple", 64, 37, new int[]{0, 70, 0}, null,
                new int[]{-101, 70, 200}, "black_shulker_box", APPLES,
                "kit_cargo_retrieval_incomplete", 1L, null, 0L, List.of(), -1);
        assertNull(KitLostSourcePolicy.borrowedBlocker(checkpoint, 1728,
                true, true, true, 0,
                "journaled_box_absent_or_already_held:borrowed_source", origin));
        assertEquals("declared last-known units do not match the checkpoint",
                KitLostSourcePolicy.borrowedBlocker(checkpoint, 1727,
                        true, true, true, 0,
                        "journaled_box_absent_or_already_held:borrowed_source", origin));
        assertEquals("borrowed source is still present or inventory identity is unknown",
                KitLostSourcePolicy.borrowedBlocker(checkpoint, 1728,
                        true, true, true, 1,
                        "journaled_box_absent_or_already_held:borrowed_source", origin));
    }

    private static ContainerEntry chest(int x, int y, int z,
                                        int inventoryX, int inventoryY, int inventoryZ,
                                        int slot) {
        return new ContainerEntry(x, y, z, "chest", true,
                Map.of("black_shulker_box", 1, "enchanted_golden_apple", 1728), 1,
                List.of(new ContainerEntry.ShulkerDetail(slot, "black", APPLES)), 1L,
                null, null, inventoryX, inventoryY, inventoryZ, true, "X");
    }
}
