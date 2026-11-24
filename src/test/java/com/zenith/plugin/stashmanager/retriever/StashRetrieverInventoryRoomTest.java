package com.zenith.plugin.stashmanager.retriever;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StashRetrieverInventoryRoomTest {

    @Test
    void fullInventoryCanStillAcceptMatchingFoodStackHeadroom() {
        Container inventory = filledInventory();
        inventory.setItemStack(9, item("golden_carrot", 12));

        assertTrue(StashRetriever.hasPlayerInventoryRoomFor(
                inventory, item("golden_carrot", 52)));
    }

    @Test
    void fullInventoryRejectsDifferentCargoWithoutAnEmptySlot() {
        Container inventory = filledInventory();

        assertFalse(StashRetriever.hasPlayerInventoryRoomFor(
                inventory, item("golden_carrot", 1)));
    }

    @Test
    void plainRequestAcceptsNamedVariantButQualifiedRequestStaysExact() {
        Map<String, Integer> plain = Map.of("golden_carrot", 12);
        assertEquals("golden_carrot", StashRetriever.requestKeyForItem(
                plain, "golden_carrot[custom_name]"));

        Map<String, Integer> qualified = Map.of("golden_carrot[special]", 12);
        assertNull(StashRetriever.requestKeyForItem(
                qualified, "golden_carrot[custom_name]"));
    }

    @Test
    void indexedNamedStacksContributeToPlainRequestCapacity() {
        assertEquals(19, StashRetriever.amountMatchingRequest(
                Map.of("golden_carrot", 3, "golden_carrot[custom_name]", 16),
                "golden_carrot"));
        assertEquals(16, StashRetriever.amountMatchingRequest(
                Map.of("golden_carrot", 3, "golden_carrot[custom_name]", 16),
                "golden_carrot[custom_name]"));
    }

    @Test
    void foodPriorityFindsTheNearbyChestBeforeDistantLaneStock() {
        var nearby = new ContainerEntry(12, 64, 10,
                "chest", false, Map.of("golden_carrot", 832), 13, List.of(), 1);
        var farther = new ContainerEntry(12, 65, 8,
                "chest", false, Map.of("golden_carrot", 512), 8, List.of(), 1);
        assertEquals(0, StashRetriever.foodAccessBand(
                10.86, 64, 14.14, nearby));
        assertEquals(2, StashRetriever.foodAccessBand(
                10.86, 64, 14.14, farther));
    }

    @Test
    void directOnlyFoodNeverBorrowsCarriedShulker() {
        assertTrue(StashRetriever.mayBorrowInventoryShulker("stash_food_contingency"));
        assertFalse(StashRetriever.mayBorrowInventoryShulker(
                "stash_food_contingency", true));
    }

    @Test
    void directOnlyIndexExcludesNestedShulkerFood() {
        var indexed = new ContainerEntry(12, 64, 10, "chest", false,
                Map.of("enchanted_golden_apple", 175), 1,
                List.of(new ContainerEntry.ShulkerDetail(3, "black",
                        Map.of("enchanted_golden_apple", 64))), 1);

        assertEquals(111, StashRetriever.directlyStoredItems(indexed)
                .get("enchanted_golden_apple"));
    }

    private static Container filledInventory() {
        Container inventory = new Container(0, 46);
        for (int slot = 9; slot <= 44; slot++) {
            inventory.setItemStack(slot, item("stone", 64));
        }
        return inventory;
    }

    private static ItemStack item(String itemId, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(itemId).id(), amount);
    }
}
