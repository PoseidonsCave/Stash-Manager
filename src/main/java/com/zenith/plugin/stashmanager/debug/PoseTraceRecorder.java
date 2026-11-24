package com.zenith.plugin.stashmanager.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Opt-in, bounded pose trace kept separate from the normal failure log. */
public final class PoseTraceRecorder {
    private static final int MAX_EVENTS = 5000;
    private final DebugRecorder events = new DebugRecorder(MAX_EVENTS);
    private volatile boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DebugRecorder events() {
        return events;
    }

    public void record(String stage, String context, double x, double y, double z,
                       double yaw, double pitch, double botYaw, double botPitch) {
        record(stage, context, x, y, z, yaw, pitch, botYaw, botPitch, "");
    }

    public void record(String stage, String context, double x, double y, double z,
                       double yaw, double pitch, double botYaw, double botPitch,
                       String movementDetail) {
        if (!enabled) return;
        events.record(stage, String.format(Locale.ROOT,
                "%s, position=%.3f, %.3f, %.3f, yaw=%.2f, pitch=%.2f, bot_yaw=%.2f, bot_pitch=%.2f%s",
                context, x, y, z, yaw, pitch, botYaw, botPitch,
                movementDetail.isEmpty() ? "" : ", " + movementDetail));
    }

    /** Save the bounded ring at a terminal failure without touching the job checkpoint. */
    public void saveSnapshot(Path destination) throws IOException {
        if (!enabled) return;
        Files.createDirectories(destination.getParent());
        Files.write(destination, events.exportText());
    }
}
