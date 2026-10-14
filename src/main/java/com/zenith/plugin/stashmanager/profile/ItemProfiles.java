package com.zenith.plugin.stashmanager.profile;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable published snapshot shared by the scan audit, organizer and kit retrieval. */
public final class ItemProfiles {
    private static volatile Map<String, ItemProfile> profiles = Map.of();

    private ItemProfiles() {}

    public static synchronized void install(Collection<ItemProfile> definitions) {
        Map<String, ItemProfile> next = validate(definitions);
        profiles = Map.copyOf(next);
    }

    public static Map<String, ItemProfile> validate(Collection<ItemProfile> definitions) {
        Map<String, ItemProfile> next = new LinkedHashMap<>();
        for (ItemProfile profile : definitions) {
            if (next.putIfAbsent(profile.name(), profile) != null) {
                throw new IllegalArgumentException("Duplicate profile: " + profile.name());
            }
            if (!profile.laneEnabled()) continue;
            for (ItemProfile other : next.values()) {
                if (other != profile && other.laneEnabled() && profile.overlaps(other)) {
                    throw new IllegalArgumentException("Profiles " + other.name() + " and "
                            + profile.name() + " can claim the same item. Make one rule exact or disable a lane.");
                }
            }
        }
        return Map.copyOf(next);
    }

    public static Map<String, ItemProfile> all() {
        return profiles;
    }

    public static ItemProfile get(String name) {
        return profiles.get(name);
    }

    public static String route(String observedItemId) {
        String identity = ItemIdentifier.normalizeItemId(observedItemId);
        if (identity == null || identity.isBlank()) return null;
        if (identity.startsWith("profile:")) return identity;
        String base = ItemIdentifier.baseItemId(identity);
        if ("written_book".equals(base) || "enchanted_book".equals(base)) return identity;
        ItemProfile matched = null;
        for (ItemProfile profile : profiles.values()) {
            if (!profile.laneEnabled() || !profile.matches(identity)) continue;
            if (matched != null) return identity;
            matched = profile;
        }
        if (matched != null) return matched.storageKey();
        if (base.endsWith("_pickaxe")) {
            // Existing lane contracts already distinguish these two incompatible tools.
            if (identity.equals(base + "[fortune]") || identity.equals(base + "[silk_touch]")) {
                return identity;
            }
            Map<String, Integer> enchants = ItemIdentifier.enchantmentsInItemId(identity);
            if (enchants.getOrDefault("silk_touch", 0) > 0) return base + "[silk_touch]";
            if (enchants.getOrDefault("fortune", 0) > 0) return base + "[fortune]";
        }
        return base;
    }

    public static boolean matchesRequest(String request, String observedItemId) {
        if (request == null || !request.startsWith("profile:")) return false;
        ItemProfile profile = get(request.substring("profile:".length()));
        return profile != null && profile.matches(observedItemId);
    }

    public static String baseItemId(String storageClass) {
        if (storageClass != null && storageClass.startsWith("profile:")) {
            ItemProfile profile = get(storageClass.substring("profile:".length()));
            return profile == null ? null : profile.itemId();
        }
        return ItemIdentifier.baseItemId(storageClass);
    }
}
