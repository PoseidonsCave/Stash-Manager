package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodContingencyRetryPolicyTest {
    private static final String TEMPORARY_BOX_BLOCKER =
            "food refill is blocked (organizer_temporary_shulker_unresolved); "
                    + "it will retry when the bot is back near the stash";

    @Test
    void exactAttestedBoxMayReleaseItsOwnFoodGate() {
        assertTrue(FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                845, TEMPORARY_BOX_BLOCKER, true,
                "organizer_temporary_shulker_unresolved", 0));
    }

    @Test
    void doesNotBypassOtherResumeOrFoodBlockers() {
        assertFalse(FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                0, TEMPORARY_BOX_BLOCKER, true,
                "organizer_temporary_shulker_unresolved", 0));
        assertFalse(FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                845, "latest_scan_incomplete_or_unhealthy", true,
                "organizer_temporary_shulker_unresolved", 0));
        assertFalse(FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                845, TEMPORARY_BOX_BLOCKER, true,
                "no_food_retrieved_1", 0));
        assertFalse(FoodContingencyRetryPolicy.allowsAttestedPackedBoxResume(
                845, TEMPORARY_BOX_BLOCKER, true,
                "organizer_temporary_shulker_unresolved",
                FoodContingencyRetryPolicy.MAX_RETRIES));
    }
}
