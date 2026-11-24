package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitConsolidationBridgeGateTest {
    private static KitConsolidationBridgeGate.Evidence evidence(
            KitConsolidationBridgeGate.Mode mode, boolean handoffVerified,
            boolean sourceEmpty, long residual) {
        return new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false, mode,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, true, true, true, handoffVerified, sourceEmpty, residual);
    }

    @Test
    void depletedClassNeedsVerifiedOutputAndLaterCompleteScan() {
        var good = evidence(KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                false, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.READY,
                KitConsolidationBridgeGate.evaluate(good));
        var stale = new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 2_000L, true, 1_000L,
                true, true, true, true, false, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.POST_SCAN_UNTRUSTED,
                KitConsolidationBridgeGate.evaluate(stale));
    }

    @Test
    void residualModeRequiresHandoffAndActualEmptySource() {
        assertEquals(KitConsolidationBridgeGate.Verdict.HANDOFF_UNVERIFIED,
                KitConsolidationBridgeGate.evaluate(evidence(
                        KitConsolidationBridgeGate.Mode.RESIDUAL_HANDOFF,
                        false, true, 0)));
        assertEquals(KitConsolidationBridgeGate.Verdict.SOURCE_LANE_NOT_EMPTY,
                KitConsolidationBridgeGate.evaluate(evidence(
                        KitConsolidationBridgeGate.Mode.RESIDUAL_HANDOFF,
                        true, false, 0)));
        assertEquals(KitConsolidationBridgeGate.Verdict.RESIDUAL_STOCK,
                KitConsolidationBridgeGate.evaluate(evidence(
                        KitConsolidationBridgeGate.Mode.RESIDUAL_HANDOFF,
                        true, true, 1)));
    }

    @Test
    void protectedClassesNeverTransferEvenWithAllOtherEvidence() {
        var pinned = new KitConsolidationBridgeGate.Evidence(true, true,
                "petrified_oak_slab", false, KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, true, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.PROTECTED_CLASS,
                KitConsolidationBridgeGate.evaluate(pinned));
        var sitePinned = new KitConsolidationBridgeGate.Evidence(true, true,
                "beacon", true, KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, true, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.PROTECTED_CLASS,
                KitConsolidationBridgeGate.evaluate(sitePinned));
    }

    @Test
    void noConfiguredRuleOrPendingCargoBlocksTransfer() {
        var noRule = new KitConsolidationBridgeGate.Evidence(true, false, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, true, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.RULE_MISSING,
                KitConsolidationBridgeGate.evaluate(noRule));
        var active = new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, true, true, false, true, 1_000L, true, 2_000L,
                true, true, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.JOB_UNSETTLED,
                KitConsolidationBridgeGate.evaluate(active));
    }

    @Test
    void receiptBorrowedBoxAndDestinationEvidenceAreIndependent() {
        var borrowed = new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, false, false, true, 1_000L, true, 2_000L,
                true, true, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.BORROWED_BOXES_PENDING,
                KitConsolidationBridgeGate.evaluate(borrowed));
        var noReceipt = new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, false, true, true, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.RECEIPT_UNVERIFIED,
                KitConsolidationBridgeGate.evaluate(noReceipt));
        var noDestination = new KitConsolidationBridgeGate.Evidence(true, true, "beacon", false,
                KitConsolidationBridgeGate.Mode.DEPLETED_ONLY,
                true, false, true, false, true, 1_000L, true, 2_000L,
                true, true, true, false, true, true, 0);
        assertEquals(KitConsolidationBridgeGate.Verdict.DESTINATION_UNVERIFIED,
                KitConsolidationBridgeGate.evaluate(noDestination));
    }
}
