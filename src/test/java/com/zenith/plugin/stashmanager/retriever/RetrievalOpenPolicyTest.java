package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalOpenPolicyTest {
    @Test
    void liveWindowWaitsForContentsBeyondOrdinaryOpenTimeout() {
        assertEquals(RetrievalOpenPolicy.Opening.WAIT_FOR_CONTENTS,
                RetrievalOpenPolicy.opening(false, -1, 64, 101, 100, 60));
        assertEquals(RetrievalOpenPolicy.Opening.CONFIRMED,
                RetrievalOpenPolicy.opening(true, 64, 64, 102, 100, 60));
    }

    @Test
    void unconfirmedOrMismatchedWindowNeverBecomesRetrievalCargo() {
        assertEquals(RetrievalOpenPolicy.Opening.RETRY_CLICK,
                RetrievalOpenPolicy.opening(false, -1, -1, 100, 100, 60));
        assertEquals(RetrievalOpenPolicy.Opening.TIMED_OUT,
                RetrievalOpenPolicy.opening(false, -1, -1, 101, 100, 60));
        assertEquals(RetrievalOpenPolicy.Opening.WAIT_FOR_CONTENTS,
                RetrievalOpenPolicy.opening(true, 63, 64, 101, 100, 60));
        assertEquals(RetrievalOpenPolicy.Opening.TIMED_OUT,
                RetrievalOpenPolicy.opening(true, 63, 64, 161, 100, 60));
    }

    @Test
    void previousWindowMustCloseBeforeWalking() {
        assertTrue(RetrievalOpenPolicy.shouldCloseBeforeWalking(64));
        assertFalse(RetrievalOpenPolicy.shouldCloseBeforeWalking(0));
    }

    @Test
    void visiblePartnerIsChosenWhenPrimaryIsOccluded() {
        assertEquals(1, RetrievalOpenPolicy.preferredHalf(false, true));
        assertEquals(0, RetrievalOpenPolicy.preferredHalf(true, true));
        assertEquals(-1, RetrievalOpenPolicy.preferredHalf(false, false));
    }
}
