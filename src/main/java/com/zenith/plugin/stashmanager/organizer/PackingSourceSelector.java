package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;

import java.util.*;

/** Prefer useful box stock; keep uncertain sources as a bounded fallback. */
final class PackingSourceSelector {
    private static final long MISS_TTL_MILLIS = 5 * 60_000L;
    private record Key(long inventory, String storageClass) {}
    private record Stock(int boxes, List<ContainerEntry.ShulkerDetail> details) {
        static Stock of(ContainerEntry entry) {
            return new Stock(entry.shulkerCount(), List.copyOf(entry.shulkerDetails()));
        }
    }
    private record Miss(Stock stock, long timestamp) {}
    record Candidate(ContainerEntry container, int rank) {
        String kind() {
            return switch (rank) {
                case 0 -> "matching_partial";
                case 1 -> "known_empty";
                case 2 -> "unknown_stock";
                case 3 -> "full_or_incompatible_snapshot";
                default -> "recent_live_miss";
            };
        }
    }
    private final Map<Key, Miss> misses = new HashMap<>();

    void reset() { misses.clear(); }

    void recordMiss(ContainerEntry entry, String storageClass, long now) {
        misses.entrySet().removeIf(e -> now - e.getValue().timestamp() >= MISS_TTL_MILLIS);
        misses.put(new Key(inventoryKey(entry), storageClass), new Miss(Stock.of(entry), now));
    }

    List<Candidate> candidates(Collection<ContainerEntry> entries, String storageClass,
                               Set<Long> tried, double x, double y, double z, long now) {
        Map<Long, ContainerEntry> physical = new HashMap<>();
        for (ContainerEntry entry : entries) {
            physical.merge(inventoryKey(entry), entry, (a, b) ->
                    b.timestamp() > a.timestamp()
                            || (b.timestamp() == a.timestamp() && b.posKey() < a.posKey()) ? b : a);
        }
        return physical.values().stream()
                .filter(entry -> !tried.contains(inventoryKey(entry)))
                .filter(PackingSourceSelector::hasBoxes)
                .map(entry -> new Candidate(entry, rank(entry, storageClass, now)))
                .sorted(Comparator.comparingInt(Candidate::rank)
                        .thenComparingInt(c -> now >= c.container().timestamp()
                                && now - c.container().timestamp() < MISS_TTL_MILLIS ? 0 : 1)
                        .thenComparingDouble(c -> distanceSquared(c.container(), x, y, z))
                        .thenComparingLong(c -> c.container().posKey()))
                .toList();
    }

    private int rank(ContainerEntry entry, String storageClass, long now) {
        Miss miss = misses.get(new Key(inventoryKey(entry), storageClass));
        if (miss != null && now >= miss.timestamp() && now - miss.timestamp() < MISS_TTL_MILLIS
                && miss.stock().equals(Stock.of(entry))) return 4;
        boolean empty = false;
        int physicalBoxes = 0;
        for (var detail : entry.shulkerDetails()) {
            if (!detail.isPhysicalInstance()) continue;
            physicalBoxes++;
            var box = ShulkerClassification.classify(detail.items());
            if (box.kind() == ShulkerClassification.Kind.EMPTY) empty = true;
            if (box.kind() == ShulkerClassification.Kind.BULK
                    && Objects.equals(storageClass, box.storageKey())
                    && box.contents().values().stream().mapToLong(Integer::longValue).sum()
                    < LaneStorageCapacity.itemCapacityFor(storageClass).itemsPerShulker()) return 0;
        }
        if (empty) return 1;
        if (physicalBoxes == 0 || physicalBoxes < entry.shulkerCount()
                || entry.shulkerDetails().stream().anyMatch(d -> !d.isPhysicalInstance())) return 2;
        return 3;
    }

    private static boolean hasBoxes(ContainerEntry entry) {
        return entry.shulkerCount() > 0 || !entry.shulkerDetails().isEmpty()
                || entry.items().keySet().stream().anyMatch(id -> id.endsWith("shulker_box"));
    }

    static long inventoryKey(ContainerEntry entry) {
        return entry.isDouble() && entry.inventoryIdentityKnown() ? entry.inventoryKey() : entry.posKey();
    }

    private static double distanceSquared(ContainerEntry entry, double x, double y, double z) {
        double dx = entry.x() - x, dy = entry.y() - y, dz = entry.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
