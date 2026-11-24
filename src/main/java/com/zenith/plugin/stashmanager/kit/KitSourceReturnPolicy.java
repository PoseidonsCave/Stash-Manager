package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects scanned, capacity-eligible, user-registered imports for a borrowed kit source box. */
public final class KitSourceReturnPolicy {
    private KitSourceReturnPolicy() { }

    public static List<int[]> capacityImports(List<ContainerEntry> scanned,
                                           List<int[]> registeredImports,
                                           Set<Long> excludedBlocks,
                                           int[] regionPos1, int[] regionPos2,
                                           int[] sourcePosition, int[] exportPosition) {
        if (scanned == null || registeredImports == null || excludedBlocks == null
                || !valid(regionPos1) || !valid(regionPos2)
                || !valid(exportPosition)) return List.of();
        Set<Long> registered = new HashSet<>();
        for (int[] pos : registeredImports) if (valid(pos)) registered.add(key(pos));
        Set<Long> disallowedInventories = new HashSet<>();
        for (ContainerEntry entry : scanned) {
            if (entry == null) continue;
            if ((sourcePosition != null && Arrays.equals(position(entry), sourcePosition))
                    || Arrays.equals(position(entry), exportPosition)
                    || excludedBlocks.contains(entry.posKey())) {
                disallowedInventories.add(entry.inventoryKey());
            }
        }
        return scanned.stream()
                .filter(entry -> entry != null && entry.inventoryFootprintKnown())
                .filter(entry -> eligibleContainer(entry))
                // The scan is advisory for candidate selection. The opened server
                // window must independently prove a free slot before any click.
                .filter(KitSourceReturnPolicy::hasIndexedCapacity)
                .filter(entry -> registered.contains(entry.posKey())
                        && !excludedBlocks.contains(entry.posKey())
                        && !disallowedInventories.contains(entry.inventoryKey()))
                .filter(entry -> contains(regionPos1, regionPos2, position(entry)))
                .filter(entry -> registeredFootprint(entry, registered, excludedBlocks,
                        regionPos1, regionPos2))
                .collect(java.util.stream.Collectors.toMap(ContainerEntry::inventoryKey,
                        entry -> entry, (first, second) -> first))
                .values().stream()
                .sorted(Comparator.comparingLong((ContainerEntry entry) -> distance(
                                position(entry), sourcePosition == null
                                        ? exportPosition : sourcePosition))
                        .thenComparingInt(ContainerEntry::x)
                        .thenComparingInt(ContainerEntry::y)
                        .thenComparingInt(ContainerEntry::z))
                .map(KitSourceReturnPolicy::position)
                .toList();
    }

    /** An operator-selected import may have stale indexed contents, but its role and
     * physical inventory footprint must already be known. The opened server window
     * must independently prove free capacity before any return transfer is submitted. */
    public static boolean registeredImportForLiveCapacityAudit(List<ContainerEntry> scanned,
            List<int[]> registeredImports, Set<Long> excludedBlocks,
            int[] regionPos1, int[] regionPos2, int[] sourcePosition,
            int[] exportPosition, int[] targetPosition) {
        if (scanned == null || registeredImports == null || excludedBlocks == null
                || !valid(regionPos1) || !valid(regionPos2)
                || !valid(exportPosition)
                || !valid(targetPosition)) return false;
        Set<Long> registered = new HashSet<>();
        for (int[] pos : registeredImports) if (valid(pos)) registered.add(key(pos));
        if (!registered.contains(key(targetPosition))
                || excludedBlocks.contains(key(targetPosition))
                || !contains(regionPos1, regionPos2, targetPosition)) return false;
        ContainerEntry target = scanned.stream()
                .filter(entry -> entry != null && Arrays.equals(position(entry), targetPosition))
                .findFirst().orElse(null);
        if (target == null || !target.inventoryFootprintKnown()
                || !eligibleContainer(target)
                || !registeredFootprint(target, registered, excludedBlocks,
                        regionPos1, regionPos2)) return false;
        return scanned.stream().filter(entry -> entry != null)
                .filter(entry -> (sourcePosition != null
                        && Arrays.equals(position(entry), sourcePosition))
                        || Arrays.equals(position(entry), exportPosition)
                        || excludedBlocks.contains(entry.posKey()))
                .noneMatch(entry -> entry.inventoryKey() == target.inventoryKey());
    }

    public static int[] nextAfter(List<int[]> candidates, int[] previous) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (previous == null) return candidates.getFirst().clone();
        for (int i = 0; i < candidates.size(); i++) {
            if (Arrays.equals(candidates.get(i), previous)) {
                return i + 1 < candidates.size() ? candidates.get(i + 1).clone() : null;
            }
        }
        // A fresh index may have removed the now-full previous target. In that
        // case no candidate equals it, so the first remaining one is untried.
        return candidates.getFirst().clone();
    }

    private static boolean registeredFootprint(ContainerEntry entry, Set<Long> registered,
                                               Set<Long> excluded, int[] first, int[] second) {
        if (!entry.isDouble()) return true;
        int[] partner = new int[]{entry.inventoryX(), entry.inventoryY(), entry.inventoryZ()};
        if ("X".equals(entry.doubleChestAxis())) partner[0]++;
        else if ("Z".equals(entry.doubleChestAxis())) partner[2]++;
        else return false;
        return registered.contains(key(partner)) && !excluded.contains(key(partner))
                && contains(first, second, partner);
    }

    private static boolean eligibleContainer(ContainerEntry entry) {
        return "minecraft:chest".equals(entry.blockType())
                || "minecraft:trapped_chest".equals(entry.blockType())
                || "minecraft:barrel".equals(entry.blockType());
    }

    static boolean hasIndexedCapacity(ContainerEntry entry) {
        int occupied = entry.shulkerCount()
                + entry.directStackSlots().values().stream().mapToInt(Integer::intValue).sum();
        // Legacy rows without direct physical slot counts cannot establish capacity.
        if (entry.totalItems() > 0 && occupied == 0) return false;
        return occupied < (entry.isDouble() ? 54 : 27);
    }

    public static boolean hasLiveCapacity(int occupiedSlots, int containerSlots) {
        return (containerSlots == 27 || containerSlots == 54)
                && occupiedSlots >= 0 && occupiedSlots < containerSlots;
    }

    private static boolean contains(int[] first, int[] second, int[] position) {
        for (int axis = 0; axis < 3; axis++) {
            if (position[axis] < Math.min(first[axis], second[axis])
                    || position[axis] > Math.max(first[axis], second[axis])) return false;
        }
        return true;
    }

    private static long distance(int[] first, int[] second) {
        return Math.abs((long) first[0] - second[0])
                + Math.abs((long) first[1] - second[1])
                + Math.abs((long) first[2] - second[2]);
    }

    private static int[] position(ContainerEntry entry) {
        return new int[]{entry.x(), entry.y(), entry.z()};
    }

    private static boolean valid(int[] position) {
        return position != null && position.length == 3;
    }

    private static long key(int[] position) {
        return ((long) position[0] & 0x3FFFFFFL) << 38
                | ((long) position[1] & 0xFFFL) << 26
                | ((long) position[2] & 0x3FFFFFFL);
    }
}
