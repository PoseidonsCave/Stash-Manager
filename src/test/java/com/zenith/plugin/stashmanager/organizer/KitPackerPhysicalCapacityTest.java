package com.zenith.plugin.stashmanager.organizer;

import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.kit.KitVariantExchangePlan;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class KitPackerPhysicalCapacityTest {
    @Test
    void packedEnderChestRepairPassesThePackerBoundary() {
        var a = new DataComponents(new HashMap<>());
        a.put(DataComponentTypes.CUSTOM_NAME, Component.text("A"));
        var b = new DataComponents(new HashMap<>());
        b.put(DataComponentTypes.CUSTOM_NAME, Component.text("B"));
        int item = ItemRegistry.REGISTRY.get("ender_chest").id();
        var plan = new KitVariantExchangePlan.Plan(17,
                new ItemStack(item, 6, a), 18, new ItemStack(item, 10, b),
                0, new ItemStack(item, 6, b), 6, 64, 18);
        assertTrue(KitPacker.packedStackRepairBoundary(
                Map.of("ender_chest", 16), 19, 1, plan));
        assertDoesNotThrow(() -> new KitPacker.VariantExchange(plan, 44, true));
        assertFalse(KitPacker.packedStackRepairBoundary(
                Map.of("ender_chest", 16), 20, 1, plan));
        assertFalse(KitPacker.packedStackRepairBoundary(
                Map.of("ender_chest", 15), 19, 1, plan));
        assertFalse(KitPacker.packedStackRepairBoundary(
                Map.of("ender_chest", 16), 19, 2, plan));
    }

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

    @Test
    void twoExactReplacementsRestoreOneSlotForChestplate() {
        var a = new DataComponents(new HashMap<>());
        a.put(DataComponentTypes.CUSTOM_NAME, Component.text("A"));
        var b = new DataComponents(new HashMap<>());
        b.put(DataComponentTypes.CUSTOM_NAME, Component.text("B"));
        var c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.CUSTOM_NAME, Component.text("C"));
        var d = new DataComponents(new HashMap<>());
        d.put(DataComponentTypes.CUSTOM_NAME, Component.text("D"));
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < 22; i++) stacks.add(new ItemStack(2 + i, 1, null));
        stacks.add(new ItemStack(1, 64, a));
        stacks.add(new ItemStack(1, 18, b));
        stacks.add(new ItemStack(1, 64, c));
        stacks.add(new ItemStack(1, 60, d));
        stacks.add(new ItemStack(1, 50, c));
        stacks.add(new ItemStack(50, 1, null));
        assertEquals(28, KitPacker.physicalRequiredSlots(stacks,
                id -> id == 1 ? 64 : 1));
        stacks.set(23, new ItemStack(1, 18, c));
        assertEquals(28, KitPacker.physicalRequiredSlots(stacks,
                id -> id == 1 ? 64 : 1));
        stacks.set(25, new ItemStack(1, 60, c));
        assertEquals(27, KitPacker.physicalRequiredSlots(stacks,
                id -> id == 1 ? 64 : 1));
    }
}
