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
    long timestamp,
    String label,
    // Direction a hopper's spout feeds into (e.g. "NORTH"); null for non-hoppers.
    String hopperFacing,
    // Canonical physical-inventory position. Both double-chest halves share this identity.
    int inventoryX, int inventoryY, int inventoryZ,
    boolean inventoryIdentityKnown,
    // X or Z for a known double-chest footprint; null for singles and legacy rows.
    String doubleChestAxis
) {

    // Per-shulker breakdown: color and items inside.
    public record ShulkerDetail(
        int slot,
        String color,
        Map<String, Integer> items
    ) {
        public ShulkerDetail {
            items = items == null ? Collections.emptyMap() : new LinkedHashMap<>(items);
        }

        // Legacy scans/database rows were aggregated by color and have no physical slot.
        public ShulkerDetail(String color, Map<String, Integer> items) {
            this(-1, color, items);
        }

        public boolean isPhysicalInstance() {
            return slot >= 0;
        }
    }

    public ContainerEntry {
        items = items == null ? Collections.emptyMap() : new LinkedHashMap<>(items);
        shulkerDetails = shulkerDetails == null ? Collections.emptyList() : List.copyOf(shulkerDetails);
        doubleChestAxis = normalizeDoubleChestAxis(isDouble, doubleChestAxis);
    }

    // Convenience constructor without label (backwards compatible).
    public ContainerEntry(int x, int y, int z, String blockType, boolean isDouble,
                          Map<String, Integer> items, int shulkerCount,
                          List<ShulkerDetail> shulkerDetails, long timestamp) {
        this(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, timestamp,
                null, null, x, y, z, !isDouble, null);
    }

    // Convenience constructor without hopperFacing (backwards compatible).
    public ContainerEntry(int x, int y, int z, String blockType, boolean isDouble,
                          Map<String, Integer> items, int shulkerCount,
                          List<ShulkerDetail> shulkerDetails, long timestamp, String label) {
        this(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, timestamp,
                label, null, x, y, z, !isDouble, null);
    }

    // Convenience constructor used by older call sites without persisted inventory identity.
    public ContainerEntry(int x, int y, int z, String blockType, boolean isDouble,
                          Map<String, Integer> items, int shulkerCount,
                          List<ShulkerDetail> shulkerDetails, long timestamp,
                          String label, String hopperFacing) {
        this(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, timestamp,
                label, hopperFacing, x, y, z, !isDouble, null);
    }

    // Compatibility constructor for callers created before physical footprint persistence.
    public ContainerEntry(int x, int y, int z, String blockType, boolean isDouble,
                          Map<String, Integer> items, int shulkerCount,
                          List<ShulkerDetail> shulkerDetails, long timestamp,
                          String label, String hopperFacing,
                          int inventoryX, int inventoryY, int inventoryZ,
                          boolean inventoryIdentityKnown) {
        this(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, timestamp,
                label, hopperFacing, inventoryX, inventoryY, inventoryZ,
                inventoryIdentityKnown, null);
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

    // Count of unique item types in this container.
    public int itemTypeCount() {
        return items.size();
    }

    // Create a copy with a new label.
    public ContainerEntry withLabel(String newLabel) {
        return new ContainerEntry(x, y, z, blockType, isDouble, items, shulkerCount,
                shulkerDetails, timestamp, newLabel, hopperFacing,
                inventoryX, inventoryY, inventoryZ, inventoryIdentityKnown, doubleChestAxis);
    }

    public long inventoryKey() {
        return ((long) inventoryX & 0x3FFFFFFL) << 38
                | ((long) inventoryY & 0xFFFL) << 26
                | ((long) inventoryZ & 0x3FFFFFFL);
    }

    public boolean inventoryFootprintKnown() {
        return !isDouble || (inventoryIdentityKnown && doubleChestAxis != null);
    }

    private static String normalizeDoubleChestAxis(boolean isDouble, String axis) {
        if (!isDouble || axis == null) return null;
        String normalized = axis.trim().toUpperCase(java.util.Locale.ROOT);
        return "X".equals(normalized) || "Z".equals(normalized) ? normalized : null;
    }
}
