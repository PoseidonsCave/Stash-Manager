package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KitCrystalSourceGateTest {
    @Test
    void sixReplacementCrystalsMustMatchTheShellGroupWithRoom() {
        ItemStack minority = crystal(64, "A");
        ItemStack majority = crystal(64, "B");
        ItemStack selected = KitCrystalSourceGate.partialShellVariant(List.of(
                minority, majority, crystal(64, "B"), crystal(58, "B")), 6);
        assertNotNull(selected);
        assertTrue(KitCrystalSourceGate.accepts(crystal(6, "B"), selected));
        assertFalse(KitCrystalSourceGate.accepts(crystal(6, "A"), selected));
        assertNull(KitCrystalSourceGate.partialShellVariant(List.of(
                minority, majority, crystal(64, "B"), crystal(58, "B")), 7));
    }

    @Test
    void initialBatchChoosesOneComponentGroup() {
        ItemStack selected = KitCrystalSourceGate.choose(List.of(
                crystal(64, "A"), crystal(6, "A"),
                crystal(64, "B"), crystal(64, "B"), crystal(58, "B")), 256);
        assertTrue(KitCrystalSourceGate.accepts(crystal(1, "B"), selected));
        assertFalse(KitCrystalSourceGate.accepts(crystal(1, "A"), selected));
    }

    @Test
    void fullCrystalStacksContinueIntoAnEmptyShellSlot() {
        List<ItemStack> shell = List.of(
                crystal(64, "A"), crystal(64, "A"), crystal(64, "A"));
        ItemStack selected = KitCrystalSourceGate.partialShellVariant(shell, 64);
        assertNotNull(selected);
        assertTrue(KitCrystalSourceGate.accepts(crystal(64, "A"), selected));
        assertTrue(KitCrystalSourceGate.accepts(crystal(64, "A"),
                KitFullStackContinuation.chooseCrystals(shell, 64)));
    }

    @Test
    void fullStackContinuationRejectsMixedVariantsOrAFullShell() {
        assertNull(KitCrystalSourceGate.partialShellVariant(List.of(
                crystal(64, "A"), crystal(64, "B"), crystal(64, "A")), 64));
        assertNull(KitFullStackContinuation.chooseCrystals(List.of(
                crystal(64, "A"), crystal(64, "B"), crystal(64, "A")), 64));

        List<ItemStack> fullShell = new ArrayList<>(List.of(
                crystal(64, "A"), crystal(64, "A"), crystal(64, "A")));
        for (int i = 0; i < 24; i++) {
            fullShell.add(new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 64));
        }
        assertNull(KitCrystalSourceGate.partialShellVariant(fullShell, 64));
    }

    private static ItemStack crystal(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(), amount, components);
    }
}
