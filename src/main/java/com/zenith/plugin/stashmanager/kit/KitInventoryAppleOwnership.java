package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Exact live-slot evidence for apples intentionally retained by a kit transaction.
 * A component-rendering change can only reject recovery, never authorize a move. */
public final class KitInventoryAppleOwnership {
    public record Slot(int amount, boolean customPresentation, String componentHash) { }

    private KitInventoryAppleOwnership() { }

    public static Slot evidence(ItemStack stack) {
        if (stack == null || stack.getAmount() < 1 || stack.getAmount() > 64
                || !"enchanted_golden_apple".equals(ItemIdentifier.getItemId(stack))) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(stack.getId()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(stack.getDataComponents()).getBytes(StandardCharsets.UTF_8));
            return new Slot(stack.getAmount(), ItemIdentifier.hasCustomPresentation(stack),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static boolean matches(Map<Integer, Slot> expected, Map<Integer, ItemStack> live) {
        if (expected == null || live == null) return false;
        for (var entry : expected.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getValue().equals(evidence(live.get(entry.getKey())))) return false;
        }
        return true;
    }
}
