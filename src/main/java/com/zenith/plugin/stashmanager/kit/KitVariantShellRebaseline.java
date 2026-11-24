package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Explicit operator re-baseline of a legacy full-shell fingerprint. No inventory writes. */
public final class KitVariantShellRebaseline {
    private KitVariantShellRebaseline() { }

    public static boolean matches(Map<Integer, ItemStack> physical,
                                  Map<String, Integer> packed,
                                  Map<String, Integer> recipe,
                                  String savedLegacyHash,
                                  String acknowledgedLegacyHash,
                                  String acknowledgedCanonicalHash) {
        if (physical == null || physical.size() != 27 || packed == null || packed.isEmpty()
                || recipe == null || savedLegacyHash == null
                || !savedLegacyHash.matches("[0-9a-f]{64}")
                || !savedLegacyHash.equals(acknowledgedLegacyHash)
                || acknowledgedCanonicalHash == null
                || !acknowledgedCanonicalHash.matches("[0-9a-f]{64}")) return false;
        String liveLegacy = KitVariantFingerprint.shell(physical);
        String liveCanonical = KitVariantFingerprint.canonicalShell(physical);
        if (savedLegacyHash.equals(liveLegacy)
                || !acknowledgedCanonicalHash.equals(liveCanonical)) return false;
        Map<String, Integer> live = new HashMap<>();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = physical.get(slot);
            if (stack == null || stack.getAmount() <= 0) return false;
            String item = ItemIdentifier.getItemId(stack);
            if (item.isBlank() || !recipe.containsKey(item)) return false;
            live.merge(item, stack.getAmount(), Integer::sum);
        }
        if (!live.equals(packed)) return false;
        for (var entry : live.entrySet()) {
            if (entry.getValue() > recipe.getOrDefault(entry.getKey(), 0)) return false;
        }
        return true;
    }
}
