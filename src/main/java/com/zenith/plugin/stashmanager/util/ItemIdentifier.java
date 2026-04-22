package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Shared item-ID logic so scan, kit, organize, and retrieve all agree on tool variants.
public final class ItemIdentifier {

    private static final Set<String> PICKAXE_IDS = Set.of(
        "minecraft:wooden_pickaxe",
        "minecraft:stone_pickaxe",
        "minecraft:iron_pickaxe",
        "minecraft:golden_pickaxe",
        "minecraft:diamond_pickaxe",
        "minecraft:netherite_pickaxe"
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
            List<ItemStack> containerItems = shulkerStack.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
            if (containerItems == null) return contents;

            for (ItemStack innerStack : containerItems) {
                if (innerStack == null || innerStack.getId() == 0 || innerStack.getAmount() <= 0) continue;
                contents.merge(getItemId(innerStack), innerStack.getAmount(), Integer::sum);
            }
        } catch (Exception ignored) {
        }

        return contents;
    }

    private static String getPickaxeEnchantSuffix(ItemStack stack) {
        try {
            ItemEnchantments itemEnchantments = stack.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
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
