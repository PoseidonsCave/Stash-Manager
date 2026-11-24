package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCapacityTest {
    @Test
    void countsStackableAndNonStackableItemsByPhysicalSlot() {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("stone", 25 * 64);
        kit.put("diamond_sword", 2);

        assertEquals(27, KitCapacity.requiredSlots(kit));
        assertTrue(KitCapacity.fitsOneShulker(kit));

        kit.put("diamond_sword", 3);
        assertEquals(28, KitCapacity.requiredSlots(kit));
        assertFalse(KitCapacity.fitsOneShulker(kit));
    }

    @Test
    void fitOneShulkerTrimsTheTailWithoutExceedingCapacity() {
        Map<String, Integer> kit = new LinkedHashMap<>();
        kit.put("stone", 27 * 64);
        kit.put("diamond_sword", 1);

        Map<String, Integer> fitted = KitCapacity.fitOneShulker(kit);

        assertEquals(Map.of("stone", 27 * 64), fitted);
        assertEquals(27, KitCapacity.requiredSlots(fitted));
    }

    @Test
    void combatReplenishmentAndRegearLayoutsUseEveryShulkerSlot() {
        Map<String, Integer> cpvp = new LinkedHashMap<>();
        cpvp.put("diamond_helmet", 1);
        cpvp.put("diamond_chestplate", 1);
        cpvp.put("diamond_leggings", 1);
        cpvp.put("diamond_boots", 1);
        cpvp.put("diamond_sword", 1);
        cpvp.put("diamond_axe", 1);
        cpvp.put("elytra", 1);
        cpvp.put("ender_chest", 16);
        cpvp.put("totem_of_undying", 6);
        cpvp.put("end_crystal", 256);
        cpvp.put("firework_rocket", 128);
        cpvp.put("enchanted_golden_apple", 128);
        cpvp.put("experience_bottle", 128);
        cpvp.put("obsidian", 128);
        cpvp.put("ender_pearl", 16);

        Map<String, Integer> replenishment = new LinkedHashMap<>();
        replenishment.put("enchanted_golden_apple", 128);
        replenishment.put("end_crystal", 256);
        replenishment.put("ender_pearl", 64);
        replenishment.put("experience_bottle", 128);
        replenishment.put("firework_rocket", 128);
        replenishment.put("glowstone", 128);
        replenishment.put("obsidian", 256);
        replenishment.put("respawn_anchor", 32);
        replenishment.put("totem_of_undying", 6);

        Map<String, Integer> armorRegear = new LinkedHashMap<>();
        armorRegear.put("diamond_helmet", 3);
        armorRegear.put("diamond_chestplate", 3);
        armorRegear.put("diamond_leggings", 3);
        armorRegear.put("diamond_boots", 3);
        armorRegear.put("diamond_sword", 3);
        armorRegear.put("diamond_axe", 3);
        armorRegear.put("elytra", 3);
        armorRegear.put("ender_chest", 16);
        armorRegear.put("totem_of_undying", 5);

        assertEquals(KitCapacity.MAX_SLOTS, KitCapacity.requiredSlots(cpvp));
        assertEquals(KitCapacity.MAX_SLOTS, KitCapacity.requiredSlots(replenishment));
        assertEquals(KitCapacity.MAX_SLOTS, KitCapacity.requiredSlots(armorRegear));
    }
}
