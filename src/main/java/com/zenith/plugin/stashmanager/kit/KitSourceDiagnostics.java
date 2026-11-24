package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, read-only index evidence for a checkpoint's next kit item. */
public final class KitSourceDiagnostics {
    private KitSourceDiagnostics() { }

    public record Source(ContainerEntry entry, int total, int direct, int nested,
                         int matchingShulkers, double distance) { }

    public static List<Source> find(Collection<ContainerEntry> entries, String exactItemId,
                                    double playerX, double playerY, double playerZ, int limit) {
        if (entries == null || exactItemId == null || exactItemId.isBlank() || limit <= 0) {
            return List.of();
        }
        Map<Long, Source> byInventory = new LinkedHashMap<>();
        for (ContainerEntry entry : entries) {
            if (entry == null || !entry.inventoryFootprintKnown()) continue;
            Integer indexedAmount = entry.items().get(exactItemId);
            int total = Math.max(0, indexedAmount == null ? 0 : indexedAmount);
            if (total == 0) continue;
            int nested = 0;
            int matchingShulkers = 0;
            for (ContainerEntry.ShulkerDetail detail : entry.shulkerDetails()) {
                Integer nestedAmount = detail.items().get(exactItemId);
                int amount = Math.max(0, nestedAmount == null ? 0 : nestedAmount);
                nested += amount;
                if (amount > 0 && detail.isPhysicalInstance()) matchingShulkers++;
            }
            nested = Math.min(total, nested);
            double dx = playerX - (entry.x() + 0.5);
            double dy = playerY - (entry.y() + 0.5);
            double dz = playerZ - (entry.z() + 0.5);
            Source source = new Source(entry, total, total - nested, nested,
                    matchingShulkers, Math.sqrt(dx * dx + dy * dy + dz * dz));
            Source previous = byInventory.get(entry.inventoryKey());
            if (previous == null || entry.timestamp() > previous.entry().timestamp()) {
                byInventory.put(entry.inventoryKey(), source);
            }
        }
        List<Source> matches = new ArrayList<>(byInventory.values());
        matches.sort(Comparator.comparingInt(Source::total).reversed()
                .thenComparing(Comparator.comparingInt(Source::direct).reversed())
                .thenComparingDouble(Source::distance)
                .thenComparingInt(source -> source.entry().x())
                .thenComparingInt(source -> source.entry().y())
                .thenComparingInt(source -> source.entry().z()));
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }
}
