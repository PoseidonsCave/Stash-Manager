package com.zenith.plugin.stashmanager.kit;

/** Fail-closed admission for a future physical lane-ownership transfer. */
public final class KitConsolidationBridgeGate {
    private KitConsolidationBridgeGate() { }

    public enum Mode { DEPLETED_ONLY, RESIDUAL_HANDOFF }

    public enum Verdict {
        DISABLED,
        RULE_MISSING,
        PROTECTED_CLASS,
        JOB_UNSETTLED,
        BORROWED_BOXES_PENDING,
        AUTOMATION_ACTIVE,
        BASELINE_UNTRUSTED,
        POST_SCAN_UNTRUSTED,
        MATERIAL_BALANCE_MISMATCH,
        RECEIPT_UNVERIFIED,
        OUTPUT_UNVERIFIED,
        DESTINATION_UNVERIFIED,
        HANDOFF_UNVERIFIED,
        SOURCE_LANE_NOT_EMPTY,
        RESIDUAL_STOCK,
        READY
    }

    public record Evidence(boolean enabled, boolean ruleMatches, String storageClass,
                           boolean pinnedClass, Mode mode, boolean jobComplete,
                           boolean pendingCargo, boolean borrowedBoxesReturned,
                           boolean automationActive, boolean baselineTrusted,
                           long deliveredAtEpochMilli, boolean postScanComplete,
                           long postScanStartedAtEpochMilli,
                           boolean materialBalanceMatches, boolean receiptVerified,
                           boolean outputVerified, boolean destinationManifestVerified,
                           boolean handoffVerified, boolean sourceLaneEmpty,
                           long residualUnitsOutsideKitBank) { }

    public static Verdict evaluate(Evidence evidence) {
        if (evidence == null || !evidence.enabled()) return Verdict.DISABLED;
        if (!evidence.ruleMatches() || evidence.mode() == null) return Verdict.RULE_MISSING;
        if (evidence.pinnedClass()
                || KitConsolidationPlan.protectedByDefault(evidence.storageClass())) {
            return Verdict.PROTECTED_CLASS;
        }
        if (!evidence.jobComplete() || evidence.pendingCargo()) return Verdict.JOB_UNSETTLED;
        if (!evidence.borrowedBoxesReturned()) return Verdict.BORROWED_BOXES_PENDING;
        if (evidence.automationActive()) return Verdict.AUTOMATION_ACTIVE;
        if (!evidence.baselineTrusted()) return Verdict.BASELINE_UNTRUSTED;
        if (!evidence.postScanComplete() || evidence.deliveredAtEpochMilli() <= 0
                || evidence.postScanStartedAtEpochMilli() <= evidence.deliveredAtEpochMilli()) {
            return Verdict.POST_SCAN_UNTRUSTED;
        }
        if (!evidence.materialBalanceMatches()) return Verdict.MATERIAL_BALANCE_MISMATCH;
        if (!evidence.receiptVerified()) return Verdict.RECEIPT_UNVERIFIED;
        if (!evidence.outputVerified()) return Verdict.OUTPUT_UNVERIFIED;
        if (!evidence.destinationManifestVerified()) return Verdict.DESTINATION_UNVERIFIED;
        if (evidence.mode() == Mode.RESIDUAL_HANDOFF && !evidence.handoffVerified()) {
            return Verdict.HANDOFF_UNVERIFIED;
        }
        if (!evidence.sourceLaneEmpty()) return Verdict.SOURCE_LANE_NOT_EMPTY;
        if (evidence.residualUnitsOutsideKitBank() != 0) return Verdict.RESIDUAL_STOCK;
        return Verdict.READY;
    }
}
