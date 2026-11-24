package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StorageClassPolicyTest {
    @Test
    void structuralVariantsRemainSeparateLaneClasses() {
        String blocks = StorageClassPolicy.exact("minecraft:bricks");
        String stairs = StorageClassPolicy.exact("brick_stairs");
        String slabs = StorageClassPolicy.exact("brick_slab");
        String trapdoors = StorageClassPolicy.exact("oak_trapdoor");

        assertEquals(4, java.util.Set.of(blocks, stairs, slabs, trapdoors).size());
    }

    @Test
    void equipmentAndEnchantmentVariantsRemainSeparate() {
        assertNotEquals(StorageClassPolicy.exact("diamond_helmet"),
                StorageClassPolicy.exact("diamond_sword"));
        assertNotEquals(StorageClassPolicy.exact("diamond_pickaxe[fortune]"),
                StorageClassPolicy.exact("diamond_pickaxe[silk_touch]"));
    }

    @Test
    void enchantedAndSignedBooksKeepDistinctStorageClasses() {
        assertNotEquals(StorageClassPolicy.route("enchanted_book[mending=1]"),
                StorageClassPolicy.route("enchanted_book[protection=4]"));
        assertNotEquals(StorageClassPolicy.route("written_book[book=abc123]"),
                StorageClassPolicy.route("written_book[book=def456]"));
        assertNotEquals(StorageClassPolicy.route("written_book[book=abc123]"),
                StorageClassPolicy.route("enchanted_book[mending=1]"));
    }

    @Test
    void mixedKitsExpandIntoExactDemandClasses() {
        Map<String, Long> demand = MixedShulkerPlaybook.aggregateDemand(List.of(
                Map.of("diamond_helmet", 1, "diamond_sword", 1, "end_crystal", 64),
                Map.of("end_crystal", 32, "obsidian", 64)));

        assertEquals(Map.of(
                "diamond_helmet", 1L,
                "diamond_sword", 1L,
                "end_crystal", 96L,
                "obsidian", 64L), demand);
    }

    @Test
    void stagingEstimateUsesRealStackSizes() {
        assertEquals(3, MixedShulkerPlaybook.minimumStagingSlots(Map.of(
                "end_crystal", 65,
                "diamond_sword", 1)));
    }
}
