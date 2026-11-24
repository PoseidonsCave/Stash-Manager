package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitAppleSourceGateTest {
    @Test
    void selectsAFullNamedGroupInsteadOfTheFirstPartialNamedStack() {
        ItemStack partial = named(19, "Faraoman™");
        ItemStack full = named(64, "Other Apple");
        ItemStack regular = regular(64);

        ItemStack selected = KitAppleSourceGate.choose(List.of(partial, full, full, regular),
                KitAppleSourceGate.Phase.NAMED, 128);

        assertNotNull(selected);
        assertTrue(KitAppleSourceGate.accepts(full, KitAppleSourceGate.Phase.NAMED, selected));
        assertFalse(KitAppleSourceGate.accepts(partial, KitAppleSourceGate.Phase.NAMED, selected));
        assertFalse(KitAppleSourceGate.accepts(regular, KitAppleSourceGate.Phase.NAMED, selected));
    }

    @Test
    void regularPhaseNeverAdmitsNamedApples() {
        ItemStack named = named(64, "Faraoman™");
        ItemStack regular = regular(64);

        ItemStack selected = KitAppleSourceGate.choose(List.of(named, regular, regular),
                KitAppleSourceGate.Phase.REGULAR, 128);

        assertNotNull(selected);
        assertTrue(KitAppleSourceGate.accepts(regular, KitAppleSourceGate.Phase.REGULAR, selected));
        assertFalse(KitAppleSourceGate.accepts(named, KitAppleSourceGate.Phase.REGULAR, selected));
        assertNull(KitAppleSourceGate.choose(List.of(named),
                KitAppleSourceGate.Phase.REGULAR, 128));
    }

    @Test
    void namedGatePreservesExactNameCapitalization() {
        ItemStack upper = named(64, "Glory Apple");
        ItemStack lower = named(64, "glory Apple");
        assertFalse(KitAppleSourceGate.accepts(lower, KitAppleSourceGate.Phase.NAMED, upper));
    }

    @Test
    void onlyAnOccludedTimedOutHopperCanBeSkippedForNamedPreference() {
        assertTrue(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "hopper",
                true, false, 0, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.REGULAR, "walk_timeout", "hopper",
                true, false, 0, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "hopper",
                true, false, 1, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "hopper",
                true, false, 0, true));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "open_timeout", "hopper",
                true, false, 0, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "chest",
                true, false, 0, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "hopper",
                false, false, 0, false));
        assertFalse(KitAppleSourceGate.maySkipInaccessibleNamedHopper(
                KitAppleSourceGate.Phase.NAMED, "walk_timeout", "hopper",
                true, true, 0, false));
    }

    private static ItemStack regular(int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get("enchanted_golden_apple").id(), amount);
    }

    private static ItemStack named(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("enchanted_golden_apple").id(),
                amount, components);
    }
}
