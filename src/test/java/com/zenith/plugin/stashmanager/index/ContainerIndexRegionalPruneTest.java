package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerIndexRegionalPruneTest {
    @Test
    void removesOnlyUnseenRowsInsideCompletedScanRegion() throws Exception {
        DatabaseManager database = new InMemoryDeleteDatabase();

        ContainerIndex index = new ContainerIndex();
        index.put(entry(1, 64, 1));
        index.put(entry(2, 64, 2));
        index.put(entry(50, 64, 50));
        index.setDatabase(database);

        int pruned = index.pruneRegionExcept(
                new int[]{0, 0, 0}, new int[]{10, 100, 10},
                Set.of(index.get(1, 64, 1).posKey()));

        assertEquals(1, pruned);
        assertNotNull(index.get(1, 64, 1));
        assertNull(index.get(2, 64, 2));
        assertNotNull(index.get(50, 64, 50));
    }

    private static ContainerEntry entry(int x, int y, int z) {
        return new ContainerEntry(x, y, z, "minecraft:chest", false,
                Map.of(), 0, List.of(), 1L);
    }

    private static final class InMemoryDeleteDatabase extends DatabaseManager {
        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void deleteContainers(java.util.Collection<ContainerEntry> containers) {
            // Persistence behavior is covered by the database integration; this test verifies
            // regional selection and cache mutation after a successful durable delete.
        }
    }
}
