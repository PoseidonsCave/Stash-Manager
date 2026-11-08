package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIdentifierPhysicalSlotsTest {
    @Test
    void preservesSparseInnerSlotNumbers() {
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) slots.add(null);
        slots.set(0, new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 3));
        slots.set(24, new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(), 15));
        DataComponents components = new DataComponents(new HashMap<>());
        putContainer(components, slots);
        var box = new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(),
                1, components);
        var physical = ItemIdentifier.readShulkerPhysicalSlotStacks(box);
        assertEquals(2, physical.size());
        assertEquals(3, physical.get(0).getAmount());
        assertEquals(15, physical.get(24).getAmount());
    }

    @Test
    void rejectsMalformedContainerLength() {
        DataComponents components = new DataComponents(new HashMap<>());
        putContainer(components,
                List.of(new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 1)));
        var box = new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(),
                1, components);
        assertTrue(ItemIdentifier.readShulkerPhysicalSlotStacks(box).isEmpty());
    }

    @Test
    void acceptsOptionalContainerEntriesUsedByNewerProtocols() {
        var slots = new ArrayList<Optional<ItemStack>>();
        for (int i = 0; i < 27; i++) slots.add(Optional.empty());
        slots.set(24, Optional.of(new ItemStack(
                ItemRegistry.REGISTRY.get("end_crystal").id(), 15)));
        DataComponents components = new DataComponents(new HashMap<>());
        putContainer(components, slots);
        var box = new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(),
                1, components);
        assertEquals(15, ItemIdentifier.readShulkerPhysicalSlotStacks(box)
                .get(24).getAmount());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putContainer(DataComponents components, List<?> slots) {
        // MCProtocolLib changed this component from List<ItemStack> to
        // List<Optional<ItemStack>>. Exercise the reader against both shapes.
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
    }
}
