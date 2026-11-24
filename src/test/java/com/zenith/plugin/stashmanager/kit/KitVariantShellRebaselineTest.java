package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitVariantShellRebaselineTest {
    private static final String OLD = "a".repeat(64);

    @Test
    void requiresExplicitOldAndLiveHashesAndExactPhysicalContents() {
        Map<Integer, ItemStack> slots = fullShell();
        Map<String, Integer> packed = Map.of("obsidian", 26, "end_crystal", 15);
        Map<String, Integer> recipe = Map.of("obsidian", 26, "end_crystal", 15,
                "diamond_helmet", 1);
        String live = KitVariantFingerprint.canonicalShell(slots);
        assertTrue(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, OLD, live));
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, "b".repeat(64), live));
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, OLD, "b".repeat(64)));
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                KitVariantFingerprint.shell(slots), KitVariantFingerprint.shell(slots), live));

        slots.put(26, stack("end_crystal", 14));
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, OLD, live));
        slots.put(26, stack("diamond_sword", 1));
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, OLD, KitVariantFingerprint.canonicalShell(slots)));
        slots.remove(26);
        assertFalse(KitVariantShellRebaseline.matches(slots, packed, recipe,
                OLD, OLD, KitVariantFingerprint.canonicalShell(slots)));
    }

    private static Map<Integer, ItemStack> fullShell() {
        Map<Integer, ItemStack> slots = new HashMap<>();
        for (int i = 0; i < 26; i++) slots.put(i, stack("obsidian", 1));
        slots.put(26, stack("end_crystal", 15));
        return slots;
    }

    private static ItemStack stack(String item, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(item).id(), amount);
    }
}
