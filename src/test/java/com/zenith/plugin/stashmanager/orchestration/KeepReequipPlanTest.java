package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeepReequipPlanTest {
    @Test
    void countsEquippedGearAndOnlyRefillsDeficits() {
        var plan = KeepReequipPlan.from(
                Map.of("diamond_helmet", 1, "golden_carrot", 64),
                Map.of("diamond_helmet", 1, "golden_carrot", 12));
        assertEquals(Map.of("golden_carrot", 52), plan.requested());
        assertTrue(plan.equipment().contains("diamond_helmet"));
    }

    @Test
    void unboundedRulesNeverRequestWholeStash() {
        var kept = new java.util.LinkedHashMap<String, Integer>();
        kept.put("netherite_chestplate", null);
        kept.put("diamond_pickaxe", null);
        kept.put("shulker_box", null);
        var plan = KeepReequipPlan.from(kept, Map.of("diamond_pickaxe", 1));
        assertEquals(Map.of("netherite_chestplate", 1), plan.requested());
        assertTrue(plan.skippedUnlimited().contains("shulker_box"));
        assertEquals(6, KeepReequipPlan.equipmentSlot("netherite_chestplate"));
        assertEquals(45, KeepReequipPlan.equipmentSlot("totem_of_undying"));
    }
}
