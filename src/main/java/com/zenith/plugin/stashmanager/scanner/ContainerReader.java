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

    public ContainerReader(ContainerIndex index) {
        this.index = index;
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
            Map.of(),
            0,
            java.util.List.of(),
            System.currentTimeMillis(),
            null,
            hopperFacing,
            inventoryIdentity.inventoryX(),
            inventoryIdentity.inventoryY(),
            inventoryIdentity.inventoryZ(),
            inventoryIdentity.identityKnown(),
            doubleChestAxis
        );

        index.put(snapshotContents(open, containerEntry, System.currentTimeMillis()));

        return true;
    }

    /** Refresh contents only; retain the scanned footprint, lane geometry, and label. */
    public static ContainerEntry snapshotContents(Container open, ContainerEntry location, long timestamp) {
        int slots = open.getSize() - 36;
        Map<String, Integer> items = new LinkedHashMap<>();
        var shulkers = new java.util.ArrayList<ContainerEntry.ShulkerDetail>();
        ShulkerIntrospector introspector = new ShulkerIntrospector();
        int shulkerCount = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = open.getItemStack(slot);
            if (stack == null || stack.getId() == 0 || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            items.merge(itemId, stack.getAmount(), Integer::sum);
            if (!itemId.contains("shulker_box")) continue;
            shulkerCount += stack.getAmount();
            var detail = introspector.introspect(stack);
            if (detail == null) continue;
            shulkers.add(new ContainerEntry.ShulkerDetail(slot, detail.color(), detail.items()));
            detail.items().forEach((item, count) -> items.merge(item, count, Integer::sum));
        }
        return location.withContents(items, shulkerCount, shulkers, timestamp);
    }

    private String doubleChestAxis(DoubleChestIdentity.Resolution identity) {
        if (!identity.identityKnown() || identity.blocks().size() != 2) return null;
        int[] first = identity.blocks().get(0);
        int[] second = identity.blocks().get(1);
        if (first[0] != second[0]) return "X";
        if (first[2] != second[2]) return "Z";
        return null;
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
