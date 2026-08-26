package com.zenith.plugin.stashmanager.organizer.lane;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a physical storage block from the inventory representatives persisted by a scan.
 *
 * <p>A double chest is intentionally opened only once. Its indexed representative can therefore
 * be the canonical half while a hopper touches the other half. Canonical double-chest coordinates
 * are the minimum X/Z coordinate, so the unindexed physical half can only be one block east or
 * south of that identity. Exact observations always win; the footprint fallback is used only when
 * the contacted half was skipped by the scanner.</p>
 */
public final class IndexedStorageGeometry {
    private final Map<Long, ContainerEntry> exactByPosition = new HashMap<>();
    private final Map<Long, ContainerEntry> knownFootprintByPosition = new HashMap<>();
    private final Map<Long, ContainerEntry> doubleByInventory = new HashMap<>();

    public IndexedStorageGeometry(Collection<ContainerEntry> containers) {
        if (containers == null) return;
        for (ContainerEntry entry : containers) {
            if (!isPermanentStorage(entry)) continue;
            exactByPosition.merge(entry.posKey(), entry, IndexedStorageGeometry::freshest);
            if (entry.isDouble() && entry.inventoryIdentityKnown()) {
                doubleByInventory.merge(entry.inventoryKey(), entry, IndexedStorageGeometry::freshest);
                if (entry.doubleChestAxis() != null) {
                    knownFootprintByPosition.merge(
                            posKey(entry.inventoryX(), entry.inventoryY(), entry.inventoryZ()),
                            entry, IndexedStorageGeometry::freshest);
                    int partnerX = entry.inventoryX() + ("X".equals(entry.doubleChestAxis()) ? 1 : 0);
                    int partnerZ = entry.inventoryZ() + ("Z".equals(entry.doubleChestAxis()) ? 1 : 0);
                    knownFootprintByPosition.merge(
                            posKey(partnerX, entry.inventoryY(), partnerZ),
                            entry, IndexedStorageGeometry::freshest);
                }
            }
        }
    }

    /**
     * Finds the inventory occupying a physical block. The preferred axis disambiguates tightly
     * packed rows when both a western and northern canonical inventory are adjacent.
     */
    public ContainerEntry findAt(int x, int y, int z, int preferredDx, int preferredDz) {
        ContainerEntry exact = exactByPosition.get(posKey(x, y, z));
        if (exact != null) return exact;

        ContainerEntry knownFootprint = knownFootprintByPosition.get(posKey(x, y, z));
        if (knownFootprint != null) return knownFootprint;

        // Legacy rows predate persisted footprint axes. Keep their positive-half fallback
        // available for read-only compatibility; organizer safety requires a fresh scan before
        // those ambiguous rows can become movement destinations.
        ContainerEntry west = doubleByInventory.get(posKey(x - 1, y, z));
        ContainerEntry north = doubleByInventory.get(posKey(x, y, z - 1));
        if (preferredDx != 0) return west != null ? west : north;
        if (preferredDz != 0) return north != null ? north : west;
        return west != null ? west : north;
    }

    private static ContainerEntry freshest(ContainerEntry current, ContainerEntry candidate) {
        return candidate.timestamp() > current.timestamp() ? candidate : current;
    }

    private static boolean isPermanentStorage(ContainerEntry entry) {
        return entry != null && switch (entry.blockType()) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel" -> true;
            default -> false;
        };
    }

    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) y & 0xFFFL) << 26
                | ((long) z & 0x3FFFFFFL);
    }
}
