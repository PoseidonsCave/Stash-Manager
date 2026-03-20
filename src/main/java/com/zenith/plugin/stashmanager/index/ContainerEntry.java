package com.zenith.plugin.stashmanager.index;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Single container record in the index.
public record ContainerEntry(
    int x, int y, int z,
    String blockType,
    boolean isDouble,
    Map<String, Integer> items,
    int shulkerCount,
    List<ShulkerDetail> shulkerDetails,
    long timestamp
) {

    // Per-shulker breakdown: color and items inside.
    public record ShulkerDetail(
        String color,
        Map<String, Integer> items
    ) {
        public ShulkerDetail {
            items = items == null ? Collections.emptyMap() : new LinkedHashMap<>(items);
        }
    }

    public ContainerEntry {
        items = items == null ? Collections.emptyMap() : new LinkedHashMap<>(items);
        shulkerDetails = shulkerDetails == null ? Collections.emptyList() : List.copyOf(shulkerDetails);
    }

    // Unique position key for deduplication.
    public long posKey() {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    // Human-readable block type name.
    public String readableBlockType() {
        String base = blockType.replace("minecraft:", "");
        return switch (base) {
            case "chest" -> isDouble ? "Double Chest" : "Chest";
            case "trapped_chest" -> isDouble ? "Double Trapped Chest" : "Trapped Chest";
            case "barrel" -> "Barrel";
            case "shulker_box" -> "Shulker Box";
            case "hopper" -> "Hopper";
            case "dispenser" -> "Dispenser";
            case "dropper" -> "Dropper";
            default -> base;
        };
    }

    // Formatted position string.
    public String posString() {
        return x + ", " + y + ", " + z;
    }

    // Total item count across all items.
    public int totalItems() {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    // Check if this container holds items matching the search term.
    public boolean containsItem(String search) {
        String lower = search.toLowerCase();
        return items.keySet().stream().anyMatch(id -> id.toLowerCase().contains(lower));
    }

    // Get the count of a specific item by partial name match.
    public int getItemCount(String search) {
        String lower = search.toLowerCase();
        return items.entrySet().stream()
            .filter(e -> e.getKey().toLowerCase().contains(lower))
            .mapToInt(Map.Entry::getValue)
            .sum();
    }
}
