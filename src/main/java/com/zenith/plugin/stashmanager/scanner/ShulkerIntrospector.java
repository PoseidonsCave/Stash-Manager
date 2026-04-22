package com.zenith.plugin.stashmanager.scanner;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.Map;

// Extracts contents from shulker box ItemStacks via NBT data components.
public class ShulkerIntrospector {

    public ShulkerIntrospector() {}

    // Extract shulker contents. Returns null if NBT is absent or unreadable.
    public ContainerEntry.ShulkerDetail introspect(ItemStack shulkerStack) {
        if (shulkerStack == null) return null;

        try {
            String color = extractShulkerColor(shulkerStack);
            Map<String, Integer> items = ItemIdentifier.readShulkerContents(shulkerStack);
            return new ContainerEntry.ShulkerDetail(color, items);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractShulkerColor(ItemStack stack) {
        // Resolve color from the item registry name
        ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data != null) {
            String name = data.name();
            if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
            if (name.endsWith("_shulker_box") && !name.equals("shulker_box")) {
                return name.replace("_shulker_box", "");
            }
        }
        return "unknown";
    }
}
