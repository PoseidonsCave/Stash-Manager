package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.Map;

/** Exact two-exchange repair for the witnessed CPVP crystal layout. */
public final class KitCrystalNormalizationPlan {
    private KitCrystalNormalizationPlan() { }

    public record Step(int number, int outgoingSlot, int anchorSlot, int amount,
                       int slotsAfter, ItemStack outgoing, ItemStack anchor) { }

    public static Step next(Map<Integer, ItemStack> shell) {
        if (shell == null || shell.size() != 27) return null;
        ItemStack a = shell.get(20), b = shell.get(21), c = shell.get(22);
        ItemStack d = shell.get(23), e = shell.get(24);
        if (!crystal(a, 64) || !crystal(b, 18) || !crystal(c, 64)
                || !crystal(d, 60) || !crystal(e, 50)
                || !same(c, e) || same(a, c) || same(d, c) || same(a, d)) return null;
        for (var entry : shell.entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= 27) return null;
            if (entry.getKey() < 20 || entry.getKey() > 24) {
                if ("end_crystal".equals(ItemIdentifier.getItemId(entry.getValue()))) return null;
            }
        }
        if (!same(b, c) && !same(b, a) && !same(b, d))
            return new Step(1, 21, 22, 18, 27, b, c);
        if (same(b, c))
            return new Step(2, 23, 22, 60, 26, d, c);
        return null;
    }

    public static boolean normalized(Map<Integer, ItemStack> shell) {
        if (shell == null || shell.size() != 27) return false;
        ItemStack a = shell.get(20), b = shell.get(21), c = shell.get(22);
        ItemStack d = shell.get(23), e = shell.get(24);
        return crystal(a, 64) && crystal(b, 18) && crystal(c, 64)
                && crystal(d, 60) && crystal(e, 50)
                && same(b, c) && same(d, c) && same(e, c) && !same(a, c);
    }

    private static boolean crystal(ItemStack stack, int amount) {
        return stack != null && stack.getAmount() == amount
                && "end_crystal".equals(ItemIdentifier.getItemId(stack));
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return KitVariantFingerprint.sameComponents(a, b);
    }
}
