package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingLanePlanTest {
    @Test
    void tenCopyWorkingStockKeepsBulkOutAndCountsNonstackableSlots() {
        Map<String, Integer> recipe = new LinkedHashMap<>();
        recipe.put("tnt", 256);
        recipe.put("diamond_sword", 2);
        var plan = KitWorkingLanePlan.assess(recipe, 10,
                Map.of("tnt", 3_000L, "diamond_sword", 25L));

        assertEquals(6, plan.kitSlots());
        assertEquals(2, plan.items().get("tnt").shulkerSlots());
        assertEquals(1, plan.items().get("diamond_sword").shulkerSlots());
        assertEquals(3, plan.shulkerSlots());
        assertEquals(1, plan.doubleChests());
        assertTrue(plan.indexedStockSuffices());
    }

    @Test
    void exactVariantsCannotBorrowEachOthersIndexedStock() {
        var plan = KitWorkingLanePlan.assess(
                Map.of("diamond_sword[sharpness=5]", 1), 10,
                Map.of("diamond_sword", 100L));
        assertEquals(10, plan.items().get("diamond_sword[sharpness=5]").shortage());
        assertFalse(plan.indexedStockSuffices());
    }

    @Test
    void rainbowWoolAtTwentyFiveCopiesNeedsOneBoxPerColor() {
        Map<String, Integer> recipe = new LinkedHashMap<>();
        for (String color : "black blue brown cyan gray green light_blue light_gray lime magenta orange pink purple red white yellow".split(" ")) {
            recipe.put(color + "_wool", 64);
        }
        var plan = KitWorkingLanePlan.assess(recipe, 25, Map.of());
        assertEquals(16, plan.kitSlots());
        assertEquals(16, plan.shulkerSlots());
        assertEquals(1, plan.doubleChests());
        assertEquals(1_600, plan.items().get("red_wool").units());
    }

    @Test
    void proposedRedstoneRecipeFitsAndExposesTwentyFiveCopyShortages() {
        Map<String, Integer> recipe = new LinkedHashMap<>();
        for (String id : "redstone redstone_block redstone_torch repeater comparator observer piston sticky_piston slime_block hopper dispenser dropper lever rail powered_rail tripwire_hook".split(" ")) {
            recipe.put(id, 64);
        }
        for (String id : "note_block daylight_detector honey_block".split(" ")) recipe.put(id, 16);
        recipe.put("target", 8);
        recipe.put("stone_button", 8);
        Map<String, Long> limitingStock = Map.of(
                "note_block", 260L, "daylight_detector", 1_023L,
                "honey_block", 199L, "target", 128L, "stone_button", 121L);
        var plan = KitWorkingLanePlan.assess(recipe, 25, limitingStock);
        assertEquals(21, plan.kitSlots());
        assertEquals(21, plan.shulkerSlots());
        assertEquals(1, plan.doubleChests());
        assertTrue(plan.stackSizesResolved());
        assertEquals(201, plan.items().get("honey_block").shortage());
        assertEquals(140, plan.items().get("note_block").shortage());
        assertEquals(79, plan.items().get("stone_button").shortage());
        assertEquals(72, plan.items().get("target").shortage());
    }

    @Test
    void proposedGriefingRecipeFitsWithNonstackableWorkingStock() {
        Map<String, Integer> recipe = new LinkedHashMap<>();
        recipe.put("tnt", 256);
        recipe.put("end_crystal", 128);
        recipe.put("obsidian", 128);
        for (String id : "glowstone fire_charge soul_sand firework_rocket".split(" ")) recipe.put(id, 64);
        recipe.put("respawn_anchor", 16);
        recipe.put("ender_pearl", 16);
        recipe.put("wither_skeleton_skull", 3);
        recipe.put("lava_bucket", 2);
        recipe.put("red_bed", 2);
        recipe.put("totem_of_undying", 2);
        recipe.put("water_bucket", 1);
        recipe.put("flint_and_steel", 1);
        var plan = KitWorkingLanePlan.assess(recipe, 10, Map.of("red_bed", 28L));
        assertEquals(23, plan.kitSlots());
        assertEquals(16, plan.shulkerSlots());
        assertEquals(1, plan.doubleChests());
        assertTrue(plan.stackSizesResolved());
        assertEquals(0, plan.items().get("red_bed").shortage());
    }
}
