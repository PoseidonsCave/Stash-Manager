package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingFillAdmissionTest {
    private static final int[] MIN = {0, 60, 0};
    private static final int[] MAX = {20, 70, 20};

    private static final class Database extends DatabaseManager {
        ScanSummary scan = new ScanSummary(3, 3, 0, "complete", 100);
        boolean identity = true;
        List<ContainerEntry> entries = List.of(
                new ContainerEntry(1, 64, 1, "minecraft:chest", false,
                        Map.of("redstone", 64), 1,
                        List.of(new ContainerEntry.ShulkerDetail(2, "blue",
                                Map.of("redstone", 64))), 101),
                new ContainerEntry(10, 64, 2, "minecraft:chest", true,
                        Map.of(), 0, List.of(), 101, null, null,
                        10, 64, 2, true, "X"),
                new ContainerEntry(11, 64, 2, "minecraft:chest", true,
                        Map.of(), 0, List.of(), 101, null, null,
                        10, 64, 2, true, "X"));
        List<KitWorkingSlotRow> slots = List.of(new KitWorkingSlotRow(
                10, 64, 2, 4, "redstone", "redstone", 64, "pending", null));
        @Override public boolean isInitialized() { return true; }
        @Override public Optional<ScanSummary> getLatestScanSummary(int[] a, int[] b) {
            return Optional.of(scan);
        }
        @Override public boolean latestScanHasFullItemIdentity(int[] a, int[] b) {
            return identity;
        }
        @Override public List<KitWorkingSlotRow> loadKitWorkingSlots() { return slots; }
        @Override public List<ContainerEntry> getAllContainers() { return entries; }
        @Override public List<int[]> loadImportChests() { return List.of(); }
        @Override public List<int[]> loadBlacklistedChests() { return List.of(); }
        @Override public List<int[]> loadExportChests() { return List.of(); }
        @Override public List<int[]> loadKitWorkingChests() {
            return List.of(new int[]{10, 64, 2}, new int[]{11, 64, 2});
        }
        @Override public List<int[]> loadCraftingTables() { return List.of(); }
    }

    private static KitWorkingTransferJournal.Ticket ticket() {
        return new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", 64,
                new int[]{1, 64, 1}, 2, new int[]{10, 64, 2}, 4,
                "a".repeat(64), KitWorkingTransferJournal.Phase.PREPARED, 1, null);
    }

    private static ContainerIndex index(Database database) {
        ContainerIndex index = new ContainerIndex();
        index.setDatabase(database);
        assertEquals(3, index.loadFromDatabase());
        return index;
    }

    @Test void acceptsOnlyExactPendingSlotFromTrustedCompleteScan() throws Exception {
        var database = new Database();
        assertNull(KitWorkingFillAdmission.blocker(database, index(database),
                MIN, MAX, ticket(), true));
    }

    @Test void rejectsPartialScanAndUnverifiedSourceBox() throws Exception {
        var database = new Database();
        var index = index(database);
        database.scan = new DatabaseManager.ScanSummary(3, 2, 1, "partial", 100);
        assertEquals("complete current-identity scan required",
                KitWorkingFillAdmission.blocker(database, index, MIN, MAX, ticket(), true));
        database.scan = new DatabaseManager.ScanSummary(3, 3, 0, "complete", 100);
        database.identity = false;
        assertEquals("complete current-identity scan required",
                KitWorkingFillAdmission.blocker(database, index, MIN, MAX, ticket(), true));
        database.identity = true;
        var wrongSlot = new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", 64,
                new int[]{1, 64, 1}, 3, new int[]{10, 64, 2}, 4,
                "a".repeat(64), KitWorkingTransferJournal.Phase.PREPARED, 1, null);
        assertEquals("exact source box is absent from the current index",
                KitWorkingFillAdmission.blocker(database, index, MIN, MAX, wrongSlot, true));
    }

    @Test void rejectsReassignedOrAlreadyActiveDestination() throws Exception {
        var database = new Database();
        var index = index(database);
        database.slots = List.of(new DatabaseManager.KitWorkingSlotRow(
                10, 64, 2, 4, "redstone", "redstone", 64, "active", "a".repeat(64)));
        assertEquals("destination slot is not reserved for this exact box",
                KitWorkingFillAdmission.blocker(database, index, MIN, MAX, ticket(), true));
    }
}
