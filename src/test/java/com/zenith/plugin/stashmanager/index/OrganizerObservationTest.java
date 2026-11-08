package com.zenith.plugin.stashmanager.index;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.scanner.ContainerReader;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrganizerObservationTest {
    @Test
    void observesSourceAndDestinationWithoutCountingPlayerInventoryOrChangingGeometry() {
        ContainerIndex index = new ContainerIndex();
        StubDatabase db = new StubDatabase();
        index.setDatabase(db);
        index.put(chest(1, "source left"));
        index.put(chest(2, "source right"));
        long scanTime = index.getLastScanTimestamp();
        db.writes.clear();

        Container window = new Container(73, 90);
        window.setItemStack(89, item("diamond_boots", 1));
        index.recordInventoryObservation(ContainerReader.snapshotContents(window, index.get(1, 64, 0), 100));
        assertEquals(2, db.writes.size());
        assertTrue(index.get(1, 64, 0).items().isEmpty());
        assertTrue(index.get(2, 64, 0).items().isEmpty());
        assertEquals("source right", index.get(2, 64, 0).label());
        assertEquals("X", index.get(2, 64, 0).doubleChestAxis());
        assertEquals(scanTime, index.getLastScanTimestamp());

        window.setItemStack(51, box());
        index.recordInventoryObservation(ContainerReader.snapshotContents(window, index.get(2, 64, 0), 200));
        assertEquals(4, db.writes.size());
        assertEquals(1, index.get(1, 64, 0).shulkerCount());
        assertEquals(27, index.get(1, 64, 0).items().get("diamond_boots"));
        assertEquals(51, index.get(1, 64, 0).shulkerDetails().getFirst().slot());
        assertEquals(27, index.get(1, 64, 0).shulkerDetails().getFirst()
                .stackSlots().get("diamond_boots"));
        assertEquals(200, index.getLastInventoryObservation());
        assertTrue(index.getDatabaseLastWriteSuccess() > 0);
    }

    @Test
    void countsDirectPhysicalStacksWithoutCreatingNameBasedItemClasses() {
        Container window = new Container(73, 90);
        window.setItemStack(0, item("iron_block", 1));
        window.setItemStack(1, item("iron_block", 1));
        var observed = ContainerReader.snapshotContents(window, chest(1, "import"), 100);
        assertEquals(Map.of("iron_block", 2), observed.items());
        assertEquals(Map.of("iron_block", 2), observed.directStackSlots());
        assertEquals(observed.directStackSlots(), observed.withLabel("renamed").directStackSlots());
    }

    @Test
    void unknownWorksiteObservationCannotInventAnIndexedContainer() {
        ContainerIndex index = new ContainerIndex();
        index.recordInventoryObservation(chest(5, "temporary worksite"));
        assertEquals(0, index.size());
        assertEquals(0, index.getLastInventoryObservation());
        assertEquals(0, index.getDatabaseLastWriteAttempt());
        assertEquals(0, index.getDatabaseLastWriteSuccess());
    }

    @Test
    void failedWriteKeepsLiveObservationButDoesNotReportFreshPersistence() {
        ContainerIndex index = new ContainerIndex();
        index.put(chest(1, "source"));
        StubDatabase db = new StubDatabase();
        db.fail = true;
        index.setDatabase(db);
        index.recordInventoryObservation(chest(1, "source"));
        assertFalse(index.isDatabaseWriteHealthy());
        assertEquals(1, index.getDatabaseWriteFailures());
        assertTrue(index.getDatabaseLastWriteAttempt() > 0);
        assertEquals(0, index.getDatabaseLastWriteSuccess());
    }

    private static ContainerEntry chest(int x, String label) {
        return new ContainerEntry(x, 64, 0, "minecraft:chest", true,
                Map.of("stone", 64), 0, List.of(), 1, label, null,
                1, 64, 0, true, "X");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box() {
        var components = new DataComponents(new HashMap<>());
        var items = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) items.add(item("diamond_boots", 1));
        components.put(DataComponentTypes.CONTAINER, (List) items);
        return new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1, components);
    }

    private static ItemStack item(String id, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), amount);
    }

    private static class StubDatabase extends DatabaseManager {
        final List<ContainerEntry> writes = new ArrayList<>();
        boolean fail;
        @Override public boolean isInitialized() { return true; }
        @Override public void upsertContainer(ContainerEntry entry) throws SQLException {
            if (fail) throw new SQLException("simulated outage");
            writes.add(entry);
        }
    }
}
