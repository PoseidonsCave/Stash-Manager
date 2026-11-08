package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportCapacityBackpressureTest {
    @Test
    void stopsOnlyAfterEveryRegisteredInventoryWasRejected() {
        assertFalse(ImportCapacityBackpressure.stopBeforeRepack(
                List.of(1L, 2L, 3L), Set.of(1L, 2L), false));
        assertTrue(ImportCapacityBackpressure.stopBeforeRepack(
                List.of(1L, 2L, 3L), Set.of(1L, 2L, 3L), false));
    }

    @Test
    void deduplicatesDoubleChestAliasesAndPreservesLaneBackedRecovery() {
        assertTrue(ImportCapacityBackpressure.stopBeforeRepack(
                List.of(1L, 1L, 2L), Set.of(1L, 2L), false));
        assertFalse(ImportCapacityBackpressure.stopBeforeRepack(
                List.of(1L, 2L), Set.of(1L, 2L), true));
        assertFalse(ImportCapacityBackpressure.stopBeforeRepack(
                List.of(), Set.of(), false));
    }
}
