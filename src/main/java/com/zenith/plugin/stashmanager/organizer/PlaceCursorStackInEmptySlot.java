package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import static com.zenith.Globals.CACHE;

/** Places a held stack into an empty slot without Zenith's bundle-specific click path. */
final class PlaceCursorStackInEmptySlot implements InventoryAction {
    private final int containerId;
    private final int slotId;

    PlaceCursorStackInEmptySlot(int containerId, int slotId) {
        this.containerId = containerId;
        this.slotId = slotId;
    }

    @Override
    public int containerId() {
        return containerId;
    }

    @Override
    public MinecraftPacket packet() {
        Container open = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        if (!canPlace(containerId, slotId, open, cursor)) return null;
        return PlaceOneFromCursor.exactClickPacket(containerId, slotId,
                ClickItemAction.LEFT_CLICK, cursor, Container.EMPTY_STACK);
    }

    static boolean canPlace(int containerId, int slotId, Container open, ItemStack cursor) {
        return open != null && open.getContainerId() == containerId
                && slotId >= 0 && slotId < open.getSize()
                && open.getItemStack(slotId) == Container.EMPTY_STACK
                && cursor != null && cursor.getAmount() > 0;
    }
}
