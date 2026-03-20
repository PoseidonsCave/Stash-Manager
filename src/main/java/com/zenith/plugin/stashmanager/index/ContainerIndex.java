package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
}
