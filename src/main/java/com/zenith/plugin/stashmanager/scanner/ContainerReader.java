package com.zenith.plugin.stashmanager.scanner;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.zenith.Globals.CACHE;

// Reads open container contents and records items into the index.
public class ContainerReader {

    private final Logger logger;
    private final ContainerIndex index;
    private final ShulkerIntrospector shulkerIntrospector;

    public ContainerReader(Logger logger, ContainerIndex index) {
        this.logger = logger;
        this.index = index;
        this.shulkerIntrospector = new ShulkerIntrospector(logger);
    }

    // Read the currently open container and record its contents to the index.
    // Returns true if the container was read successfully.
    public boolean readOpenContainer(RegionScanner.ContainerLocation location, boolean isDouble) {
        Container open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (open == null) {
            logger.debug("No open container to read at {}, {}, {}", location.x(), location.y(), location.z());
            return false;
        }

        int size = open.getSize();
        Map<String, Integer> items = new LinkedHashMap<>();
        int shulkerCount = 0;
        var shulkerDetails = new java.util.ArrayList<ContainerEntry.ShulkerDetail>();

        // Read each slot in the container (excluding player inventory slots)
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getId() == 0 || stack.getAmount() <= 0) continue;

            String itemId = getItemId(stack);
            items.merge(itemId, stack.getAmount(), Integer::sum);

            // Check if this item is a shulker box
            if (isShulkerBox(itemId)) {
                shulkerCount++;
                var shulkerDetail = shulkerIntrospector.introspect(stack);
                if (shulkerDetail != null) {
                    shulkerDetails.add(shulkerDetail);
                    // Also add shulker contents to the container-level items
                    for (var entry : shulkerDetail.items().entrySet()) {
                        items.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                }
            }
        }

        String blockType = blockEntityTypeToId(location.type());

        ContainerEntry containerEntry = new ContainerEntry(
            location.x(), location.y(), location.z(),
            blockType,
            isDouble,
            items,
            shulkerCount,
            shulkerDetails,
            System.currentTimeMillis()
        );

        index.put(containerEntry);

        logger.info("Indexed container at {}, {}, {}: {} unique items, {} shulkers",
            location.x(), location.y(), location.z(), items.size(), shulkerCount);

        return true;
    }

    private String getItemId(ItemStack stack) {
        // MCProtocolLib stores item IDs as integers; map to string via registry
        // The network ID can be resolved to a Minecraft item identifier
        return "minecraft:item_" + stack.getId();
    }

    private boolean isShulkerBox(String itemId) {
        return itemId.contains("shulker_box");
    }

    private String blockEntityTypeToId(BlockEntityType type) {
        return switch (type) {
            case CHEST -> "minecraft:chest";
            case TRAPPED_CHEST -> "minecraft:trapped_chest";
            case BARREL -> "minecraft:barrel";
            case SHULKER_BOX -> "minecraft:shulker_box";
            case HOPPER -> "minecraft:hopper";
            case DISPENSER -> "minecraft:dispenser";
            case DROPPER -> "minecraft:dropper";
            default -> "minecraft:unknown";
        };
    }
}
