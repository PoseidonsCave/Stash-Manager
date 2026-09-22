package com.zenith.plugin.stashmanager.organizer;

/** Resolves a restored destination checkpoint from the live exact-task cargo inventory. */
final class CheckpointCargoRecovery {
    enum Disposition {
        RESUME_HANDOFF,
        COMPLETE_DESTINATION
    }

    private CheckpointCargoRecovery() {}

    static Disposition disposition(int exactCargoUnitsPresent) {
        return exactCargoUnitsPresent > 0
                ? Disposition.RESUME_HANDOFF
                : Disposition.COMPLETE_DESTINATION;
    }

    /** Cap restart evidence to the journal-owned quantity, including finite keep-list excess. */
    static int provableCargoUnits(
            int ledgerRemaining,
            int wholeStackUnits,
            int finiteKeepExcessUnits) {
        int observable = Math.max(0, wholeStackUnits) + Math.max(0, finiteKeepExcessUnits);
        return ledgerRemaining > 0
                ? Math.min(ledgerRemaining, observable)
                : observable;
    }
}
