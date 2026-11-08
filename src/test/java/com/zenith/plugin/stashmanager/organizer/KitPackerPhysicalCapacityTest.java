package com.zenith.plugin.stashmanager.organizer;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitPackerPhysicalCapacityTest {
    @Test
    void equalComponentPartialsCanShareOnePhysicalSlot() {
        assertEquals(1, KitPacker.physicalRequiredSlots(List.of(
                new ItemStack(1, 49, null), new ItemStack(1, 15, null)),
                id -> 64));
    }

    @Test
    void differentComponentPartialsCannotCreateSpaceForLastItem() {
        var named = new DataComponents(new HashMap<>());
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("named"));
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < 3; i++) stacks.add(new ItemStack(1, 64, null));
        stacks.add(new ItemStack(1, 49, null));
        stacks.add(new ItemStack(1, 15, named));
        for (int i = 0; i < 22; i++) stacks.add(new ItemStack(2 + i, 1, null));
        assertEquals(27, KitPacker.physicalRequiredSlots(stacks,
                id -> id == 1 ? 64 : 1));
        stacks.add(new ItemStack(24, 1, null));
        assertEquals(28, KitPacker.physicalRequiredSlots(stacks,
                id -> id == 1 ? 64 : 1));
    }
}
