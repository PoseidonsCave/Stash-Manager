package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitPhysicalSlotBudgetTest {
    @Test
    void reservesTheRemainingAppleSlotsBeforeAnotherSourceIsBorrowed() {
        Map<String, Integer> recipe = Map.of(
                "end_crystal", 256, "enchanted_golden_apple", 128);
        Map<String, Integer> packed = Map.of("end_crystal", 256);

        assertEquals(2, KitPhysicalSlotBudget.minimumRemaining(recipe, packed));
        assertTrue(KitPhysicalSlotBudget.fits(25, recipe, packed));
        assertFalse(KitPhysicalSlotBudget.fits(26, recipe, packed));
    }

    @Test
    void usesOnlyTheMinimumExtraSlotsForAPartialStack() {
        Map<String, Integer> recipe = Map.of("stone", 64);
        Map<String, Integer> packed = Map.of("stone", 32);

        assertEquals(0, KitPhysicalSlotBudget.minimumRemaining(recipe, packed));
        assertTrue(KitPhysicalSlotBudget.fits(27, recipe, packed));
    }

    @Test void admitsOnlyExactComponentCompactionThatFreesTheNeededSlot() {
        Map<String, Integer> packed = Map.of("diamond_sword", 21, "obsidian", 128);
        Map<String, Integer> recipe = Map.of("diamond_sword", 21, "obsidian", 128,
                "end_crystal", 256);
        var physical = new ArrayList<ItemStack>();
        for (int i = 0; i < 21; i++) physical.add(stack("diamond_sword", 1));
        physical.add(stack("obsidian", 64));
        physical.add(stack("obsidian", 32));
        physical.add(stack("obsidian", 32));
        assertFalse(KitPhysicalSlotBudget.fits(physical.size(), recipe, packed));
        assertTrue(KitPhysicalSlotBudget.fitsAfterCompaction(physical.size(),
                physical, recipe, packed));

        var different = new ArrayList<>(physical);
        DataComponents named = new DataComponents(new HashMap<>());
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("different"));
        different.set(23, new ItemStack(ItemRegistry.REGISTRY.get("obsidian").id(),
                32, named));
        assertFalse(KitPhysicalSlotBudget.fitsAfterCompaction(different.size(),
                different, recipe, packed));
        assertFalse(KitPhysicalSlotBudget.fitsAfterCompaction(23,
                physical, recipe, packed));
    }

    @Test void countsPhysicalSlotsForMixedRocketVariants() {
        var physical = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) physical.add(stack("totem_of_undying", 1));
        for (int i = 0; i < 9; i++) physical.add(stack("experience_bottle", 64));
        for (int i = 0; i < 9; i++) physical.add(stack("firework_rocket", 64));
        assertEquals(27, KitPhysicalSlotBudget.requiredExactSlots(physical));

        DataComponents other = new DataComponents(new HashMap<>());
        other.put(DataComponentTypes.CUSTOM_NAME, Component.text("other rocket"));
        physical.set(18, new ItemStack(ItemRegistry.REGISTRY.get("firework_rocket").id(),
                64, other));
        assertEquals(27, KitPhysicalSlotBudget.requiredExactSlots(physical));

        physical.subList(18, physical.size()).clear();
        for (int i = 0; i < 6; i++) physical.add(stack("firework_rocket", 64));
        physical.add(stack("firework_rocket", 25));
        for (int i = 0; i < 2; i++) physical.add(new ItemStack(
                ItemRegistry.REGISTRY.get("firework_rocket").id(), 64, other));
        physical.add(new ItemStack(ItemRegistry.REGISTRY.get("firework_rocket").id(),
                39, other));
        assertEquals(28, KitPhysicalSlotBudget.requiredExactSlots(physical));

        // Exchanging 25 from an already borrowed box moves the variant totals
        // from 409/167 to 384/192 while preserving the 576-rocket recipe.
        physical.subList(18, physical.size()).clear();
        for (int i = 0; i < 6; i++) physical.add(stack("firework_rocket", 64));
        for (int i = 0; i < 3; i++) physical.add(new ItemStack(
                ItemRegistry.REGISTRY.get("firework_rocket").id(), 64, other));
        assertEquals(27, KitPhysicalSlotBudget.requiredExactSlots(physical));
    }

    private static ItemStack stack(String id, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), amount);
    }
}
