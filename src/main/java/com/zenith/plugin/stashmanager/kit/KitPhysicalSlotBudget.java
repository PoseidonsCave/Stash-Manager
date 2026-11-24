package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;

/** Lower bound for the physical slots still needed by a partly packed kit. */
public final class KitPhysicalSlotBudget {
    private KitPhysicalSlotBudget() { }

    /** Count slots after ordinary same-component stacking, including loose cargo. */
    public static int requiredExactSlots(Collection<ItemStack> physicalStacks) {
        if (physicalStacks == null) return Integer.MAX_VALUE;
        Map<Map.Entry<Integer, Object>, Integer> totals = new HashMap<>();
        for (ItemStack stack : physicalStacks) {
            if (stack == null || stack.getId() <= 0 || stack.getAmount() <= 0)
                return Integer.MAX_VALUE;
            var item = ItemRegistry.REGISTRY.get(stack.getId());
            if (item == null || item.stackSize() < 1
                    || stack.getAmount() > item.stackSize()) return Integer.MAX_VALUE;
            totals.merge(new AbstractMap.SimpleImmutableEntry<>(
                    stack.getId(), stack.getDataComponents()),
                    stack.getAmount(), Integer::sum);
        }
        long slots = 0;
        for (var entry : totals.entrySet()) {
            var item = ItemRegistry.REGISTRY.get(entry.getKey().getKey());
            if (item == null || item.stackSize() < 1) return Integer.MAX_VALUE;
            slots += 1L + (entry.getValue() - 1L) / item.stackSize();
            if (slots > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) slots;
    }

    public static int minimumRemaining(Map<String, Integer> recipe,
                                       Map<String, Integer> packedContents) {
        if (recipe == null || packedContents == null) return Integer.MAX_VALUE;
        int recipeSlots = KitCapacity.requiredSlots(recipe);
        int packedSlots = KitCapacity.requiredSlots(packedContents);
        return Math.max(0, recipeSlots - packedSlots);
    }

    public static boolean fits(int occupiedPhysicalSlots,
                               Map<String, Integer> recipe,
                               Map<String, Integer> packedContents) {
        return occupiedPhysicalSlots >= 0
                && occupiedPhysicalSlots <= KitCapacity.MAX_SLOTS
                && (long) occupiedPhysicalSlots
                    + minimumRemaining(recipe, packedContents) <= KitCapacity.MAX_SLOTS;
    }

    /** Admit only slack recoverable by the packer's exact-component stack compaction. */
    public static boolean fitsAfterCompaction(int occupiedPhysicalSlots,
                                              List<ItemStack> physicalStacks,
                                              Map<String, Integer> recipe,
                                              Map<String, Integer> packedContents) {
        if (physicalStacks == null || physicalStacks.size() != occupiedPhysicalSlots
                || occupiedPhysicalSlots < 0 || occupiedPhysicalSlots > KitCapacity.MAX_SLOTS)
            return false;
        if (fits(occupiedPhysicalSlots, recipe, packedContents)) return true;
        Map<Map.Entry<Integer, Object>, Integer> totals = new HashMap<>();
        for (ItemStack stack : physicalStacks) {
            if (stack == null || stack.getId() <= 0 || stack.getAmount() <= 0) return false;
            var item = ItemRegistry.REGISTRY.get(stack.getId());
            if (item == null || item.stackSize() < 1
                    || stack.getAmount() > item.stackSize()) return false;
            totals.merge(new AbstractMap.SimpleImmutableEntry<>(
                    stack.getId(), stack.getDataComponents()),
                    stack.getAmount(), Integer::sum);
        }
        long compacted = 0;
        for (var entry : totals.entrySet()) {
            var item = ItemRegistry.REGISTRY.get(entry.getKey().getKey());
            if (item == null || item.stackSize() < 1) return false;
            compacted += 1L + (entry.getValue() - 1L) / item.stackSize();
        }
        return compacted < occupiedPhysicalSlots
                && compacted >= KitCapacity.requiredSlots(packedContents)
                && compacted + minimumRemaining(recipe, packedContents)
                    <= KitCapacity.MAX_SLOTS;
    }
}
