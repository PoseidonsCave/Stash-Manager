package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceCursorStackInEmptySlotTest {
    @Test
    void permitsBundleOnCursorOverAnEmptySlot() {
        Container open = new Container(33, 63);
        ItemStack bundle = new ItemStack(ItemRegistry.REGISTRY.get("pink_bundle").id(), 1);

        assertTrue(PlaceCursorStackInEmptySlot.canPlace(33, 29, open, bundle));
        assertNotNull(PlaceOneFromCursor.exactClickPacket(33, 29,
                ClickItemAction.LEFT_CLICK, bundle, Container.EMPTY_STACK, 42));
    }

    @Test
    void rejectsChangedWindowOrOccupiedDestination() {
        Container open = new Container(33, 63);
        ItemStack bundle = new ItemStack(ItemRegistry.REGISTRY.get("pink_bundle").id(), 1);
        open.setItemStack(29, new ItemStack(ItemRegistry.REGISTRY.get("golden_carrot").id(), 2));

        assertFalse(PlaceCursorStackInEmptySlot.canPlace(33, 29, open, bundle));
        assertFalse(PlaceCursorStackInEmptySlot.canPlace(34, 30, open, bundle));
        assertFalse(PlaceCursorStackInEmptySlot.canPlace(33, 63, open, bundle));
        assertFalse(PlaceCursorStackInEmptySlot.canPlace(33, 30, open, null));
    }

    @Test
    void acceptsANullCachedEmptySlot() {
        Container open = new Container(33, 63);
        open.setItemStack(29, null);
        ItemStack cursor = new ItemStack(ItemRegistry.REGISTRY.get("diamond_leggings").id(), 1);

        assertTrue(PlaceCursorStackInEmptySlot.canPlace(33, 29, open, cursor));
    }
}
