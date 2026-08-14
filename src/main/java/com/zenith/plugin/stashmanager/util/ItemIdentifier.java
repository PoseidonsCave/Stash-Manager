package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Shared item-ID logic so scan, kit, organize, and retrieve all agree on tool variants.
public final class ItemIdentifier {

    private static final Set<String> PICKAXE_IDS = Set.of(
        "wooden_pickaxe",
        "stone_pickaxe",
        "iron_pickaxe",
        "golden_pickaxe",
        "diamond_pickaxe",
        "netherite_pickaxe"
    );

    private ItemIdentifier() {}

    public static String getItemId(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return "";

        ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        String baseId = data != null ? data.name() : "minecraft:unknown_" + stack.getId();

        if (PICKAXE_IDS.contains(baseId)) {
            String suffix = getPickaxeEnchantSuffix(stack);
            if (suffix != null) {
                return baseId + "[" + suffix + "]";
            }
        }

        return baseId;
    }

    public static Map<String, Integer> readShulkerContents(ItemStack shulkerStack) {
        Map<String, Integer> contents = new LinkedHashMap<>();
        if (shulkerStack == null || shulkerStack.getAmount() <= 0) return contents;

        try {
            DataComponents components = shulkerStack.getDataComponents();
            if (components == null) return contents;
            Object containerValue = components.get(DataComponentTypes.CONTAINER);
            if (!(containerValue instanceof List<?> containerItems)) return contents;

            for (Object entry : containerItems) {
                Object value = entry instanceof Optional<?> optional ? optional.orElse(null) : entry;
                if (!(value instanceof ItemStack innerStack)) continue;
                if (innerStack == null || innerStack.getId() == 0 || innerStack.getAmount() <= 0) continue;
                contents.merge(getItemId(innerStack), innerStack.getAmount(), Integer::sum);
            }
        } catch (Exception ignored) {
        }

        return contents;
    }

    // Strips the enchant suffix (e.g. "diamond_pickaxe[fortune]" -> "diamond_pickaxe") for
    // content-filter comparisons — grouping/matching by primary content only needs the base
    // item type, and a stale index entry captured before enchant suffixes existed would
    // otherwise never match a fresh live read of the same physical item.
    public static String baseItemId(String itemId) {
        if (itemId == null) return null;
        int bracket = itemId.indexOf('[');
        return bracket >= 0 ? itemId.substring(0, bracket) : itemId;
    }

    private static String getPickaxeEnchantSuffix(ItemStack stack) {
        try {
            DataComponents components = stack.getDataComponents();
            if (components == null) return null;
            ItemEnchantments itemEnchantments = components.get(DataComponentTypes.ENCHANTMENTS);
            if (itemEnchantments == null) return null;

            if (itemEnchantments.getEnchantments().containsKey(EnchantmentRegistry.SILK_TOUCH.get().id())) {
                return "silk_touch";
            }
            if (itemEnchantments.getEnchantments().containsKey(EnchantmentRegistry.FORTUNE.get().id())) {
                return "fortune";
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
