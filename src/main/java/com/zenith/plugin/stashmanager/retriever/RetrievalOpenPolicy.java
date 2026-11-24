package com.zenith.plugin.stashmanager.retriever;

/** Pure decisions for the asynchronous chest-open handoff. */
final class RetrievalOpenPolicy {
    enum Opening { CONFIRMED, WAIT_FOR_CONTENTS, RETRY_CLICK, TIMED_OUT }

    private RetrievalOpenPolicy() { }

    static Opening opening(boolean contentReceived, int packetContainerId,
                           int liveContainerId, int waitedTicks,
                           int openTimeoutTicks, int contentGraceTicks) {
        if (contentReceived && packetContainerId > 0
                && packetContainerId == liveContainerId) return Opening.CONFIRMED;
        // An opened window can precede its contents packet. Never discard it at the
        // ordinary click timeout and then start walking with the GUI still open.
        if (liveContainerId > 0) return waitedTicks > openTimeoutTicks + contentGraceTicks
                ? Opening.TIMED_OUT : Opening.WAIT_FOR_CONTENTS;
        return waitedTicks > openTimeoutTicks ? Opening.TIMED_OUT : Opening.RETRY_CLICK;
    }

    static boolean shouldCloseBeforeWalking(int liveContainerId) {
        return liveContainerId > 0;
    }

    static int preferredHalf(boolean primaryVisible, boolean partnerVisible) {
        if (primaryVisible) return 0;
        return partnerVisible ? 1 : -1;
    }
}
