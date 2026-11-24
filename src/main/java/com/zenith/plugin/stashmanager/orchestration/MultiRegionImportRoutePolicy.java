package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pins only registered, fully scanned intake inventories for a cross-region organize job. */
public final class MultiRegionImportRoutePolicy {
    private MultiRegionImportRoutePolicy() {}

    public record Selection(String blocker, List<StashOrganizer.IntakeRegion> intakes,
                            int importBlocks) {
        public boolean ready() { return blocker == null; }
    }

    public static Selection select(DatabaseManager.SavedRegion storage,
                                   List<DatabaseManager.SavedRegion> regions,
                                   ContainerIndex index) {
        if (index == null) return blocked("container index unavailable");
        return select(storage, regions, index.getImportChests(), index.getAll(),
                index.getBlacklistedChests(), index.getExportChests());
    }

    static Selection select(DatabaseManager.SavedRegion storage,
                            List<DatabaseManager.SavedRegion> regions,
                            List<int[]> registered,
                            Collection<ContainerEntry> indexed,
                            List<int[]> blacklisted,
                            List<int[]> exports) {
        if (storage == null || storage.role() != DatabaseManager.RegionRole.STORAGE
                || storage.pos1() == null || storage.pos2() == null) {
            return blocked("select a saved region with the storage role");
        }
        if (regions == null || registered == null || indexed == null
                || blacklisted == null || exports == null) return blocked("route data unavailable");
        Map<Long, ContainerEntry> indexedByPosition = indexed.stream()
                .collect(Collectors.toMap(ContainerEntry::posKey, Function.identity(),
                        (first, second) -> second));
        Set<Long> registeredKeys = registered.stream().map(MultiRegionImportRoutePolicy::key)
                .collect(Collectors.toSet());
        Set<Long> excludedKeys = new HashSet<>();
        blacklisted.forEach(pos -> excludedKeys.add(key(pos)));
        exports.forEach(pos -> excludedKeys.add(key(pos)));
        List<StashOrganizer.IntakeRegion> intakes = new ArrayList<>();
        Set<Long> claimedBlocks = new HashSet<>();
        for (DatabaseManager.SavedRegion region : regions) {
            if (region.role() != DatabaseManager.RegionRole.INTAKE) continue;
            List<int[]> blocks = registered.stream()
                    .filter(pos -> contains(region.pos1(), region.pos2(), pos)).toList();
            if (blocks.isEmpty()) continue;
            for (int[] pos : blocks) {
                if (contains(storage.pos1(), storage.pos2(), pos)) {
                    return blocked("intake " + region.name() + " overlaps the selected storage at a registered import");
                }
                if (!claimedBlocks.add(key(pos))) {
                    return blocked("registered import belongs to multiple intake regions at " + coordinates(pos));
                }
                ContainerEntry entry = indexedByPosition.get(key(pos));
                if (entry == null || !entry.inventoryFootprintKnown()
                        || !("minecraft:chest".equals(entry.blockType())
                                || "minecraft:trapped_chest".equals(entry.blockType())
                                || "minecraft:barrel".equals(entry.blockType()))
                        || excludedKeys.contains(key(pos))) {
                    return blocked("registered import is unscanned, excluded, or has unknown footprint at "
                            + coordinates(pos));
                }
                if (entry.isDouble()) {
                    List<ContainerEntry> halves = indexed.stream()
                            .filter(other -> other.isDouble() && other.inventoryFootprintKnown()
                                    && other.inventoryKey() == entry.inventoryKey()
                                    && other.blockType().equals(entry.blockType()))
                            .toList();
                    if (halves.size() != 2 || halves.stream().anyMatch(other ->
                            !contains(region.pos1(), region.pos2(),
                                    new int[]{other.x(), other.y(), other.z()})
                            || !registeredKeys.contains(other.posKey())
                            || excludedKeys.contains(other.posKey()))) {
                        return blocked("both halves of the import double chest must be scanned and registered "
                                + "in intake " + region.name() + " at " + coordinates(pos));
                    }
                }
            }
            intakes.add(new StashOrganizer.IntakeRegion(region.name(), region.pos1(),
                    region.pos2(), blocks));
        }
        if (intakes.isEmpty()) return blocked("no intake region has registered import chests");
        return new Selection(null, List.copyOf(intakes), claimedBlocks.size());
    }

    private static Selection blocked(String reason) {
        return new Selection(reason, List.of(), 0);
    }

    private static boolean contains(int[] first, int[] second, int[] pos) {
        if (first == null || second == null || first.length != 3 || second.length != 3) return false;
        for (int axis = 0; axis < 3; axis++) {
            if (pos[axis] < Math.min(first[axis], second[axis])
                    || pos[axis] > Math.max(first[axis], second[axis])) return false;
        }
        return true;
    }

    private static long key(int[] pos) {
        return ((long) pos[0] & 0x3FFFFFFL) << 38
                | ((long) pos[1] & 0xFFFL) << 26 | ((long) pos[2] & 0x3FFFFFFL);
    }

    private static String coordinates(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }
}
