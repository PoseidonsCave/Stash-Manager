package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCraftingPlanTest {
    @Test
    void optInCraftsOnlyAnchorGapAndReservesGlowstoneForTheKit() {
        Map<String, Integer> replenishment = Map.of(
                "respawn_anchor", 32, "glowstone", 128);
        Map<String, Long> stock = Map.of(
                "respawn_anchor", 32L, "crying_obsidian", 8444L, "glowstone", 96495L);

        var disabled = KitCraftingPlan.assess(replenishment, 10, stock, false);
        assertFalse(disabled.feasible());
        assertEquals(Map.of("respawn_anchor", 288L), disabled.shortages());
        assertTrue(disabled.toCraft().isEmpty());

        var enabled = KitCraftingPlan.assess(replenishment, 10, stock, true);
        assertTrue(enabled.feasible());
        assertEquals(Map.of("respawn_anchor", 288L), enabled.toCraft());
        assertEquals(1728L, enabled.totalDemand().get("crying_obsidian"));
        assertEquals(2144L, enabled.totalDemand().get("glowstone"));
        assertEquals(32L, enabled.totalDemand().get("respawn_anchor"));
    }

    @Test
    void cannotCraftWhenReservedIngredientsWouldRunShort() {
        var plan = KitCraftingPlan.assess(
                Map.of("respawn_anchor", 32, "glowstone", 128), 10,
                Map.of("respawn_anchor", 32L, "crying_obsidian", 1700L,
                        "glowstone", 2000L), true);

        assertFalse(plan.feasible());
        assertEquals(28L, plan.shortages().get("crying_obsidian"));
        assertEquals(144L, plan.shortages().get("glowstone"));
    }

    @Test
    void unknownOutputsAreNeverAssumedCraftable() {
        var plan = KitCraftingPlan.assess(Map.of("enchanted_golden_apple", 64), 2,
                Map.of(), true);
        assertEquals(Map.of("enchanted_golden_apple", 128L), plan.shortages());
        assertTrue(plan.toCraft().isEmpty());
    }
}
