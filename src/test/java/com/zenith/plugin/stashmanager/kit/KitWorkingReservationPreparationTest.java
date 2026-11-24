package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingReservationPreparationTest {
    @Test void verifiesOnlyAfterServerWindowIsClosed() {
        var probe = new FakeProbe();
        var audit = new KitWorkingReservationPreparation(new int[]{1, 64, 2}, probe);
        assertFalse(audit.tick().verified());
        assertFalse(audit.tick().verified());
        assertTrue(audit.tick().verified());
        assertTrue(audit.tick().verifiedAtEpochMilli() > 0);
        assertTrue(probe.shutdown);
    }

    @Test void occupiedChestNeverProducesReservationEvidence() {
        var probe = new FakeProbe();
        probe.occupied = true;
        var audit = new KitWorkingReservationPreparation(new int[]{1, 64, 2}, probe);
        assertFalse(audit.tick().verified());
        assertFalse(audit.tick().verified());
        var result = audit.tick();
        assertEquals("destination_is_not_an_empty_double_chest", result.blocker());
        assertEquals(0, result.verifiedAtEpochMilli());
    }

    private static final class FakeProbe implements KitWorkingReservationPreparation.Probe {
        int closes;
        boolean occupied;
        boolean shutdown;
        @Override public KitWorkingMinecraftPort.ProbeResult inspect(int[] position) {
            return occupied
                    ? new KitWorkingMinecraftPort.ProbeResult(
                            KitWorkingTransferExecutor.Check.MISMATCH, null,
                            "destination_is_not_an_empty_double_chest")
                    : new KitWorkingMinecraftPort.ProbeResult(
                            KitWorkingTransferExecutor.Check.MATCH, null, null);
        }
        @Override public KitWorkingTransferExecutor.Check close() {
            return ++closes < 3 ? KitWorkingTransferExecutor.Check.WAIT
                    : KitWorkingTransferExecutor.Check.MATCH;
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public void onContainerData(int containerId) { }
    }
}
