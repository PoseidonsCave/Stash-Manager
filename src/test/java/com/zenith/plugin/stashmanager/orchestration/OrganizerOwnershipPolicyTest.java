package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizerOwnershipPolicyTest {
    @Test
    void standaloneUnregisteredChestIsOutsideOrganizerOwnership() {
        assertFalse(OrganizerOwnershipPolicy.isManagedSource(false, false));
        assertFalse(OrganizerOwnershipPolicy.isDestination(false, false));
    }

    @Test
    void importChestCanSupplyAndTemporarilyStageButIsNotPermanentLane() {
        assertTrue(OrganizerOwnershipPolicy.isManagedSource(false, true));
        assertFalse(OrganizerOwnershipPolicy.isDestination(false, true));
        assertTrue(OrganizerOwnershipPolicy.isReconciliationStagingDestination(true, false));
        assertFalse(OrganizerOwnershipPolicy.isReconciliationStagingDestination(true, true));
    }

    @Test
    void laneInventoryIsManagedDestinationUnlessMarkedAsImport() {
        assertTrue(OrganizerOwnershipPolicy.isManagedSource(true, false));
        assertTrue(OrganizerOwnershipPolicy.isDestination(true, false));
        assertTrue(OrganizerOwnershipPolicy.isManagedSource(true, true));
        assertFalse(OrganizerOwnershipPolicy.isDestination(true, true));
    }
}
