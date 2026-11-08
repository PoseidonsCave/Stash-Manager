package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitVariantExchangeEvidenceTest {
    @Test
    void acceptsOnlyExactObservedStages() {
        var plan = plan();
        var shell = shell();
        var player = player();
        var evidence = KitVariantExchangeEvidence.capture(plan, shell, shell, player, 13, 15);
        assertNotNull(evidence);
        assertTrue(evidence.matchesBefore(shell, player));

        var removedShell = new HashMap<>(shell);
        removedShell.remove(24);
        var removedPlayer = new HashMap<>(player);
        removedPlayer.put(15, crystal(15, "B"));
        assertTrue(evidence.matchesAfterRemoval(removedShell, removedPlayer));
        assertFalse(evidence.matchesAfterAddition(removedShell, removedPlayer));

        var addedShell = new HashMap<>(removedShell);
        addedShell.put(21, crystal(64, "A"));
        var addedPlayer = new HashMap<>(removedPlayer);
        addedPlayer.remove(13);
        assertTrue(evidence.matchesAfterAddition(addedShell, addedPlayer));
        assertFalse(evidence.matchesBefore(addedShell, addedPlayer));
        assertTrue(evidence.matchesRecoveredShell(box(addedShell)));
        assertFalse(evidence.matchesRecoveredShell(box(shell)));

        addedPlayer.put(16, crystal(1, "B"));
        assertFalse(evidence.matchesAfterAddition(addedShell, addedPlayer));
    }

    @Test
    void rejectsChangedOrUnsafePreflight() {
        var plan = plan();
        var shell = shell();
        var player = player();
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, shell, player, 13, 13));
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, shell, player, 13, 44));
        var changedSource = new HashMap<>(player);
        changedSource.put(13, crystal(15, "B"));
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, shell, changedSource, 13, 15));
        var occupiedDestination = new HashMap<>(player);
        occupiedDestination.put(15, crystal(1, "B"));
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, shell, occupiedDestination,
                13, 15));
        var changedShell = new HashMap<>(shell);
        changedShell.put(24, crystal(14, "B"));
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, changedShell, player, 13, 15));
        changedShell = new HashMap<>(shell);
        changedShell.put(0, new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 2));
        assertNull(KitVariantExchangeEvidence.capture(plan, shell, changedShell, player, 13, 15));
    }

    private static KitVariantExchangePlan.Plan plan() {
        var shell = List.of(
                new KitVariantExchangePlan.SlotStack(20, crystal(64, "A")),
                new KitVariantExchangePlan.SlotStack(21, crystal(49, "A")),
                new KitVariantExchangePlan.SlotStack(22, crystal(64, "B")),
                new KitVariantExchangePlan.SlotStack(23, crystal(64, "B")),
                new KitVariantExchangePlan.SlotStack(24, crystal(15, "B")));
        var plan = KitVariantExchangePlan.oneStack(shell,
                List.of(new KitVariantExchangePlan.SlotStack(4, crystal(64, "A"))),
                "end_crystal", 256, 4);
        assertNotNull(plan);
        return plan;
    }

    private static Map<Integer, ItemStack> shell() {
        var result = new HashMap<Integer, ItemStack>();
        for (int slot = 0; slot < 22; slot++) {
            result.put(slot, new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 1));
        }
        result.put(20, crystal(64, "A"));
        result.put(21, crystal(49, "A"));
        result.put(22, crystal(64, "B"));
        result.put(23, crystal(64, "B"));
        result.put(24, crystal(15, "B"));
        result.put(25, new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 1));
        result.put(26, new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 1));
        return result;
    }

    private static Map<Integer, ItemStack> player() {
        return Map.of(13, crystal(15, "A"),
                14, new ItemStack(ItemRegistry.REGISTRY.get("diamond_helmet").id(), 1));
    }

    private static ItemStack crystal(int amount, String variant) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(variant));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(),
                amount, components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box(Map<Integer, ItemStack> contents) {
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) slots.add(contents.get(i));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(),
                1, components);
    }
}
