package com.zenith.plugin.stashmanager.debug;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebugRecorderTest {

    @Test
    void exportKeepsActionableReasonBeyondLegacyLimit() {
        DebugRecorder recorder = new DebugRecorder();
        String detail = "context=" + "x".repeat(350)
                + ", reason=mixed_stack_transfer_unverified";

        recorder.record("organize_failed", detail);

        String exported = new String(recorder.exportText(), StandardCharsets.UTF_8);
        assertTrue(exported.contains("reason=mixed_stack_transfer_unverified"));
    }

    @Test
    void customCapacityEvictsOldestEvent() {
        DebugRecorder recorder = new DebugRecorder(2);
        recorder.record("first", "one");
        recorder.record("second", "two");
        recorder.record("third", "three");

        assertEquals(2, recorder.size());
        assertEquals("second", recorder.recent(2).get(0).stage());
        assertEquals("third", recorder.recent(2).get(1).stage());
        assertThrows(IllegalArgumentException.class, () -> new DebugRecorder(0));
    }

    @Test
    void geometryRecorderKeepsLongSnapshotWithoutChangingNormalLimit() {
        String snapshot = "nearby=" + "chest@1,2,3;".repeat(150);
        DebugRecorder geometry = new DebugRecorder(2, 16000);
        DebugRecorder normal = new DebugRecorder();
        geometry.record("container_open_timeout", snapshot);
        normal.record("container_open_timeout", snapshot);

        assertEquals(snapshot, geometry.recent(1).get(0).detail());
        assertTrue(normal.recent(1).get(0).detail().length() <= 1000);
        assertThrows(IllegalArgumentException.class, () -> new DebugRecorder(2, 3));
    }
}
