package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitVariantExchangeTest {
    private static ItemStack crystal(int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(), amount);
    }

    private static ItemStack namedCrystal(int amount) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("other variant"));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(),
                amount, components);
    }

    private static ItemStack helmet(int index) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("helmet " + index));
        return new ItemStack(ItemRegistry.REGISTRY.get("diamond_helmet").id(),
                1, components);
    }

    private static Map<Integer, ItemStack> shell() {
        Map<Integer, ItemStack> slots = new HashMap<>();
        slots.put(0, namedCrystal(15));
        slots.put(1, crystal(49));
        for (int index = 2; index < 27; index++) slots.put(index, helmet(index));
        return slots;
    }

    @Test void exactFifteenCrystalOpportunityAndSourceVariant() {
        var physical = shell().entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        var opportunity = KitVariantExchangePlan.shellOpportunity(
                physical, "end_crystal", 64, 26);
        assertNotNull(opportunity);
        assertEquals(15, opportunity.amount());
        assertEquals(26, opportunity.slotsAfter());
        assertEquals(0, opportunity.outgoingSlot());
        assertEquals(1, opportunity.mergeTargetSlot());
        assertNull(opportunity.withLiveSource(3, namedCrystal(15)));
        assertNotNull(opportunity.withLiveSource(3, crystal(64)));
        assertNull(KitVariantExchangePlan.shellOpportunity(
                physical, "end_crystal", 65, 26));
    }

    @Test void everyIntermediateWindowMustMatchBeforeNextClick() {
        var before = shell();
        var physical = before.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        var opportunity = KitVariantExchangePlan.shellOpportunity(
                physical, "end_crystal", 64, 26);
        assertNotNull(opportunity);
        var plan = opportunity.withLiveSource(3, crystal(15));
        assertNotNull(plan);
        Map<Integer, ItemStack> player = new HashMap<>();
        player.put(10, crystal(15));
        player.put(13, helmet(99));
        var evidence = KitVariantExchangeEvidence.capture(
                plan, before, before, player, 10, 11);
        assertNotNull(evidence);
        assertTrue(evidence.matchesBefore(before, player));
        assertFalse(evidence.matchesAfterRemoval(before, player));

        Map<Integer, ItemStack> removed = new HashMap<>(before);
        removed.remove(0);
        Map<Integer, ItemStack> removedPlayer = new HashMap<>(player);
        removedPlayer.put(11, namedCrystal(15));
        assertTrue(evidence.matchesAfterRemoval(removed, removedPlayer));
        removedPlayer.put(11, namedCrystal(14));
        assertFalse(evidence.matchesAfterRemoval(removed, removedPlayer));
        removedPlayer.put(11, namedCrystal(15));

        Map<Integer, ItemStack> added = new HashMap<>(removed);
        added.put(1, crystal(64));
        removedPlayer.remove(10);
        assertTrue(evidence.matchesAfterAddition(added, removedPlayer));
        added.put(0, helmet(99));
        assertFalse(evidence.matchesRecoveredWithCargo(
                new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1),
                helmet(99))); // An empty box cannot fake a recovered shell.
        assertEquals(64, added.get(1).getAmount());
    }
}
