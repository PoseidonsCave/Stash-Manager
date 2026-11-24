package com.zenith.plugin.stashmanager.debug;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// Bounded ring-buffer of debug events (with optional stack traces) for
// exportable troubleshooting, mirroring OpenCraft's ChatDebugRecorder.
public final class DebugRecorder {

    private static final int DEFAULT_MAX_EVENTS = 500;
    private static final int DEFAULT_MAX_DETAIL_LENGTH = 1000;
    private final int maxEvents;
    private final int maxDetailLength;
    private final Deque<DebugEvent> events;

    public DebugRecorder() {
        this(DEFAULT_MAX_EVENTS);
    }

    public DebugRecorder(int maxEvents) {
        this(maxEvents, DEFAULT_MAX_DETAIL_LENGTH);
    }

    public DebugRecorder(int maxEvents, int maxDetailLength) {
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        if (maxDetailLength < 4) throw new IllegalArgumentException("maxDetailLength must be at least 4");
        this.maxEvents = maxEvents;
        this.maxDetailLength = maxDetailLength;
        this.events = new ArrayDeque<>(maxEvents);
    }

    public synchronized void record(String stage, String detail) {
        record(stage, detail, null);
    }

    public synchronized void record(String stage, String detail, @Nullable Throwable error) {
        if (events.size() >= maxEvents) {
            events.removeFirst();
        }
        events.addLast(new DebugEvent(
            Instant.now().toString(),
            compact(stage, DEFAULT_MAX_DETAIL_LENGTH),
            compact(detail, maxDetailLength),
            error == null ? null : stackTraceOf(error)
        ));
    }

    public synchronized List<DebugEvent> recent(int limit) {
        List<DebugEvent> snapshot = new ArrayList<>(Math.min(limit, events.size()));
        int skipped = Math.max(0, events.size() - limit);
        int index = 0;
        for (DebugEvent event : events) {
            if (index++ < skipped) continue;
            snapshot.add(event);
        }
        return snapshot;
    }

    public synchronized void clear() {
        events.clear();
    }

    public synchronized int size() {
        return events.size();
    }

    // Full plain-text dump of every recorded event, in order, for file export.
    public synchronized byte[] exportText() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            for (DebugEvent event : events) {
                pw.println(event.timestamp() + " | " + event.stage() + " | " + event.detail());
                if (event.stackTrace() != null) {
                    pw.println(event.stackTrace());
                    pw.println("---");
                }
            }
        }
        return baos.toByteArray();
    }

    private static String stackTraceOf(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String compact(@Nullable String text, int limit) {
        if (text == null) return "";
        String singleLine = text.replaceAll("[\\r\\n]+", " ").strip();
        return singleLine.length() <= limit
            ? singleLine
            : singleLine.substring(0, limit - 3) + "...";
    }

    public record DebugEvent(String timestamp, String stage, String detail, @Nullable String stackTrace) {
    }
}
