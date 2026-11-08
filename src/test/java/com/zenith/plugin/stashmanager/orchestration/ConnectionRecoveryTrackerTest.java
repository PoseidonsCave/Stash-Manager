package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRecoveryTrackerTest {
    private static final long SECOND_NANOS = 1_000_000_000L;

    @Test
    void automaticReconnectCompletesOneOutage() {
        var tracker = new ConnectionRecoveryTracker();

        assertEquals(ConnectionRecoveryTracker.Transition.OUTAGE_STARTED,
                tracker.beginOutage("connection_lost", false, 10L).transition());
        assertEquals(ConnectionRecoveryTracker.Transition.AUTO_RECONNECT_SCHEDULED,
                tracker.autoReconnectScheduled(30, 10L).transition());
        assertEquals(ConnectionRecoveryTracker.Phase.AUTO_RECONNECT_WAIT, tracker.phase());
        assertEquals(30, tracker.scheduledReconnectDelaySeconds());
        assertEquals(ConnectionRecoveryTracker.Transition.CONNECT_STARTED,
                tracker.connectStarted(10L + 30L * SECOND_NANOS).transition());
        assertEquals(ConnectionRecoveryTracker.Transition.TRANSPORT_CONNECTED,
                tracker.transportConnected(10L + 31L * SECOND_NANOS).transition());

        var online = tracker.online(10L + 33L * SECOND_NANOS);
        assertEquals(ConnectionRecoveryTracker.Transition.ONLINE_RECOVERED,
                online.transition());
        assertEquals(33L, online.elapsedSeconds());
        assertFalse(tracker.isPending());
        assertEquals(1, tracker.outageCount());
        assertEquals(1, tracker.recoveryCount());
    }

    @Test
    void manualReconnectUsesTheSamePendingCheckpoint() {
        var tracker = new ConnectionRecoveryTracker();

        tracker.beginOutage("Manual disconnect", true, 5L);
        assertTrue(tracker.manualDisconnect());
        assertTrue(tracker.isPending());

        assertEquals(ConnectionRecoveryTracker.Transition.CONNECT_STARTED,
                tracker.connectStarted(5L + SECOND_NANOS).transition());
        assertEquals(ConnectionRecoveryTracker.Transition.ONLINE_RECOVERED,
                tracker.online(5L + 2L * SECOND_NANOS).transition());
        assertEquals(1, tracker.recoveryCount());
    }

    @Test
    void failedLoginPreservesTheOutageForAnotherAttempt() {
        var tracker = new ConnectionRecoveryTracker();

        tracker.beginOutage("timeout", false, 1L);
        tracker.connectStarted(2L);
        assertEquals(ConnectionRecoveryTracker.Transition.LOGIN_FAILED,
                tracker.loginFailed(3L).transition());
        assertTrue(tracker.isPending());
        assertEquals(ConnectionRecoveryTracker.Phase.LOGIN_FAILED, tracker.phase());

        assertEquals(ConnectionRecoveryTracker.Transition.CONNECT_STARTED,
                tracker.connectStarted(4L).transition());
        assertEquals(ConnectionRecoveryTracker.Transition.ONLINE_RECOVERED,
                tracker.online(5L).transition());
    }

    @Test
    void duplicateDisconnectDoesNotCreateAnotherOutage() {
        var tracker = new ConnectionRecoveryTracker();

        tracker.beginOutage("bot tick stopped", false, 10L);
        var duplicate = tracker.beginOutage("final server reason", true, 20L);

        assertEquals(ConnectionRecoveryTracker.Transition.NONE, duplicate.transition());
        assertEquals(1, tracker.outageCount());
        assertEquals("final server reason", tracker.reason());
        assertTrue(tracker.manualDisconnect());
    }

    @Test
    void aLaterOutageDoesNotInheritManualFlag() {
        var tracker = new ConnectionRecoveryTracker();

        tracker.beginOutage("manual", true, 10L);
        tracker.online(20L);
        tracker.beginOutage("network", false, 30L);

        assertFalse(tracker.manualDisconnect());
        assertEquals(2, tracker.outageCount());
    }
}
