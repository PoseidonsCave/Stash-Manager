package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerOpenAssessmentTest {
    @Test
    void aDelayedLiveCacheIsNeverClassifiedAsFull() {
        assertEquals(ContainerOpenAssessment.Result.WAIT_FOR_CACHE,
                ContainerOpenAssessment.assess(true, false, false));
    }

    @Test
    void classifiesCapacityOnlyAfterTheCacheIsReady() {
        assertEquals(ContainerOpenAssessment.Result.READY,
                ContainerOpenAssessment.assess(true, true, true));
        assertEquals(ContainerOpenAssessment.Result.FULL,
                ContainerOpenAssessment.assess(true, true, false));
    }
}
