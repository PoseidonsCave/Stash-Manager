package com.zenith.plugin.stashmanager.organizer;

/** Forces one destroy-state reset tick before each new temporary-block break request. */
final class BlockBreakAttemptGate {
    enum Step { RESET, SUBMIT, WAIT }

    private boolean resetIssued;

    Step next(boolean requestActive) {
        if (requestActive) return Step.WAIT;
        if (!resetIssued) {
            resetIssued = true;
            return Step.RESET;
        }
        return Step.SUBMIT;
    }

    void clear() {
        resetIssued = false;
    }
}
