package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitItemSourcePolicyTest {
    @Test
    void forkPolicySelectsOnlyItsItemAndFillsTheRequest() {
        KitItemSourcePolicy obsidian = new KitItemSourcePolicy("obsidian",
                stack -> true, KitVariantFingerprint::sameComponents, 64, false);
        ItemStack other = stack("end_crystal", 64);
        ItemStack partial = namedObsidian(19, "A");
        ItemStack full = namedObsidian(64, "B");

        assertSame(full, obsidian.choose(List.of(other, partial, full, full), 128));
        assertFalse(obsidian.accepts(full, partial));
        assertFalse(obsidian.accepts(other, full));
        assertNull(obsidian.choose(List.of(other), 64));
        assertNull(obsidian.choose(List.of(full), 0));
    }

    @Test
    void rocketPolicyPrefersOneUsableVariantOverMixedPartialStacks() {
        ItemStack shortA = namedRocket(39, "A");
        ItemStack shortB = namedRocket(39, "B");
        ItemStack fullC = namedRocket(64, "C");
        ItemStack partialC = namedRocket(50, "C");
        List<ItemStack> observed = new java.util.ArrayList<>(
                List.of(shortA, shortB));
        observed.addAll(Collections.nCopies(17, fullC));

        assertSame(fullC, KitItemSourcePolicy.ROCKETS.choose(observed, 576));
        assertFalse(KitItemSourcePolicy.ROCKETS.accepts(shortA, fullC));
        assertSame(partialC, KitItemSourcePolicy.ROCKETS.partialShellVariant(
                List.of(partialC), 14));
    }

    @Test
    void onlyNamedApplePreferenceMaySkipAnUntouchedOccludedHopper() {
        assertTrue(KitItemSourcePolicy.NAMED_APPLES.maySkipInaccessibleSource(
                "walk_timeout", "hopper", true, false, 0, false));
        assertFalse(KitItemSourcePolicy.REGULAR_APPLES.maySkipInaccessibleSource(
                "walk_timeout", "hopper", true, false, 0, false));
        assertFalse(KitItemSourcePolicy.CRYSTALS.maySkipInaccessibleSource(
                "walk_timeout", "hopper", true, false, 0, false));
        assertFalse(KitItemSourcePolicy.NAMED_APPLES.maySkipInaccessibleSource(
                "walk_timeout", "hopper", true, false, 1, false));
    }

    @Test
    void rejectsInvalidPolicies() {
        assertThrows(IllegalArgumentException.class, () -> new KitItemSourcePolicy("",
                stack -> true, (first, second) -> true, 64, false));
        assertThrows(IllegalArgumentException.class, () -> new KitItemSourcePolicy("obsidian",
                stack -> true, (first, second) -> true, 0, false));
        assertEquals(64, KitItemSourcePolicy.CRYSTALS.stackLimit());
    }

    private static ItemStack stack(String itemId, int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get(itemId).id(), count);
    }

    private static ItemStack namedObsidian(int count, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("obsidian").id(), count, components);
    }

    private static ItemStack namedRocket(int count, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("firework_rocket").id(),
                count, components);
    }
}
