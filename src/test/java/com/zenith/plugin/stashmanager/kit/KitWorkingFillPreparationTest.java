package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingFillPreparationTest {
    private static final String FINGERPRINT = "a".repeat(64);

    private static KitWorkingSourceAssignmentPlan.Assignment assignment(int slot) {
        return new KitWorkingSourceAssignmentPlan.Assignment(
                new DatabaseManager.KitWorkingSlotRow(10, 64, 2, slot,
                        "redstone", "redstone", 64, "pending", null),
                new int[]{1, 64, 1}, slot);
    }

    @Test void buildsTicketsOnlyAfterAllLiveProbesAndClosedWindow() {
        var probe = new FakeProbe();
        var preparation = new KitWorkingFillPreparation(List.of(assignment(1), assignment(2)),
                probe);
        assertFalse(preparation.tick().ready());
        assertEquals(1, preparation.inspected());
        assertFalse(preparation.tick().ready());
        assertEquals(2, preparation.inspected());
        assertTrue(preparation.tick().ready());
        assertEquals(2, preparation.tick().tickets().size());
        assertTrue(probe.shutdown);
        assertEquals(FINGERPRINT, preparation.tick().tickets().get(0).boxFingerprint());
    }

    @Test void mismatchNeverExposesPartialTickets() {
        var probe = new FakeProbe();
        probe.failAt = 2;
        var preparation = new KitWorkingFillPreparation(List.of(assignment(1), assignment(2)),
                probe);
        assertFalse(preparation.tick().ready());
        assertFalse(preparation.tick().ready());
        var result = preparation.tick();
        assertEquals("source_box_contents_mismatch", result.blocker());
        assertTrue(result.tickets().isEmpty());
        assertTrue(probe.shutdown);
    }

    @Test void interruptionDropsUndurableAssignments() {
        var probe = new FakeProbe();
        var preparation = new KitWorkingFillPreparation(List.of(assignment(1)), probe);
        preparation.interrupt("bot_ticks_stopped");
        assertEquals("bot_ticks_stopped", preparation.tick().blocker());
        assertTrue(preparation.tick().tickets().isEmpty());
    }

    private static final class FakeProbe implements KitWorkingFillPreparation.Probe {
        int inspections;
        int closes;
        int failAt = -1;
        boolean shutdown;

        @Override public KitWorkingMinecraftPort.ProbeResult inspect(
                KitWorkingSourceAssignmentPlan.Assignment assignment) {
            if (++inspections == failAt) return new KitWorkingMinecraftPort.ProbeResult(
                    KitWorkingTransferExecutor.Check.MISMATCH, null,
                    "source_box_contents_mismatch");
            return new KitWorkingMinecraftPort.ProbeResult(
                    KitWorkingTransferExecutor.Check.MATCH, FINGERPRINT, null);
        }
        @Override public KitWorkingTransferExecutor.Check close() {
            return ++closes == 1 ? KitWorkingTransferExecutor.Check.WAIT
                    : KitWorkingTransferExecutor.Check.MATCH;
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public void onContainerData(int containerId) { }
    }
}
