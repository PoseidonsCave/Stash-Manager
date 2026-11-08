package com.zenith.plugin.stashmanager.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionRoleTest {
    @Test void omittedRoleIsAllPurposeButUnknownAndLegacyRolesStayScanOnly() {
        assertEquals(DatabaseManager.RegionRole.UNASSIGNED,
                DatabaseManager.RegionRole.fromDatabase(null));
        assertEquals(DatabaseManager.RegionRole.SCAN_ONLY,
                DatabaseManager.RegionRole.fromDatabase("unexpected"));
        assertEquals(DatabaseManager.RegionRole.UNASSIGNED,
                new DatabaseManager.SavedRegion("legacy", new int[3], new int[3]).role());
        assertEquals(DatabaseManager.RegionRole.SCAN_ONLY,
                DatabaseManager.RegionRole.fromDatabase("scan_only"));
        assertEquals(true, DatabaseManager.RegionRole.UNASSIGNED.isStorageCapable());
        assertEquals(false, DatabaseManager.RegionRole.INTAKE.isStorageCapable());
    }

    @Test void explicitRolesRoundTrip() {
        for (var role : DatabaseManager.RegionRole.values()) {
            assertEquals(role, DatabaseManager.RegionRole.fromDatabase(role.databaseValue()));
        }
    }
}
