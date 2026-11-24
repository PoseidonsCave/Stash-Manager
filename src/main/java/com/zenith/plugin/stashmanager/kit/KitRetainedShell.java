package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single empty source shell reserved for the next kit box, never a generic keep rule. */
public final class KitRetainedShell {
    private KitRetainedShell() { }

    public static KitFulfillmentJournalStore.RetainedShell evidence(int rawSlot, ItemStack stack) {
        if (rawSlot < 9 || rawSlot > 44 || stack == null || stack.getAmount() != 1) return null;
        String itemId = ItemIdentifier.getItemId(stack);
        if (!ItemIdentifier.baseItemId(itemId).endsWith("shulker_box")
                || !ItemIdentifier.readShulkerContents(stack).isEmpty()
                || ItemIdentifier.readShulkerOccupiedSlots(stack) != 0) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(stack.getId()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            // DataComponents.toString() delegates to Map.toString(), whose iteration order
            // can change when the library clones or decodes an otherwise identical item.
            // Hash the protocol representation with component types in a fixed order.
            ByteBuf encoded = Unpooled.buffer();
            try {
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
            } finally {
                encoded.release();
            }
            return new KitFulfillmentJournalStore.RetainedShell(rawSlot, itemId,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Prove that a pre-fix hash differs only in top-level component iteration order. */
    public static boolean matchesLegacyComponentOrder(
            KitFulfillmentJournalStore.RetainedShell expected, int rawSlot, ItemStack stack) {
        if (expected == null || expected.rawSlot() != rawSlot || evidence(rawSlot, stack) == null
                || !expected.itemId().equals(ItemIdentifier.getItemId(stack))) return false;
        DataComponents components = stack.getDataComponents();
        if (components == null) return legacyHash(stack, null).equals(expected.componentsHash());
        List<Map.Entry<DataComponentType<?>, DataComponent<?, ?>>> entries =
                new ArrayList<>(components.getDataComponents().entrySet());
        // A recovery proof must remain bounded; a complex item goes to manual review.
        if (entries.size() > 8) return false;
        return matchesLegacyOrder(entries, 0, expected.componentsHash(), stack.getId());
    }

    private static boolean matchesLegacyOrder(
            List<Map.Entry<DataComponentType<?>, DataComponent<?, ?>>> entries,
            int offset, String expectedHash, int itemNumericId) {
        if (offset == entries.size()) {
            var ordered = new LinkedHashMap<DataComponentType<?>, DataComponent<?, ?>>();
            for (var entry : entries) ordered.put(entry.getKey(), entry.getValue());
            return legacyHash(itemNumericId, new DataComponents(ordered)).equals(expectedHash);
        }
        for (int i = offset; i < entries.size(); i++) {
            java.util.Collections.swap(entries, offset, i);
            boolean matches = matchesLegacyOrder(entries, offset + 1, expectedHash, itemNumericId);
            java.util.Collections.swap(entries, offset, i);
            if (matches) return true;
        }
        return false;
    }

    static String legacyHash(ItemStack stack, DataComponents components) {
        return legacyHash(stack.getId(), components);
    }

    private static String legacyHash(int itemNumericId, DataComponents components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(itemNumericId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(components).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Return a slot only if one physical shell matches the complete journaled evidence. */
    public static int uniqueSlot(KitFulfillmentJournalStore.RetainedShell expected,
                                 Map<Integer, ItemStack> live, int ownedSlot) {
        if (expected == null || live == null) return -1;
        int found = -1;
        for (var entry : live.entrySet()) {
            int raw = entry.getKey();
            if (raw == ownedSlot) continue;
            var observed = evidence(raw, entry.getValue());
            if (observed == null || !expected.itemId().equals(observed.itemId())
                    || !expected.componentsHash().equals(observed.componentsHash())) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        if (found >= 0) return found;

        // A pre-fix checkpoint may contain the same component values in a different
        // HashMap order after reconnect. Accept that legacy proof only at its original
        // raw slot, and only if no second shell has the same canonical fingerprint.
        int raw = expected.rawSlot();
        if (raw == ownedSlot || !matchesLegacyComponentOrder(expected, raw, live.get(raw)))
            return -1;
        var observed = evidence(raw, live.get(raw));
        for (var entry : live.entrySet()) {
            if (entry.getKey() == raw || entry.getKey() == ownedSlot) continue;
            var another = evidence(entry.getKey(), entry.getValue());
            if (another != null && observed.componentsHash().equals(another.componentsHash()))
                return -1;
        }
        return raw;
    }
}
