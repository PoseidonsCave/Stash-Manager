package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRecoveryGuardTest {
    @Test
    void stopsBeforeStartingAFourthIdenticalRecovery() {
        var guard = exhausted();
        assertFalse(guard.allowRecovery("mixed-box", 23, 1));
        assertEquals(3, guard.snapshot().attempts());
    }

    @Test
    void returningToTheSameInventoryDoesNotResetTheBudget() {
        var guard = new InventoryRecoveryGuard();
        assertTrue(guard.allowRecovery("mixed-box", 23, 1));
        assertTrue(guard.allowRecovery("mixed-box", 24, 0));
        assertTrue(guard.allowRecovery("mixed-box", 23, 1));
        assertFalse(guard.allowRecovery("mixed-box", 24, 0));
    }

    @Test
    void actuallyReducingCargoAllowsFurtherRecovery() {
        var guard = exhausted();
        assertTrue(guard.allowRecovery("mixed-box", 22, 2));
        assertEquals(1, guard.snapshot().attempts());
    }

    @Test
    void eachNewMixedSourceHasItsOwnBudget() {
        assertTrue(exhausted().allowRecovery("different-mixed-box", 23, 1));
    }

    @Test
    void verifiedPackingProgressAllowsAnotherRecovery() {
        var guard = exhausted();
        guard.reset();
        assertTrue(guard.allowRecovery("mixed-box", 23, 1));
    }

    @Test
    void restartDoesNotEraseTheBudget() {
        var original = exhausted();
        var restored = new InventoryRecoveryGuard();
        restored.restore(original.snapshot());
        assertFalse(restored.allowRecovery("mixed-box", 23, 1));
    }

    @Test
    void olderCheckpointsStartWithAFreshBudget() {
        var guard = exhausted();
        guard.restore(null);
        assertTrue(guard.allowRecovery("mixed-box", 23, 1));
    }

    private InventoryRecoveryGuard exhausted() {
        var guard = new InventoryRecoveryGuard();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(guard.allowRecovery("mixed-box", 23, 1));
        }
        return guard;
    }
}
