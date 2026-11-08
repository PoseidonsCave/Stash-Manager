package com.zenith.plugin.stashmanager.kit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;

/** Fail-closed physical fingerprints; a representation change rejects recovery. */
public final class KitVariantFingerprint {
    private KitVariantFingerprint() { }

    public static String variant(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;
        // Legacy format: persisted variant-repair checkpoints still contain this hash.
        // Do not silently reinterpret one as a canonical fingerprint.
        return hash(stack.getId() + "\u0000" + String.valueOf(stack.getDataComponents()));
    }

    /** Order-independent fingerprint for new evidence and explicit legacy migration. */
    public static String canonicalVariant(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuf encoded = Unpooled.buffer();
            try {
                encoded.writeInt(stack.getId());
                DataComponents components = stack.getDataComponents();
                encoded.writeByte(components == null ? 0 : 1);
                if (components != null) {
                    var entries = components.getDataComponents().entrySet().stream()
                            .sorted(Comparator.comparingInt(entry -> entry.getKey().getId()))
                            .toList();
                    encoded.writeInt(entries.size());
                    for (var entry : entries) {
                        encoded.writeInt(entry.getKey().getId());
                        var value = entry.getValue().getValue();
                        encoded.writeByte(value == null ? 0 : 1);
                        if (value != null) entry.getValue().write(encoded);
                    }
                }
                digest.update(encoded.nioBuffer(encoded.readerIndex(), encoded.readableBytes()));
                return HexFormat.of().formatHex(digest.digest());
            } finally {
                encoded.release();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Legacy checkpoints retain version 0; new exchange evidence uses canonical v2. */
    public static String variant(ItemStack stack, int fingerprintVersion) {
        return fingerprintVersion == 2 ? canonicalVariant(stack)
                : fingerprintVersion == 0 ? variant(stack) : null;
    }

    public static String shell(Map<Integer, ItemStack> slots, int fingerprintVersion) {
        return fingerprintVersion == 2 ? canonicalShell(slots)
                : fingerprintVersion == 0 ? shell(slots) : null;
    }

    /** A legacy hash may be migrated only when the live cargo is witnessed in its
     * source box and the old hash identifies no competing source stack. */
    public static boolean witnessedLegacyCargo(ItemStack cargo, String oldHash,
                                               Collection<ItemStack> sourceStacks) {
        if (cargo == null || cargo.getAmount() != 1 || oldHash == null
                || sourceStacks == null || sourceStacks.isEmpty()
                || oldHash.equals(variant(cargo))) return false;
        boolean witnessed = false;
        for (ItemStack source : sourceStacks) {
            if (source == null || source.getAmount() <= 0
                    || source.getId() != cargo.getId()) continue;
            if (oldHash.equals(variant(source))) return false;
            if (sameComponents(source, cargo))
                witnessed = true;
        }
        return witnessed;
    }

    /** Compare component values, not DataComponents wrapper object identity. */
    public static boolean sameComponents(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.getId() != second.getId()) return false;
        DataComponents a = first.getDataComponents();
        DataComponents b = second.getDataComponents();
        if (a == null || b == null) return a == b;
        return Objects.equals(a.getDataComponents(), b.getDataComponents());
    }

    public static String shell(Map<Integer, ItemStack> slots) {
        if (slots == null || slots.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = slots.get(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            text.append(slot).append(':').append(stack.getAmount()).append(':')
                    .append(variant(stack)).append(';');
        }
        return hash(text.toString());
    }

    public static String canonicalShell(Map<Integer, ItemStack> slots) {
        if (slots == null || slots.isEmpty()) return null;
        StringBuilder text = new StringBuilder("canonical-v2;");
        for (int slot = 0; slot < 27; slot++) {
            ItemStack stack = slots.get(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            text.append(slot).append(':').append(stack.getAmount()).append(':')
                    .append(canonicalVariant(stack)).append(';');
        }
        return hash(text.toString());
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
