package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.plugin.stashmanager.index.IndexExporter;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Filterable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentifierTest {

    @Test
    void customNameDoesNotChangeTheCoreItemId() {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("Do Not Move"));
        var sword = new ItemStack(
                ItemRegistry.REGISTRY.get("diamond_sword").id(), 1, components);

        assertEquals("diamond_sword", ItemIdentifier.getItemId(sword));
        assertEquals("diamond_sword", ItemIdentifier.baseItemId("minecraft:DIAMOND_SWORD"));
        assertTrue(ItemIdentifier.hasCustomPresentation(sword));
    }

    @Test
    void armorAndWeaponsUseSortedSemanticEnchantmentsInsteadOfNames() {
        var enchantments = new Int2IntOpenHashMap();
        enchantments.put(EnchantmentRegistry.SHARPNESS.get().id(), 5);
        enchantments.put(EnchantmentRegistry.MENDING.get().id(), 1);
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("Bob's Definitely Unique Sword"));
        components.put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.builder()
                .enchantments(enchantments).build());
        var sword = new ItemStack(ItemRegistry.REGISTRY.get("diamond_sword").id(), 1, components);

        assertEquals("diamond_sword[mending=1,sharpness=5]", ItemIdentifier.getItemId(sword));
        assertEquals("Diamond Sword · Mending I, Sharpness V",
                IndexExporter.toReadableName(ItemIdentifier.getItemId(sword)));
    }

    @Test
    void pickaxesRetainAllEnchantmentsAndLevelsForProfileMatching() {
        var enchantments = new Int2IntOpenHashMap();
        enchantments.put(EnchantmentRegistry.FORTUNE.get().id(), 3);
        enchantments.put(EnchantmentRegistry.EFFICIENCY.get().id(), 5);
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.builder()
                .enchantments(enchantments).build());
        var pickaxe = new ItemStack(ItemRegistry.REGISTRY.get("diamond_pickaxe").id(), 1, components);

        assertEquals("diamond_pickaxe[efficiency=5,fortune=3]", ItemIdentifier.getItemId(pickaxe));
        assertEquals("diamond_sword[mending=1,sharpness=5]",
                ItemIdentifier.normalizeItemId("minecraft:Diamond Sword[SHARPNESS=5, MENDING=1]"));
    }

    @Test
    void storedEnchantmentsSurviveDirectAndNestedShulkerScanning() {
        var enchantments = new Int2IntOpenHashMap();
        enchantments.put(EnchantmentRegistry.PROTECTION.get().id(), 4);
        enchantments.put(EnchantmentRegistry.MENDING.get().id(), 1);
        var bookComponents = new DataComponents(new HashMap<>());
        bookComponents.put(DataComponentTypes.STORED_ENCHANTMENTS,
                ItemEnchantments.builder().enchantments(enchantments).build());
        var enchantedBook = new ItemStack(ItemRegistry.REGISTRY.get("enchanted_book").id(), 1, bookComponents);
        String identity = "enchanted_book[mending=1,protection=4]";
        assertEquals(identity, ItemIdentifier.getItemId(enchantedBook));

        var shulkerComponents = new DataComponents(new HashMap<>());
        putContainerItems(shulkerComponents, List.of(Optional.of(enchantedBook)));
        var shulker = new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1, shulkerComponents);
        assertEquals(1, ItemIdentifier.readShulkerContents(shulker).get(identity));
        assertEquals(1, ItemIdentifier.readShulkerStackSlots(shulker).get(identity));
    }

    @Test
    void signedBooksGetStableContentIdentitiesWithoutLeakingPages() {
        var first = signedBook("Field Notes", "Scout", "North entrance");
        var same = signedBook("Field Notes", "Scout", "North entrance");
        var differentPage = signedBook("Field Notes", "Scout", "South entrance");
        var differentAuthor = signedBook("Field Notes", "Other", "North entrance");
        String firstId = ItemIdentifier.getItemId(first);

        assertTrue(firstId.matches("written_book\\[book=[0-9a-f]{64}\\]"));
        assertEquals(firstId, ItemIdentifier.getItemId(same));
        assertNotEquals(firstId, ItemIdentifier.getItemId(differentPage));
        assertNotEquals(firstId, ItemIdentifier.getItemId(differentAuthor));
        assertFalse(firstId.contains("North entrance"));
        assertEquals(firstId, ItemIdentifier.normalizeItemId(firstId));

        var shulkerComponents = new DataComponents(new HashMap<>());
        putContainerItems(shulkerComponents, List.of(first, differentPage));
        var shulker = new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1, shulkerComponents);
        assertEquals(2, ItemIdentifier.readShulkerContents(shulker).size());
    }

    @Test
    void manySignedBooksRemainDistinctAndBoundedForDatabaseKeys() {
        var identities = new HashSet<String>();
        for (int i = 0; i < 500; i++) {
            String identity = ItemIdentifier.getItemId(signedBook("Field Notes", "Scout", "Page " + i));
            assertTrue(identity.length() < 128);
            assertTrue(identities.add(identity));
        }
        assertEquals(500, identities.size());
    }

    private static ItemStack signedBook(String title, String author, String page) {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.WRITTEN_BOOK_CONTENT,
                WrittenBookContent.builder()
                        .title(new Filterable<>(title, null))
                        .author(author)
                        .generation(0)
                        .pages(List.of(new Filterable<>(Component.text(page), null)))
                        .resolved(true)
                        .build());
        return new ItemStack(ItemRegistry.REGISTRY.get("written_book").id(), 1, components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putContainerItems(DataComponents components, List<?> contents) {
        // MCProtocolLib 1.21.4 uses stacks; newer targets wrap each slot in Optional.
        components.put((DataComponentType) DataComponentTypes.CONTAINER, contents);
    }
}
