package com.zenith.plugin.stashmanager.orchestration;

/** Keeps the scan cursor before containers discovered after a chunk-loading walk. */
public final class ScanQueueCursor {
    private ScanQueueCursor() { }

    public static int beforeAppendedTargets(int queuedCount) {
        return queuedCount - 1;
    }
}
