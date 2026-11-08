package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitRetainedShellTest {
    @Test
    void canonicalFingerprintIgnoresTopLevelComponentOrder() {
        var first = shell("Ores", true);
        var reversed = shell("Ores", false);

        assertNotEquals(KitRetainedShell.legacyHash(first, first.getDataComponents()),
                KitRetainedShell.legacyHash(reversed, reversed.getDataComponents()));
        assertEquals(KitRetainedShell.evidence(44, first).componentsHash(),
                KitRetainedShell.evidence(44, reversed).componentsHash());
    }

    @Test
    void legacyOrderProofRequiresSameSlotItemAndComponentValues() {
        var original = shell("Ores", true);
        var reordered = shell("Ores", false);
        var old = new KitFulfillmentJournalStore.RetainedShell(44, "white_shulker_box",
                KitRetainedShell.legacyHash(original, original.getDataComponents()));

        assertTrue(KitRetainedShell.matchesLegacyComponentOrder(old, 44, reordered));
        assertEquals(44, KitRetainedShell.uniqueSlot(old, Map.of(44, reordered), -1));
        assertFalse(KitRetainedShell.matchesLegacyComponentOrder(old, 43, reordered));
        assertEquals(-1, KitRetainedShell.uniqueSlot(old, Map.of(43, reordered), -1));
        assertFalse(KitRetainedShell.matchesLegacyComponentOrder(old, 44,
                shell("Different", false)));
        assertEquals(-1, KitRetainedShell.uniqueSlot(old,
                Map.of(44, reordered, 43, shell("Ores", true)), -1));
    }

    @Test
    void canonicalIdentityStillRejectsDistinctEmptyBoxes() {
        var ores = KitRetainedShell.evidence(44, shell("Ores", true));
        var tools = shell("Tools", false);
        assertNotEquals(ores.componentsHash(), KitRetainedShell.evidence(43, tools).componentsHash());
        assertEquals(-1, KitRetainedShell.uniqueSlot(ores, Map.of(43, tools), -1));
    }

    private static ItemStack shell(String name, boolean nameFirst) {
        var components = new DataComponents(new LinkedHashMap<>());
        if (nameFirst) {
            components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
            components.put(DataComponentTypes.MAX_STACK_SIZE, 1);
        } else {
            components.put(DataComponentTypes.MAX_STACK_SIZE, 1);
            components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        }
        return new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1, components);
    }
}
