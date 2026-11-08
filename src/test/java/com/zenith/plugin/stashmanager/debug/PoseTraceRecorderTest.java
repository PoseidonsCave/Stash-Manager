package com.zenith.plugin.stashmanager.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseTraceRecorderTest {
    @TempDir Path tempDir;

    @Test
    void disabledTraceDoesNotRecordOrWrite() throws Exception {
        PoseTraceRecorder trace = new PoseTraceRecorder();
        Path snapshot = tempDir.resolve("trace.log");
        trace.record("organize_pose", "state=WALKING", 1, 2, 3,
                4, 5, 6, 7, "forced_inputs=Jtrue");
        trace.saveSnapshot(snapshot);
        assertEquals(0, trace.events().size());
        assertFalse(Files.exists(snapshot));
    }

    @Test
    void enabledTraceKeepsMovementEvidenceInSnapshot() throws Exception {
        PoseTraceRecorder trace = new PoseTraceRecorder();
        trace.setEnabled(true);
        trace.record("organize_pose", "state=SHULKER_STORE_WALK", 1.25, 79.625, 3.75,
                4, 5, 6, 7,
                "nav_feet=1,79,3, on_ground=false, forced_inputs=Ftrue/Jfalse, feet_block=ladder");
        Path snapshot = tempDir.resolve("diagnostics").resolve("trace.log");
        trace.saveSnapshot(snapshot);
        String contents = Files.readString(snapshot);
        assertTrue(contents.contains("position=1.250, 79.625, 3.750"));
        assertTrue(contents.contains("forced_inputs=Ftrue/Jfalse"));
        assertTrue(contents.contains("feet_block=ladder"));
    }
}
