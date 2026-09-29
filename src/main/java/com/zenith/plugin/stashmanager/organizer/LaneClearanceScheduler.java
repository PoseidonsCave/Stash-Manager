package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Drain foreign stock before admitting deliveries to a reassigned lane. */
final class LaneClearanceScheduler {
    private final Map<Long, String> owners = new HashMap<>();
    private final Map<Long, Long> inventories = new HashMap<>();
    private final Set<String> blockedClasses = new HashSet<>();

    LaneClearanceScheduler(Map<String, StashOrganizer.Column> assignments,
                           Collection<ContainerEntry> containers,
                           Collection<StashOrganizer.MoveTask> pending) {
        for (ContainerEntry entry : containers) {
            long key = entry.inventoryIdentityKnown() ? entry.inventoryKey() : entry.posKey();
            inventories.put(entry.posKey(), key);
            if (entry.isDouble() && entry.inventoryIdentityKnown() && entry.doubleChestAxis() != null) {
                inventories.put(key(entry.inventoryX(), entry.inventoryY(), entry.inventoryZ()), key);
                inventories.put(key(entry.inventoryX() + ("X".equals(entry.doubleChestAxis()) ? 1 : 0),
                        entry.inventoryY(), entry.inventoryZ() + ("Z".equals(entry.doubleChestAxis()) ? 1 : 0)), key);
            }
        }
        assignments.forEach((storageClass, lane) -> {
            for (int[] chest : lane.chests()) {
                String previous = owners.putIfAbsent(inventory(chest), storageClass);
                if (previous != null && !previous.equals(storageClass)) {
                    blockedClasses.add(previous);
                    blockedClasses.add(storageClass);
                }
            }
        });
        for (StashOrganizer.MoveTask task : pending) {
            if (clearsForeignStock(task)) blockedClasses.add(owners.get(inventory(task.source())));
        }
    }

    boolean ready(StashOrganizer.MoveTask task) {
        if (task.alreadyInInventory()) return true;
        String owner = owners.get(inventory(task.destination()));
        return owner == null || (!blockedClasses.contains(owner) && !blockedClasses.contains(storageClass(task)));
    }

    boolean clearsForeignStock(StashOrganizer.MoveTask task) {
        if (task.alreadyInInventory()) return false;
        String owner = owners.get(inventory(task.source()));
        return owner != null && (task.mixedDecomposition()
                || !ItemIdentifier.contentItemIdsMatch(owner, storageClass(task)));
    }

    private static String storageClass(StashOrganizer.MoveTask task) {
        return task.shulkerContentFilter() == null ? task.itemId() : task.shulkerContentFilter();
    }

    private long inventory(int[] position) {
        if (position == null || position.length != 3) return Long.MIN_VALUE;
        long key = key(position[0], position[1], position[2]);
        return inventories.getOrDefault(key, key);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }
}
