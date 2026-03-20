package com.zenith.plugin.stashmanager.scanner;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.ItemStack;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

// Extracts contents from shulker box ItemStacks via NBT data components.
public class ShulkerIntrospector {

    private final Logger logger;

    public ShulkerIntrospector(Logger logger) {
        this.logger = logger;
    }

    // Extract shulker contents. Returns null if NBT is absent or unreadable.
    public ContainerEntry.ShulkerDetail introspect(ItemStack shulkerStack) {
        if (shulkerStack == null) return null;

        try {
            var nbt = shulkerStack.getDataComponents();
            if (nbt == null) return null;

            String color = extractShulkerColor(shulkerStack);
            Map<String, Integer> items = new LinkedHashMap<>();

            // Read shulker inventory from container data component
            var containerComponent = nbt.get(org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType.CONTAINER);
            if (containerComponent != null) {
                var itemList = (java.util.List<ItemStack>) containerComponent;
                for (ItemStack innerStack : itemList) {
                    if (innerStack == null || innerStack.getId() == 0 || innerStack.getAmount() <= 0) continue;
                    String itemId = "minecraft:item_" + innerStack.getId();
                    items.merge(itemId, innerStack.getAmount(), Integer::sum);
                }
            }

            return new ContainerEntry.ShulkerDetail(color, items);
        } catch (Exception e) {
            logger.debug("Failed to introspect shulker NBT: {}", e.getMessage());
            return null;
        }
    }

    private String extractShulkerColor(ItemStack stack) {
        // TODO: resolve color from item registry ID
        return "unknown";
    }
}
