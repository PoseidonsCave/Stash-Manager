package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitFulfillmentPlanTest {
    @Test
    void travelKitFillsOneBoxButCanBeFetchedInBatches() {
        Map<String, Integer> travel = new LinkedHashMap<>();
        travel.put("experience_bottle", 576);
        travel.put("firework_rocket", 576);
        travel.put("totem_of_undying", 9);

        assertEquals(27, KitCapacity.requiredSlots(travel));
        assertEquals(Map.of("experience_bottle", 256),
                KitFulfillmentPlan.nextBatch(travel, 4));
        assertEquals(4, KitCapacity.requiredSlots(KitFulfillmentPlan.nextBatch(travel, 4)));
        assertTrue(KitFulfillmentPlan.nextWholeItemBatch(travel, 8).isEmpty());
        assertEquals(Map.of("experience_bottle", 576),
                KitFulfillmentPlan.nextWholeItemBatch(travel, 9));
    }

    @Test
    void oneItemCanBeFetchedInRepeatedInventoryBoundedBatches() {
        assertEquals(Map.of("diamond_sword[sharpness=5]", 1),
                KitFulfillmentPlan.nextBatch(Map.of("diamond_sword[sharpness=5]", 1), 1));
        assertEquals(Map.of("end_crystal", 64),
                KitFulfillmentPlan.nextBatch(Map.of("end_crystal", 128), 1));
        assertEquals(Map.of("totem_of_undying", 3),
                KitFulfillmentPlan.nextBatch(Map.of("totem_of_undying", 4), 3));
        assertEquals(Map.of("totem_of_undying", 1),
                KitFulfillmentPlan.nextBatch(Map.of("totem_of_undying", 1), 3));
        assertEquals(Map.of("end_crystal", 128),
                KitFulfillmentPlan.nextBatch(Map.of("end_crystal", 128), 3));
    }

    @Test
    void fiveCopyAssessmentChecksExactStockShellsAndExportSlots() {
        Map<String, Integer> kit = Map.of("respawn_anchor", 32,
                "totem_of_undying", 9);
        var shortPlan = KitFulfillmentPlan.assess(kit, 5,
                Map.of("respawn_anchor", 159L, "totem_of_undying", 45L), 4, 4);
        assertFalse(shortPlan.feasible());
        assertEquals(1L, shortPlan.shortages().get("respawn_anchor"));
        assertEquals(1, shortPlan.missingEmptyBoxes());
        assertEquals(1, shortPlan.missingExportSlots());

        var goodPlan = KitFulfillmentPlan.assess(kit, 5,
                Map.of("respawn_anchor", 160L, "totem_of_undying", 45L), 5, 5);
        assertTrue(goodPlan.feasible());
    }

    @Test
    void enchantedGearRequestsDoNotBorrowAnotherVariant() {
        String exact = "diamond_sword[fire_aspect=2,sharpness=5]";
        String other = "diamond_sword[sharpness=5]";
        var plan = KitFulfillmentPlan.assess(Map.of(exact, 1), 5,
                Map.of(other, 100L, exact, 4L), 5, 5);
        assertFalse(plan.feasible());
        assertEquals(1L, plan.shortages().get(exact));
    }

    @Test
    void sharedDemandDoesNotSpendTheSameStockTwice() {
        var shortages = KitFulfillmentPlan.sharedShortages(
                Map.of("cpvp", Map.of("respawn_anchor", 64),
                        "replenishment", Map.of("respawn_anchor", 64)),
                Map.of("cpvp", 5, "replenishment", 5),
                Map.of("respawn_anchor", 420L));
        assertEquals(220L, shortages.get("respawn_anchor"));
    }
}
