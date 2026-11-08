package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashRetrieverStartFailureReasonTest {

    @Test
    void sessionAndOwnershipFailuresAreRetryable() {
        assertTrue(StashRetriever.StartFailureReason.BOT_DISCONNECTED.retryable());
        assertTrue(StashRetriever.StartFailureReason.PROXY_IN_USE.retryable());
        assertTrue(StashRetriever.StartFailureReason.ALREADY_ACTIVE.retryable());
    }

    @Test
    void invalidOrExhaustedPlansRemainActionable() {
        assertFalse(StashRetriever.StartFailureReason.EMPTY_REQUEST.retryable());
        assertFalse(StashRetriever.StartFailureReason.NO_MATCHES.retryable());
        assertFalse(StashRetriever.StartFailureReason.NONE.retryable());
    }
}
