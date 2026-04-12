package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Thread-safe in-memory container inventory index with optional DB persistence.
public class ContainerIndex {

    private final ConcurrentHashMap<Long, ContainerEntry> entries = new ConcurrentHashMap<>();
    private volatile long lastScanTimestamp = 0;
    private DatabaseManager database;

    public void setDatabase(DatabaseManager database) {
        this.database = database;
    }

    public void put(ContainerEntry entry) {
        entries.put(entry.posKey(), entry);
        lastScanTimestamp = System.currentTimeMillis();

        // Persist to database asynchronously
        if (database != null && database.isInitialized()) {
            try {
                database.upsertContainer(entry);
            } catch (Exception e) {
                // Log but don't fail the in-memory operation
            }
        }
    }

    public ContainerEntry get(int x, int y, int z) {
        long key = ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
        return entries.get(key);
    }

    public Collection<ContainerEntry> getAll() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        lastScanTimestamp = 0;
    }

    // Clear both in-memory index and database contents.
    public void clearAll() {
        entries.clear();
        lastScanTimestamp = 0;
        if (database != null && database.isInitialized()) {
            try {
                database.clearAll();
            } catch (Exception e) {
                // Log but don't fail
            }
        }
    }

    // Load all container entries from the database into the in-memory index.
    // Returns the number of entries loaded.
    public int loadFromDatabase() {
        if (database == null || !database.isInitialized()) return 0;
        try {
            List<ContainerEntry> all = database.getAllContainers();
            for (ContainerEntry entry : all) {
                entries.put(entry.posKey(), entry);
            }
            if (!all.isEmpty()) {
                lastScanTimestamp = all.stream()
                    .mapToLong(ContainerEntry::timestamp)
                    .max()
                    .orElse(System.currentTimeMillis());
            }
            return all.size();
        } catch (Exception e) {
            return 0;
        }
    }

    public long getLastScanTimestamp() {
        return lastScanTimestamp;
    }

    // Search for containers holding items matching the search term.
    public List<ContainerEntry> search(String itemSearch) {
        List<ContainerEntry> results = new ArrayList<>();
        for (ContainerEntry entry : entries.values()) {
            if (entry.containsItem(itemSearch)) {
                results.add(entry);
            }
        }
        return results;
    }

    // Get a paginated list of all containers (1-based page number).
    public List<ContainerEntry> getPage(int page, int pageSize) {
        List<ContainerEntry> all = new ArrayList<>(entries.values());
        int start = (page - 1) * pageSize;
        if (start >= all.size()) return List.of();
        int end = Math.min(start + pageSize, all.size());
        return all.subList(start, end);
    }

    // Total number of pages for the given page size.
    public int totalPages(int pageSize) {
        return Math.max(1, (int) Math.ceil((double) entries.size() / pageSize));
    }

    // Total count of a specific item across all containers.
    public int totalItemCount(String itemSearch) {
        int total = 0;
        for (ContainerEntry entry : entries.values()) {
            total += entry.getItemCount(itemSearch);
        }
        return total;
    }

    // Get containers in a specific region defined by pos1 and pos2.
    public List<ContainerEntry> getInRegion(int[] pos1, int[] pos2) {
        if (pos1 == null || pos2 == null) return Collections.emptyList();
        int minX = Math.min(pos1[0], pos2[0]);
        int minY = Math.min(pos1[1], pos2[1]);
        int minZ = Math.min(pos1[2], pos2[2]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int maxY = Math.max(pos1[1], pos2[1]);
        int maxZ = Math.max(pos1[2], pos2[2]);

        List<ContainerEntry> result = new ArrayList<>();
        for (ContainerEntry entry : entries.values()) {
            if (entry.x() >= minX && entry.x() <= maxX
                    && entry.y() >= minY && entry.y() <= maxY
                    && entry.z() >= minZ && entry.z() <= maxZ) {
                result.add(entry);
            }
        }
        return result;
    }

    // Get a detailed summary of the current index.
    public String getDetailedSummary() {
        if (entries.isEmpty()) return "No data. Run /stash scan first.";

        int totalItems = entries.values().stream()
            .mapToInt(ContainerEntry::totalItems).sum();
        int totalTypes = entries.values().stream()
            .flatMap(e -> e.items().keySet().stream())
            .collect(Collectors.toSet()).size();
        int totalShulkers = entries.values().stream()
            .mapToInt(ContainerEntry::shulkerCount).sum();
        int doubleChests = (int) entries.values().stream()
            .filter(ContainerEntry::isDouble).count();

        return entries.size() + " containers (" + doubleChests + " double chests), "
            + totalItems + " items, "
            + totalTypes + " types, "
            + totalShulkers + " shulker boxes";
    }

    // Time since last scan as a human-readable string.
    public String timeSinceLastScan() {
        if (lastScanTimestamp == 0) return "never";
        long elapsed = System.currentTimeMillis() - lastScanTimestamp;
        long seconds = elapsed / 1000;
        if (seconds < 60) return seconds + " seconds ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " minutes ago";
        long hours = minutes / 60;
        return hours + " hours ago";
    }

    // Assign labels to containers based on their primary contents.
    public void assignLabels() {
        for (var entry : entries.entrySet()) {
            ContainerEntry container = entry.getValue();
            if (container.items().isEmpty()) continue;
            if (container.label() != null && !container.label().isBlank()) continue;

            // Find the item with the highest count
            String primaryItem = container.items().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

            if (primaryItem != null) {
                String label = formatLabel(primaryItem);
                ContainerEntry labeled = container.withLabel(label);
                entries.put(entry.getKey(), labeled);

                // Persist label to DB
                if (database != null && database.isInitialized()) {
                    try {
                        database.updateLabel(container.x(), container.y(), container.z(), label);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static String formatLabel(String itemId) {
        String base = itemId;
        if (base.startsWith("minecraft:")) {
            base = base.substring("minecraft:".length());
        }
        String[] words = base.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
