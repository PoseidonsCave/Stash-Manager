package com.zenith.plugin.stashmanager.organizer;

import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.InventoryAction;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;

import static com.zenith.Globals.CLIENT_LOG;

/** Prevents a stale cursor/slot snapshot from escaping Zenith's inventory tick. */
final class GuardedClickItem implements InventoryAction {
    private final ClickItem delegate;
    private final int containerId;
    private final int slotId;

    GuardedClickItem(int containerId, int slotId, ClickItemAction action) {
        this.containerId = containerId;
        this.slotId = slotId;
        this.delegate = new ClickItem(containerId, slotId, action);
    }

    @Override
    public int containerId() {
        return containerId;
    }

    @Override
    public MinecraftPacket packet() {
        try {
            return delegate.packet();
        } catch (RuntimeException error) {
            CLIENT_LOG.warn("Skipped stale inventory click for container {} slot {}",
                    containerId, slotId, error);
            return null;
        }
    }
}
