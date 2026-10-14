package com.zenith.plugin.stashmanager.profile;

import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.Map;
import java.util.TreeMap;

/** A named, component based selector for equipment. */
public record ItemProfile(String name, String itemId, Mode mode,
                          Map<String, Integer> enchantments, boolean laneEnabled) {
    public enum Mode { CONTAINS, EXACT }

    public ItemProfile {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Profile names use 1–64 lowercase letters, digits, or underscores and start with a letter.");
        }
        itemId = ItemIdentifier.baseItemId(itemId);
        if (itemId == null || itemId.isBlank() || itemId.contains(":")) {
            throw new IllegalArgumentException("Choose a valid base item ID.");
        }
        mode = mode == null ? Mode.CONTAINS : mode;
        Map<String, Integer> normalized = new TreeMap<>();
        if (enchantments != null) {
            enchantments.forEach((id, level) -> {
                String key = ItemIdentifier.baseItemId(id);
                if (key == null || !key.matches("[a-z0-9_]+") || level == null
                        || level < 1 || level > 255) {
                    throw new IllegalArgumentException("Enchantments need a valid ID and level from 1 to 255.");
                }
                if (EnchantmentRegistry.REGISTRY.get(key) == null) {
                    throw new IllegalArgumentException("Unknown enchantment ID: " + key);
                }
                normalized.put(key, level);
            });
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Add at least one enchantment to a profile.");
        }
        enchantments = Map.copyOf(normalized);
    }

    public String storageKey() {
        return "profile:" + name;
    }

    public boolean matches(String observedItemId) {
        if (!itemId.equals(ItemIdentifier.baseItemId(observedItemId))) return false;
        Map<String, Integer> observed = ItemIdentifier.enchantmentsInItemId(observedItemId);
        if (mode == Mode.EXACT) return enchantments.equals(observed);
        return enchantments.entrySet().stream().allMatch(entry ->
                observed.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    /** Reject rules whose claimed items could overlap, even before those items are scanned. */
    public boolean overlaps(ItemProfile other) {
        if (!itemId.equals(other.itemId())) return false;
        if (mode == Mode.CONTAINS && other.mode == Mode.CONTAINS) {
            // Vanilla tools cannot carry both. An illegally enchanted item is left unrouted
            // by ItemProfiles rather than silently assigned to either profile.
            boolean mutuallyExclusive = enchantments.containsKey("fortune")
                    && other.enchantments.containsKey("silk_touch")
                    || enchantments.containsKey("silk_touch")
                    && other.enchantments.containsKey("fortune");
            return !mutuallyExclusive;
        }
        if (mode == Mode.EXACT && other.mode == Mode.EXACT) return enchantments.equals(other.enchantments);
        ItemProfile exact = mode == Mode.EXACT ? this : other;
        ItemProfile contains = mode == Mode.CONTAINS ? this : other;
        return contains.enchantments.entrySet().stream().allMatch(entry ->
                exact.enchantments.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }
}
