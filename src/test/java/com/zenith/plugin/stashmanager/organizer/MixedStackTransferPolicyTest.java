package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixedStackTransferPolicyTest {

    @Test
    void waitsWhileItemMayStillBeOnCursorBetweenClicks() {
        assertEquals(MixedStackTransferPolicy.Result.WAIT,
                MixedStackTransferPolicy.assess(false, false, false, false, false, 0, 40));
    }

    @Test
    void confirmsOnlyAfterDestinationReceivesStack() {
        assertEquals(MixedStackTransferPolicy.Result.CONFIRMED,
                MixedStackTransferPolicy.assess(true, true, false, true, false, 0, 40));
    }

    @Test
    void retriesRejectedOrUnchangedTransfers() {
        assertEquals(MixedStackTransferPolicy.Result.RETRY,
                MixedStackTransferPolicy.assess(true, false, true, false, false, 0, 40));
        assertEquals(MixedStackTransferPolicy.Result.RETRY,
                MixedStackTransferPolicy.assess(true, true, true, false, false, 0, 40));
    }

    @Test
    void waitsForInventoryCacheToSettleBeforeMarkingTransferUnverified() {
        assertEquals(MixedStackTransferPolicy.Result.WAIT,
                MixedStackTransferPolicy.assess(true, true, false, false, false, 39, 40));
        assertEquals(MixedStackTransferPolicy.Result.UNVERIFIED,
                MixedStackTransferPolicy.assess(true, true, false, false, false, 40, 40));
    }

    @Test
    void cursorMustClearBeforeDestinationCanConfirmTheTransfer() {
        assertEquals(MixedStackTransferPolicy.Result.WAIT,
                MixedStackTransferPolicy.assess(true, true, false, true, true, 39, 40));
        assertEquals(MixedStackTransferPolicy.Result.UNVERIFIED,
                MixedStackTransferPolicy.assess(true, true, false, true, true, 40, 40));
    }
}
