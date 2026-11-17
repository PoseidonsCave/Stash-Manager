package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingMinecraftPortIdentityTest {
    private static ItemStack redstone(int amount, String name) {
        if (name == null) return new ItemStack(ItemRegistry.REGISTRY.get("redstone").id(), amount);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("redstone").id(), amount, components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box(ItemStack first, ItemStack second) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(null);
        slots.set(0, first);
        if (second != null) slots.set(1, second);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        ItemStack direct = new ItemStack(ItemRegistry.REGISTRY.get("blue_shulker_box").id(),
                1, components);
        try {
            KitVariantFingerprint.canonicalVariant(direct);
            return direct;
        } catch (ClassCastException expectedOnNewerVersions) {
            List<Optional<ItemStack>> optionalSlots = new ArrayList<>();
            for (ItemStack slot : slots) optionalSlots.add(Optional.ofNullable(slot));
            DataComponents newer = new DataComponents(new HashMap<>());
            newer.put((DataComponentType) DataComponentTypes.CONTAINER, optionalSlots);
            return new ItemStack(ItemRegistry.REGISTRY.get("blue_shulker_box").id(), 1, newer);
        }
    }

    private static KitWorkingTransferJournal.Ticket ticket(ItemStack box, int units) {
        return new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", units,
                new int[]{1, 64, 1}, 0, new int[]{2, 64, 2}, 0,
                KitVariantFingerprint.canonicalVariant(box),
                KitWorkingTransferJournal.Phase.PREPARED, 1, null);
    }

    @Test void acceptsOneComponentExactPlainItemBox() {
        ItemStack box = box(redstone(64, null), redstone(32, null));
        assertTrue(KitWorkingMinecraftPort.boxMatches(box, ticket(box, 96)));
        assertFalse(KitWorkingMinecraftPort.boxMatches(box, ticket(box, 95)));
        assertTrue(KitWorkingMinecraftPort.boxShapeMatches(box, "redstone", 96));
        assertFalse(KitWorkingMinecraftPort.boxShapeMatches(box, "redstone", 95));
    }

    @Test void rejectsNamedOrMixedVariantContentsEvenWithMatchingAggregateId() {
        ItemStack named = box(redstone(64, "reserved"), null);
        assertFalse(KitWorkingMinecraftPort.boxMatches(named, ticket(named, 64)));
        ItemStack mixed = box(redstone(64, null), redstone(32, "reserved"));
        assertFalse(KitWorkingMinecraftPort.boxMatches(mixed, ticket(mixed, 96)));
    }
}
