package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingCapacityRecoveryTest {
    @Test
    void mixedStagingCapacitySearchUsesABoundedLiveProbeBudget() {
        assertTrue(StashOrganizer.shouldContinueMixedStagingCapacitySearch(0, 8));
        assertTrue(StashOrganizer.shouldContinueMixedStagingCapacitySearch(7, 8));
        assertFalse(StashOrganizer.shouldContinueMixedStagingCapacitySearch(8, 8));
        assertFalse(StashOrganizer.shouldContinueMixedStagingCapacitySearch(9, 8));
    }

    @Test
    void onlyCapacityFailuresBecomeUserResumableStops() {
        assertTrue(StashOrganizer.isResumableStagingCapacityFailure("mixed_staging_full"));
        assertTrue(StashOrganizer.isResumableStagingCapacityFailure("mixed_staging_probe_budget_exhausted"));
        assertTrue(StashOrganizer.isResumableStagingCapacityFailure(
                "mixed_staging_destination_missing"));
        assertFalse(StashOrganizer.isResumableStagingCapacityFailure(
                "mixed_stack_transfer_unverified"));
        assertTrue(StashOrganizer.isResumableMixedRecoveryFailure(
                "mixed_stack_transfer_unverified"));
        assertTrue(StashOrganizer.isResumableMixedRecoveryFailure("mixed_staging_full"));
        assertFalse(StashOrganizer.isResumableMixedRecoveryFailure("mixed_shulker_container_lost"));
    }

    @Test
    void resumePhasePreservesTheCurrentMixedTransaction() {
        assertEquals(StashOrganizer.State.MIXED_STAGE_WALK,
                StashOrganizer.stagingCapacityResumeState(true, false));
        assertEquals(StashOrganizer.State.MIXED_RETURN_WALK,
                StashOrganizer.stagingCapacityResumeState(false, true));
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,
                StashOrganizer.stagingCapacityResumeState(false, false));
    }
}
