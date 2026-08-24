package com.zenith.plugin.stashmanager.scanner;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.zenith.Globals.CACHE;

// Reads open container contents and records items into the index.
public class ContainerReader {

    private final ContainerIndex index;
    private final ShulkerIntrospector shulkerIntrospector;

    public ContainerReader(ContainerIndex index) {
        this.index = index;
        this.shulkerIntrospector = new ShulkerIntrospector();
    }

    // Read the currently open container and record its contents to the index.
    // Returns true if the container was read successfully.
    public boolean readOpenContainer(RegionScanner.ContainerLocation location, boolean isDouble) {
        Container open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        if (open == null) {
            return false;
        }

        int size = open.getSize();
        // The open window includes the player's own 36 inventory+hotbar slots appended
        // after the container's own slots — exclude them or every scan attributes
        // whatever the bot happens to be carrying to every container it opens.
        int containerSlotCount = Math.max(0, size - 36);
        Map<String, Integer> items = new LinkedHashMap<>();
        int shulkerCount = 0;
        var shulkerDetails = new java.util.ArrayList<ContainerEntry.ShulkerDetail>();

        for (int slot = 0; slot < containerSlotCount; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getId() == 0 || stack.getAmount() <= 0) continue;

            String itemId = getItemId(stack);
            items.merge(itemId, stack.getAmount(), Integer::sum);

            // Check if this item is a shulker box
            if (isShulkerBox(itemId)) {
                shulkerCount++;
                var shulkerDetail = shulkerIntrospector.introspect(stack);
                if (shulkerDetail != null) {
                    shulkerDetails.add(new ContainerEntry.ShulkerDetail(
                            slot, shulkerDetail.color(), shulkerDetail.items()));
                    for (var entry : shulkerDetail.items().entrySet()) {
                        // Also add shulker contents to the container-level items
                        items.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                }
            }
        }

        boolean actualDouble = (location.type() == BlockEntityType.CHEST
                || location.type() == BlockEntityType.TRAPPED_CHEST)
                && containerSlotCount == 54;
        String blockType = blockEntityTypeToId(location.type());
        String hopperFacing = location.hopperFacing() != null ? location.hopperFacing().name() : null;
        var inventoryIdentity = DoubleChestIdentity.resolve(
                location.x(), location.y(), location.z(), actualDouble);
        String doubleChestAxis = doubleChestAxis(inventoryIdentity);

        ContainerEntry containerEntry = new ContainerEntry(
            location.x(), location.y(), location.z(),
            blockType,
            actualDouble,
            items,
            shulkerCount,
            shulkerDetails,
            System.currentTimeMillis(),
            null,
            hopperFacing,
            inventoryIdentity.inventoryX(),
            inventoryIdentity.inventoryY(),
            inventoryIdentity.inventoryZ(),
            inventoryIdentity.identityKnown(),
            doubleChestAxis
        );

        index.put(containerEntry);

        return true;
    }

    private String doubleChestAxis(DoubleChestIdentity.Resolution identity) {
        if (!identity.identityKnown() || identity.blocks().size() != 2) return null;
        int[] first = identity.blocks().get(0);
        int[] second = identity.blocks().get(1);
        if (first[0] != second[0]) return "X";
        if (first[2] != second[2]) return "Z";
        return null;
    }

    private String getItemId(ItemStack stack) {
        return ItemIdentifier.getItemId(stack);
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
