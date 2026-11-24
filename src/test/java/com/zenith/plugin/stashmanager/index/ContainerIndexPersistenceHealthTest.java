package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexPersistenceHealthTest {
    @Test
    void exposesFailedWriteAndRecoversAfterSuccessfulPersistence() {
        ContainerIndex index = new ContainerIndex();
        StubDatabase database = new StubDatabase();
        index.setDatabase(database);

        index.put(entry(1));

        assertFalse(index.isDatabaseWriteHealthy());
        assertEquals(1, index.getDatabaseWriteFailures());
        assertEquals(1, index.size(), "a database failure must not discard the in-memory scan");

        database.failWrites = false;
        index.put(entry(2));

        assertTrue(index.isDatabaseWriteHealthy());
        assertEquals(1, index.getDatabaseWriteFailures());
        assertEquals(2, index.size());
    }

    private static ContainerEntry entry(int x) {
        return new ContainerEntry(
            x, 64, 0, "minecraft:chest", false,
            Map.of("stone", 1), 0, List.of(), 1L, null, null);
    }

    private static final class StubDatabase extends DatabaseManager {
        private boolean failWrites = true;

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void upsertContainer(ContainerEntry entry) throws SQLException {
            if (failWrites) throw new SQLException("simulated write failure");
        }
    }
}
