package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanCompletionPolicyTest {
    @Test
    void regressionRunIsNotAcceptedAsACompleteSnapshot() {
        assertTrue(ScanCompletionPolicy.shouldAbort(2799, 2619, 179));
        assertTrue(ScanCompletionPolicy.shouldAbort(2799, 2673, 126));
    }

    @Test
    void smallOrLowRateFailureSetsRemainUsable() {
        assertFalse(ScanCompletionPolicy.shouldAbort(100, 90, 10));
        assertFalse(ScanCompletionPolicy.shouldAbort(2799, 2772, 27));
        assertTrue(ScanCompletionPolicy.shouldAbort(2799, 2771, 28));
    }

    @Test
    void toleratedFailuresDoNotBecomeATrustedSnapshot() {
        assertFalse(ScanCompletionPolicy.trustedSnapshot(2799, 2793, 6));
        assertFalse(ScanCompletionPolicy.trustedSnapshot(2799, 2798, 0));
        assertTrue(ScanCompletionPolicy.trustedSnapshot(2799, 2799, 0));
    }

    @Test
    void oneUnresolvedTargetUsesTheFailureAllowance() {
        ScanCompletionPolicy.Assessment assessment =
                ScanCompletionPolicy.assess(2799, 2798, 0);
        assertTrue(assessment.accepted());
        assertEquals(1, assessment.unresolved());
        assertEquals(1, assessment.effectiveFailures());
        assertEquals(27, assessment.allowedFailures());
    }

    @Test
    void unresolvedTargetsBeyondTheAllowanceAreRejectedAccurately() {
        ScanCompletionPolicy.Assessment assessment =
                ScanCompletionPolicy.assess(100, 89, 1);
        assertFalse(assessment.accepted());
        assertEquals(10, assessment.unresolved());
        assertEquals(11, assessment.effectiveFailures());
        assertEquals("scan_failure_budget_exceeded", assessment.rejectionReason());
    }

    @Test
    void overcountedProcessingIsRejectedAsAnAccountingError() {
        ScanCompletionPolicy.Assessment assessment =
                ScanCompletionPolicy.assess(100, 100, 1);
        assertFalse(assessment.accepted());
        assertEquals("processed_count_exceeds_discovered_count",
                assessment.rejectionReason());
    }
}
