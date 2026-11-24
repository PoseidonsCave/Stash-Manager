package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Test
    void rebindsOnlyAUniqueMovedEmptyShell() {
        var empty = shell("Reserved", true);
        var expected = KitRetainedShell.evidence(44, empty);
        var moved = KitRetainedShellSlotRebind.rebindIfUnique(
                expected, 44, Map.of(11, empty));
        assertEquals(11, moved.rawSlot());
        assertEquals(expected.componentsHash(), moved.componentsHash());
        assertEquals(expected, KitRetainedShellSlotRebind.rebindIfUnique(
                expected, 44, Map.of(11, empty, 12, shell("Reserved", false))));
    }

    @Test
    void journaledRetainedSlotIsAdmittedOnlyWhenEveryIdenticalBoxIsPendingReturn() {
        ItemStack empty = new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1);
        ItemStack owned = filled("blue_shulker_box", "firework_rocket", 576);
        ItemStack source = filled("white_shulker_box", "experience_bottle", 1152);
        var retained = KitRetainedShell.evidence(44, empty);
        var pending = List.of(
                new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{1, 70, 2}, "shulker_box", Map.of()),
                new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{1, 70, 2}, "white_shulker_box",
                        Map.of("experience_bottle", 1152)));
        Map<Integer, ItemStack> live = Map.of(36, empty, 40, owned,
                42, source, 44, empty);
        assertEquals(-1, KitRetainedShell.uniqueSlot(retained, live, 40));
        assertEquals(44, KitRetainedShellSlotRebind.accountedSlot(retained, 40,
                Map.of("firework_rocket", 576), pending, live));
        assertEquals(-1, KitRetainedShellSlotRebind.accountedSlot(retained, 40,
                Map.of("firework_rocket", 576), pending.subList(1, 2), live));
        var extra = new HashMap<>(live);
        extra.put(35, empty);
        assertEquals(-1, KitRetainedShellSlotRebind.accountedSlot(retained, 40,
                Map.of("firework_rocket", 576), pending, extra));
        var changed = new HashMap<>(live);
        changed.put(44, shell("Different", true));
        assertEquals(-1, KitRetainedShellSlotRebind.accountedSlot(retained, 40,
                Map.of("firework_rocket", 576), pending, changed));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack filled(String boxId, String itemId, int count) {
        List<Optional<ItemStack>> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(Optional.empty());
        for (int i = 0; count > 0; i++) {
            int amount = Math.min(64, count);
            slots.set(i, Optional.of(new ItemStack(
                    ItemRegistry.REGISTRY.get(itemId).id(), amount)));
            count -= amount;
        }
        var components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get(boxId).id(), 1, components);
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
