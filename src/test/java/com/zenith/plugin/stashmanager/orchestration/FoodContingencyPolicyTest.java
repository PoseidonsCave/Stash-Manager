package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FoodContingencyPolicyTest {

    @Test
    void finiteSafeFoodRulesBecomeMissingQuantityRequests() {
        var plan = FoodContingencyPolicy.plan(
                Map.of("minecraft:golden_carrot", 64, "bread", 32),
                Map.of("golden_carrot", 12, "bread", 32));

        assertEquals(Map.of("golden_carrot", 52), plan.requested());
        assertEquals(2, plan.configuredFoodTypes());
        assertEquals(44, plan.currentFoodUnits());
        assertEquals(96, plan.targetFoodUnits());
    }

    @Test
    void namedVariantUsesItsCoreFoodRule() {
        var plan = FoodContingencyPolicy.plan(
                Map.of("golden_carrot[custom_name]", 16),
                Map.of("golden_carrot", 3));

        assertEquals(Map.of("golden_carrot", 13), plan.requested());
    }

    @Test
    void unlimitedRuleProtectsExistingFoodButDoesNotInventARefillAmount() {
        Map<String, Integer> rules = new LinkedHashMap<>();
        rules.put("cooked_beef", null);

        var empty = FoodContingencyPolicy.plan(rules, Map.of());
        assertTrue(empty.configured());
        assertFalse(empty.hasFood());
        assertFalse(empty.needsRefill());

        var stocked = FoodContingencyPolicy.plan(rules, Map.of("cooked_beef", 7));
        assertTrue(stocked.hasFood());
        assertFalse(stocked.needsRefill());
    }

    @Test
    void unsafeFoodAndNonFoodDoNotQualifyForStarvationRecovery() {
        var plan = FoodContingencyPolicy.plan(
                Map.of("rotten_flesh", 64, "stone", 64),
                Map.of("rotten_flesh", 64, "stone", 64));

        assertFalse(plan.configured());
        assertEquals(0, plan.currentFoodUnits());
        assertTrue(plan.requested().isEmpty());
    }

    @Test
    void autoEatRejectedFoodDoesNotCountAsUsableRecoveryStock() {
        var plan = FoodContingencyPolicy.plan(
                Map.of("golden_carrot", 64, "bread", 32),
                Map.of("golden_carrot", 12),
                "bread"::equals);

        assertEquals(1, plan.configuredFoodTypes());
        assertFalse(plan.hasFood());
        assertEquals(Map.of("bread", 32), plan.requested());
    }
}
