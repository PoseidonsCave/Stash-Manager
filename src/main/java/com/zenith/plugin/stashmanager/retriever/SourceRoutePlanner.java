package com.zenith.plugin.stashmanager.retriever;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Orders already eligible sources by estimated travel per useful unit. */
final class SourceRoutePlanner {
    private static final double ACCESS_OVERHEAD_BLOCKS = 12.0;

    private SourceRoutePlanner() { }

    static List<ContainerEntry> order(List<ContainerEntry> candidates, double startX,
                                      double startY, double startZ,
                                      ToIntFunction<ContainerEntry> usefulUnits,
                                      ToIntFunction<ContainerEntry> directUnits,
                                      Set<Long> laneOutputKeys,
                                      Set<Long> recentlyExhaustedKeys) {
        record Ranked(ContainerEntry entry, double cost) { }
        List<Ranked> ranked = new ArrayList<>(candidates.size());
        for (ContainerEntry entry : candidates) {
            int useful = usefulUnits.applyAsInt(entry);
            if (useful <= 0) {
                ranked.add(new Ranked(entry, Double.POSITIVE_INFINITY));
                continue;
            }
            double dx = startX - entry.x(), dy = startY - entry.y(), dz = startZ - entry.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double cost = (distance + ACCESS_OVERHEAD_BLOCKS) / useful;
            if (directUnits.applyAsInt(entry) > 0) cost *= 0.90;
            if (laneOutputKeys.contains(entry.posKey())) cost *= 0.90;
            // Keep observed misses available as a fallback if all other indexed
            // candidates fail; do not repeatedly visit them first on the next kit.
            if (recentlyExhaustedKeys.contains(entry.posKey())) cost += 1_000;
            ranked.add(new Ranked(entry, cost));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::cost));
        return ranked.stream().map(Ranked::entry).toList();
    }
}
