package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.item.ItemRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Resolves human-friendly search terms to fully-qualified Minecraft item IDs.
// Examples: "grass" → "minecraft:grass_block", "diamond sword" → "minecraft:diamond_sword".
// Resolution order: exact namespaced → exact suffix → scored fuzzy (exact > starts-with > ends-with > contains).
public final class ItemResolver {

    private ItemResolver() {}

    // Returns the best-matching full item ID. Falls back to "minecraft:<input>" if nothing matches.
    public static String resolve(String input) {
        if (input == null || input.isBlank()) return input;
        String normalised = input.trim().toLowerCase().replace(' ', '_');

        // Already namespaced — return as-is (trust the caller)
        if (normalised.contains(":")) {
            return normalised;
        }

        // Exact match against registry
        if (ItemRegistry.REGISTRY.get("minecraft:" + normalised) != null) {
            return "minecraft:" + normalised;
        }

        // Fuzzy: collect all registry keys whose local name contains the term
        List<String> matches = new ArrayList<>();
        for (String key : ItemRegistry.REGISTRY.getKeyMap().keySet()) {
            if (localName(key).contains(normalised)) {
                matches.add(key);
            }
        }

        if (matches.isEmpty()) {
            return "minecraft:" + normalised;
        }

        // Sort: exact match > starts-with boundary > ends-with boundary > plain contains,
        // then prefer shorter names (more specific) within the same tier.
        matches.sort(Comparator
            .<String, Integer>comparing(k -> score(localName(k), normalised))
            .reversed()
            .thenComparingInt(String::length));

        return matches.get(0);
    }

    // Returns all matching item IDs for the given term, sorted best-first.
    // Useful for showing suggestions when a term is ambiguous.
    public static List<String> candidates(String input) {
        if (input == null || input.isBlank()) return List.of();
        String term = input.trim().toLowerCase().replace(' ', '_');
        if (term.contains(":")) {
            term = term.substring(term.indexOf(':') + 1);
        }

        List<String> matches = new ArrayList<>();
        for (String key : ItemRegistry.REGISTRY.getKeyMap().keySet()) {
            if (localName(key).contains(term)) {
                matches.add(key);
            }
        }

        final String finalTerm = term;
        matches.sort(Comparator
            .<String, Integer>comparing(k -> score(localName(k), finalTerm))
            .reversed()
            .thenComparingInt(String::length));

        return matches;
    }

    // Strip the "minecraft:" (or any) namespace prefix.
    private static String localName(String fullId) {
        int colon = fullId.indexOf(':');
        return colon >= 0 ? fullId.substring(colon + 1) : fullId;
    }

    // 4 = exact, 3 = starts-with word boundary, 2 = ends-with word boundary, 1 = substring.
    private static int score(String local, String term) {
        if (local.equals(term))              return 4;
        if (local.startsWith(term + "_"))    return 3;
        if (local.endsWith("_" + term))      return 2;
        return 1;
    }
}
