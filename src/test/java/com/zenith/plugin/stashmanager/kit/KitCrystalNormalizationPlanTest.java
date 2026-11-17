package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitCrystalNormalizationPlanTest {
    @Test
    void exactWitnessRequiresTwoExchangesAndPreservesAllCrystalUnits() {
        Map<Integer, ItemStack> shell = shell();
        var first = KitCrystalNormalizationPlan.next(shell);
        assertNotNull(first);
        assertEquals(1, first.number());
        assertEquals(21, first.outgoingSlot());
        assertEquals(18, first.amount());
        assertEquals(27, first.slotsAfter());

        shell.put(21, crystal(18, "C"));
        var second = KitCrystalNormalizationPlan.next(shell);
        assertNotNull(second);
        assertEquals(2, second.number());
        assertEquals(23, second.outgoingSlot());
        assertEquals(60, second.amount());
        assertEquals(26, second.slotsAfter());

        shell.put(23, crystal(60, "C"));
        assertTrue(KitCrystalNormalizationPlan.normalized(shell));
        assertNull(KitCrystalNormalizationPlan.next(shell));
    }

    @Test
    void rejectsAnyChangedStackOrMissingSlot() {
        Map<Integer, ItemStack> shell = shell();
        shell.put(21, crystal(19, "B"));
        assertNull(KitCrystalNormalizationPlan.next(shell));
        shell = shell();
        shell.remove(0);
        assertNull(KitCrystalNormalizationPlan.next(shell));
        shell = shell();
        shell.put(23, crystal(60, "C"));
        assertNull(KitCrystalNormalizationPlan.next(shell));
    }

    private static Map<Integer, ItemStack> shell() {
        Map<Integer, ItemStack> shell = new LinkedHashMap<>();
        for (int slot = 0; slot < 27; slot++)
            shell.put(slot, new ItemStack(ItemRegistry.REGISTRY.get("diamond_sword").id(), 1));
        shell.put(20, crystal(64, "A"));
        shell.put(21, crystal(18, "B"));
        shell.put(22, crystal(64, "C"));
        shell.put(23, crystal(60, "D"));
        shell.put(24, crystal(50, "C"));
        return shell;
    }

    private static ItemStack crystal(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(), amount, components);
    }
}
