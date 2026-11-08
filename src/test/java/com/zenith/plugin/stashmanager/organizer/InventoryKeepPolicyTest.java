package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryKeepPolicyTest {

    @Test
    void basePickaxeRuleProtectsEnchantedVariants() {
        var policy = InventoryKeepPolicy.from(Map.of("diamond_pickaxe", 1));

        assertEquals(java.util.Set.of(9), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "diamond_pickaxe[fortune]", 1),
                new InventoryKeepPolicy.SlotStack(10, "diamond_pickaxe[silk_touch]", 1))));
    }

    @Test
    void unlimitedRuleProtectsEveryOriginalMatchingStack() {
        Map<String, Integer> rules = new java.util.LinkedHashMap<>();
        rules.put("totem_of_undying", null);
        var policy = InventoryKeepPolicy.from(rules);

        assertEquals(java.util.Set.of(11, 40), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(11, "totem_of_undying", 1),
                new InventoryKeepPolicy.SlotStack(12, "obsidian", 64),
                new InventoryKeepPolicy.SlotStack(40, "totem_of_undying", 1))));
    }

    @Test
    void cappedRuleProtectsWholeStackThatCrossesLimit() {
        var policy = InventoryKeepPolicy.from(Map.of("enchanted_golden_apple", 70));

        assertEquals(java.util.Set.of(9, 10), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "enchanted_golden_apple", 64),
                new InventoryKeepPolicy.SlotStack(10, "enchanted_golden_apple", 16),
                new InventoryKeepPolicy.SlotStack(11, "enchanted_golden_apple", 64))));
    }

    @Test
    void duplicateVariantRulesUseTheSafestBaseLimit() {
        Map<String, Integer> rules = new java.util.LinkedHashMap<>();
        rules.put("diamond_pickaxe[fortune]", 1);
        rules.put("diamond_pickaxe[silk_touch]", 2);
        var policy = InventoryKeepPolicy.from(rules);

        assertEquals(java.util.Set.of(9, 10), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "diamond_pickaxe[fortune]", 1),
                new InventoryKeepPolicy.SlotStack(10, "diamond_pickaxe[silk_touch]", 1),
                new InventoryKeepPolicy.SlotStack(11, "diamond_pickaxe", 1))));
    }

    @Test
    void protectionFollowsKeptCargoInsteadOfAReusedSlot() {
        var policy = InventoryKeepPolicy.from(Map.of("firework_rocket", 64));

        assertEquals(java.util.Set.of(9), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "firework_rocket", 64))));
        assertEquals(java.util.Set.of(10), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "light_gray_shulker_box", 1),
                new InventoryKeepPolicy.SlotStack(10, "firework_rocket", 64))));
    }

    @Test
    void activeCargoIsNotAbsorbedByTheKeepQuota() {
        var policy = InventoryKeepPolicy.from(Map.of("enchanted_golden_apple", 128));
        var inventory = List.of(
                new InventoryKeepPolicy.SlotStack(9, "enchanted_golden_apple", 40),
                new InventoryKeepPolicy.SlotStack(10, "enchanted_golden_apple", 64),
                new InventoryKeepPolicy.SlotStack(11, "enchanted_golden_apple", 64));

        assertEquals(java.util.Set.of(10, 11),
                policy.protectedSlots(inventory, "enchanted_golden_apple", 40));
    }

    @Test
    void foodMatchingBoxedTaskCargoRemainsKeptWhenNoLooseCargoIsOwned() {
        var policy = InventoryKeepPolicy.from(Map.of("enchanted_golden_apple", 111));
        var inventory = List.of(
                new InventoryKeepPolicy.SlotStack(9, "enchanted_golden_apple", 64),
                new InventoryKeepPolicy.SlotStack(10, "enchanted_golden_apple", 47),
                new InventoryKeepPolicy.SlotStack(39, "black_shulker_box", 1));

        assertEquals(java.util.Set.of(9, 10),
                policy.protectedSlots(inventory, "enchanted_golden_apple", 0));
    }

    @Test
    void namedStackFillsTheCoreItemKeepQuotaFirst() {
        var policy = InventoryKeepPolicy.from(Map.of("minecraft:diamond_sword", 1));

        assertEquals(java.util.Set.of(40), policy.protectedSlots(List.of(
                new InventoryKeepPolicy.SlotStack(9, "diamond_sword", 1, false),
                new InventoryKeepPolicy.SlotStack(40, "diamond_sword", 1, true))));
    }

    @Test
    void activeCargoNeverSubstitutesANamedKeptStackOfTheSameCoreItem() {
        var policy = InventoryKeepPolicy.from(Map.of("diamond_sword", 1));
        var inventory = new java.util.ArrayList<InventoryKeepPolicy.SlotStack>();
        inventory.add(new InventoryKeepPolicy.SlotStack(9, "diamond_sword", 1, true));
        for (int slot = 10; slot <= 28; slot++) {
            inventory.add(new InventoryKeepPolicy.SlotStack(slot, "diamond_sword", 1, false));
        }

        assertEquals(java.util.Set.of(9),
                policy.protectedSlots(inventory, "diamond_sword", 19));
    }

    @Test
    void unlimitedKeepRuleStillReleasesConfirmedTaskCargo() {
        Map<String, Integer> rules = new java.util.LinkedHashMap<>();
        rules.put("totem_of_undying", null);
        var policy = InventoryKeepPolicy.from(rules);
        var inventory = List.of(
                new InventoryKeepPolicy.SlotStack(9, "totem_of_undying", 1),
                new InventoryKeepPolicy.SlotStack(10, "totem_of_undying", 1));

        assertEquals(java.util.Set.of(10),
                policy.protectedSlots(inventory, "totem_of_undying", 1));
    }

    @Test
    void legacyFiniteCheckpointInfersOnlyInventoryExcessAsCargo() {
        var policy = InventoryKeepPolicy.from(Map.of("enchanted_golden_apple", 128));
        var inventory = List.of(
                new InventoryKeepPolicy.SlotStack(9, "enchanted_golden_apple", 40),
                new InventoryKeepPolicy.SlotStack(10, "enchanted_golden_apple", 64),
                new InventoryKeepPolicy.SlotStack(11, "enchanted_golden_apple", 64));

        assertEquals(40, policy.inferredMovableUnits("enchanted_golden_apple", inventory));
    }

    @Test
    void mergedCargoCanLeaveNoSafeWholeStackToMove() {
        var policy = InventoryKeepPolicy.from(Map.of("firework_rocket", 93));
        var inventory = List.of(
                new InventoryKeepPolicy.SlotStack(9, "firework_rocket", 64),
                new InventoryKeepPolicy.SlotStack(10, "firework_rocket", 42));

        assertEquals(java.util.Set.of(9, 10),
                policy.protectedSlots(inventory, "firework_rocket", 13));
    }
}
