package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanQueueCursorTest {
    @Test
    void firstTargetAppendedAfterChunkWalkIsNotSkipped() {
        int oldSize = 2798;
        int parkedCursor = ScanQueueCursor.beforeAppendedTargets(oldSize);
        assertEquals(oldSize, parkedCursor + 1);
    }

    @Test
    void emptyQueueSelectsFirstDiscoveredTarget() {
        assertEquals(0, ScanQueueCursor.beforeAppendedTargets(0) + 1);
    }
}
