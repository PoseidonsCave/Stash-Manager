package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexChestRoleTest {
    @Test
    void protectedAndExportRolesPersistBothDoubleChestBlocks() throws Exception {
        FakeDatabase database = new FakeDatabase();
        ContainerIndex index = new ContainerIndex();
        index.setDatabase(database);
        List<int[]> protectedChest = List.of(new int[]{1, 64, 1}, new int[]{2, 64, 1});
        List<int[]> exportChest = List.of(new int[]{5, 64, 5}, new int[]{6, 64, 5});

        index.addBlacklistedChests(protectedChest);
        index.addExportChests(exportChest);

        ContainerIndex reloaded = new ContainerIndex();
        reloaded.setDatabase(database);
        reloaded.loadFromDatabase();
        assertTrue(reloaded.isBlacklistedChest(1, 64, 1));
        assertTrue(reloaded.isBlacklistedChest(2, 64, 1));
        assertTrue(reloaded.isExportChest(5, 64, 5));
        assertTrue(reloaded.isExportChest(6, 64, 5));
        assertEquals(2, reloaded.getBlacklistedChestBlockCount());
        assertEquals(2, reloaded.getExportChestBlockCount());

        reloaded.removeBlacklistedChests(protectedChest);
        reloaded.removeExportChests(exportChest);
        assertFalse(reloaded.isBlacklistedChest(1, 64, 1));
        assertFalse(reloaded.isExportChest(5, 64, 5));
    }

    private static final class FakeDatabase extends DatabaseManager {
        private final List<int[]> blacklist = new ArrayList<>();
        private final List<int[]> exports = new ArrayList<>();

        @Override public boolean isInitialized() { return true; }
        @Override public List<ContainerEntry> getAllContainers() { return List.of(); }
        @Override public List<int[]> loadImportChests() { return List.of(); }
        @Override public List<int[]> loadBlacklistedChests() { return copies(blacklist); }
        @Override public List<int[]> loadExportChests() { return copies(exports); }
        @Override public void addBlacklistedChests(Collection<int[]> positions) { add(blacklist, positions); }
        @Override public void removeBlacklistedChests(Collection<int[]> positions) { remove(blacklist, positions); }
        @Override public void addExportChests(Collection<int[]> positions) { add(exports, positions); }
        @Override public void removeExportChests(Collection<int[]> positions) { remove(exports, positions); }

        private static void add(List<int[]> target, Collection<int[]> positions) {
            for (int[] position : positions) {
                if (target.stream().noneMatch(current -> same(current, position))) target.add(position.clone());
            }
        }

        private static void remove(List<int[]> target, Collection<int[]> positions) {
            target.removeIf(current -> positions.stream().anyMatch(position -> same(current, position)));
        }

        private static List<int[]> copies(List<int[]> source) {
            return source.stream().map(int[]::clone).toList();
        }

        private static boolean same(int[] left, int[] right) {
            return left[0] == right[0] && left[1] == right[1] && left[2] == right[2];
        }
    }
}
