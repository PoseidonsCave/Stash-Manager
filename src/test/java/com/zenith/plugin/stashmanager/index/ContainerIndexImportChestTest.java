package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexImportChestTest {
    @Test
    void doubleChestAssignmentPersistsBothBlocksAndCanBeRemovedAtomically() throws Exception {
        var database = new FakeDatabase();
        var index = new ContainerIndex();
        index.setDatabase(database);

        List<int[]> doubleChest = List.of(new int[]{10, 64, 20}, new int[]{11, 64, 20});
        index.addImportChests(doubleChest);

        assertTrue(index.isImportChest(10, 64, 20));
        assertTrue(index.isImportChest(11, 64, 20));
        assertEquals(2, index.getImportChestBlockCount());

        var reloaded = new ContainerIndex();
        reloaded.setDatabase(database);
        reloaded.loadFromDatabase();
        assertTrue(reloaded.isImportChest(10, 64, 20));
        assertTrue(reloaded.isImportChest(11, 64, 20));

        reloaded.removeImportChests(doubleChest);
        assertFalse(reloaded.isImportChest(10, 64, 20));
        assertFalse(reloaded.isImportChest(11, 64, 20));
        assertEquals(0, database.positions.size());
    }

    @Test
    void purgeClearsPersistenceAndCacheTogether() throws Exception {
        var database = new FakeDatabase();
        var index = new ContainerIndex();
        index.setDatabase(database);
        index.addImportChests(List.of(new int[]{10, 64, 20}, new int[]{11, 64, 20}));

        assertEquals(2, index.clearImportChests());
        assertEquals(0, index.getImportChestBlockCount());
        assertEquals(0, database.positions.size());
    }

    private static final class FakeDatabase extends DatabaseManager {
        private final List<int[]> positions = new ArrayList<>();

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public List<ContainerEntry> getAllContainers() {
            return List.of();
        }

        @Override
        public void addImportChests(Collection<int[]> additions) {
            for (int[] addition : additions) {
                boolean exists = positions.stream().anyMatch(pos -> samePosition(pos, addition));
                if (!exists) positions.add(addition.clone());
            }
        }

        @Override
        public void removeImportChests(Collection<int[]> removals) {
            positions.removeIf(pos -> removals.stream().anyMatch(removal -> samePosition(pos, removal)));
        }

        @Override
        public List<int[]> loadImportChests() throws SQLException {
            return positions.stream().map(int[]::clone).toList();
        }

        @Override
        public int clearImportChests() {
            int removed = positions.size();
            positions.clear();
            return removed;
        }

        private static boolean samePosition(int[] first, int[] second) {
            return first[0] == second[0] && first[1] == second[1] && first[2] == second[2];
        }
    }
}
