package com.zenith.plugin.stashmanager.organizer;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Prefer recent usable inventories; retain stale/full ones for a final live recheck. */
final class ImportCapacityCache {
    private static final long FRESH_MILLIS = 5 * 60_000L;
    private record Observation(ContainerCapacitySnapshot capacity, long observedAt) {}
    private final Map<Long, Observation> inventories = new HashMap<>();

    void reset() { inventories.clear(); }

    void record(long key, ContainerCapacitySnapshot capacity, long now) {
        inventories.put(key, new Observation(capacity, now));
    }

    int rank(long key, ItemStack cargo, boolean needsEmptySlot, long now) {
        Observation observation = inventories.get(key);
        if (observation == null || now - observation.observedAt() >= FRESH_MILLIS
                || now < observation.observedAt() || (cargo == null && !needsEmptySlot)) return 1;
        boolean usable = needsEmptySlot ? observation.capacity().emptySlots() > 0
                : observation.capacity().accepts(cargo);
        return usable ? 0 : 2;
    }
}
