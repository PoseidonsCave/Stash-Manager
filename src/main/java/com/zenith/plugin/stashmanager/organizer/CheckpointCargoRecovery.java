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
}
