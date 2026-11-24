package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionCapacitySelectorTest {
    @Test
    void explicitStorageOverridesCurrentlyLoadedIntake() {
        var intake = region("farm", 0, DatabaseManager.RegionRole.INTAKE);
        var storage = region("tower", 100, DatabaseManager.RegionRole.STORAGE);

        var selected = RegionCapacitySelector.select(List.of(intake, storage),
                intake.pos1(), intake.pos2());

        assertEquals(RegionCapacitySelector.Result.SELECTED, selected.result());
        assertEquals("tower", selected.name());
        assertArrayEquals(storage.pos1(), selected.pos1());
    }

    @Test
    void unassignedSavedRegionIsStorageWhenNoExplicitStorageExists() {
        var storage = region("general", 100, DatabaseManager.RegionRole.UNASSIGNED);
        var selected = RegionCapacitySelector.select(List.of(storage), null, null);
        assertEquals("general", selected.name());
    }

    @Test
    void explicitScanOnlyAndIntakeDoNotBecomeStorage() {
        var selected = RegionCapacitySelector.select(List.of(
                region("view", 0, DatabaseManager.RegionRole.SCAN_ONLY),
                region("farm", 100, DatabaseManager.RegionRole.INTAKE)),
                new int[]{0, 64, 0}, new int[]{10, 70, 10});
        assertEquals(RegionCapacitySelector.Result.NO_STORAGE_REGION, selected.result());
    }

    @Test
    void multipleStorageRegionsRequireAnExplicitLoadedChoice() {
        var first = region("north", 0, DatabaseManager.RegionRole.STORAGE);
        var second = region("south", 100, DatabaseManager.RegionRole.STORAGE);
        assertEquals(RegionCapacitySelector.Result.AMBIGUOUS,
                RegionCapacitySelector.select(List.of(first, second), null, null).result());
        assertEquals("south", RegionCapacitySelector.select(List.of(first, second),
                second.pos2(), second.pos1()).name());
    }

    @Test
    void unsavedConfigurationRemainsSupported() {
        int[] pos1 = {0, 64, 0};
        int[] pos2 = {10, 70, 10};
        var selected = RegionCapacitySelector.select(List.of(), pos1, pos2);
        assertEquals(RegionCapacitySelector.Result.SELECTED, selected.result());
        assertArrayEquals(pos1, selected.pos1());
    }

    private static DatabaseManager.SavedRegion region(
            String name, int x, DatabaseManager.RegionRole role) {
        return new DatabaseManager.SavedRegion(name,
                new int[]{x, 64, 0}, new int[]{x + 10, 70, 10}, role);
    }
}
