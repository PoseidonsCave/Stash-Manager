package com.zenith.plugin.stashmanager.orchestration;

import java.util.Objects;

/**
 * Tracks one upstream connection outage across automatic and manual reconnect attempts.
 * Event callbacks can arrive on different Zenith executors, so transitions are synchronized.
 */
public final class ConnectionRecoveryTracker {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public enum Phase {
        ONLINE,
        DISCONNECTED,
        AUTO_RECONNECT_WAIT,
        CONNECTING,
        TRANSPORT_CONNECTED,
        LOGIN_FAILED
    }

    public enum Transition {
        NONE,
        OUTAGE_STARTED,
        AUTO_RECONNECT_SCHEDULED,
        CONNECT_STARTED,
        TRANSPORT_CONNECTED,
        LOGIN_FAILED,
        ONLINE_RECOVERED
    }

    public record Update(
            Transition transition,
            Phase phase,
            int outageNumber,
            long elapsedSeconds
    ) {}

    private Phase phase = Phase.ONLINE;
    private boolean pending;
    private int outageCount;
    private int recoveryCount;
    private long outageStartedAtNanos;
    private String reason = "";
    private boolean manualDisconnect;
    private int scheduledReconnectDelaySeconds;

    public synchronized Update beginOutage(
            String outageReason, boolean wasManualDisconnect, long nowNanos) {
        reason = Objects.toString(outageReason, "disconnected");
        phase = Phase.DISCONNECTED;
        if (pending) {
            manualDisconnect = manualDisconnect || wasManualDisconnect;
            return update(Transition.NONE, nowNanos);
        }

        pending = true;
        manualDisconnect = wasManualDisconnect;
        scheduledReconnectDelaySeconds = 0;
        outageCount++;
        outageStartedAtNanos = nowNanos;
        return update(Transition.OUTAGE_STARTED, nowNanos);
    }

    public synchronized Update autoReconnectScheduled(int delaySeconds, long nowNanos) {
        if (!pending) return update(Transition.NONE, nowNanos);
        scheduledReconnectDelaySeconds = Math.max(0, delaySeconds);
        phase = Phase.AUTO_RECONNECT_WAIT;
        return update(Transition.AUTO_RECONNECT_SCHEDULED, nowNanos);
    }

    public synchronized Update connectStarted(long nowNanos) {
        if (!pending) return update(Transition.NONE, nowNanos);
        phase = Phase.CONNECTING;
        return update(Transition.CONNECT_STARTED, nowNanos);
    }

    public synchronized Update transportConnected(long nowNanos) {
        if (!pending) return update(Transition.NONE, nowNanos);
        phase = Phase.TRANSPORT_CONNECTED;
        return update(Transition.TRANSPORT_CONNECTED, nowNanos);
    }

    public synchronized Update loginFailed(long nowNanos) {
        if (!pending) return update(Transition.NONE, nowNanos);
        phase = Phase.LOGIN_FAILED;
        return update(Transition.LOGIN_FAILED, nowNanos);
    }

    public synchronized Update online(long nowNanos) {
        if (!pending) {
            phase = Phase.ONLINE;
            return update(Transition.NONE, nowNanos);
        }

        long elapsedSeconds = elapsedSeconds(nowNanos);
        pending = false;
        phase = Phase.ONLINE;
        recoveryCount++;
        return new Update(Transition.ONLINE_RECOVERED, phase, outageCount, elapsedSeconds);
    }

    public synchronized void reset() {
        phase = Phase.ONLINE;
        pending = false;
        outageStartedAtNanos = 0L;
        reason = "";
        manualDisconnect = false;
        scheduledReconnectDelaySeconds = 0;
    }

    public synchronized boolean isPending() {
        return pending;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int outageCount() {
        return outageCount;
    }

    public synchronized int recoveryCount() {
        return recoveryCount;
    }

    public synchronized String reason() {
        return reason;
    }

    public synchronized boolean manualDisconnect() {
        return manualDisconnect;
    }

    public synchronized int scheduledReconnectDelaySeconds() {
        return scheduledReconnectDelaySeconds;
    }

    public synchronized long elapsedSeconds(long nowNanos) {
        if (!pending || outageStartedAtNanos <= 0L) return 0L;
        return Math.max(0L, nowNanos - outageStartedAtNanos) / NANOS_PER_SECOND;
    }

    private Update update(Transition transition, long nowNanos) {
        return new Update(transition, phase, outageCount, elapsedSeconds(nowNanos));
    }
}
