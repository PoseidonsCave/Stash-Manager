package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogisticsOrchestratorSimulationTest {

    private static final LogisticsOrchestrator.Config FAST =
            new LogisticsOrchestrator.Config(3, 3, 1, 3, 3);

    @Test
    void untouchedSourceTimeoutMovesTaskToTailWithoutGrowingQueue() {
        VirtualLogisticsPort port = new VirtualLogisticsPort();
        port.put("source-a", "red_shulker_box", 1);
        port.put("source-b", "blue_shulker_box", 1);
        port.scriptOpen("source-a",
                new VirtualLogisticsPort.OpenPlan(5, true),
                new VirtualLogisticsPort.OpenPlan(1, true));

        LogisticsOrchestrator engine = new LogisticsOrchestrator(FAST, port);
        engine.submit(task("a", "source-a", "dest-a", "red_shulker_box"));
        engine.submit(task("b", "source-b", "dest-b", "blue_shulker_box"));

        runUntilTerminal(engine, port, 100);

        assertEquals(LogisticsOrchestrator.Phase.DONE, engine.snapshot().phase());
        assertEquals(2, engine.snapshot().totalTasks());
        assertEquals(2, engine.snapshot().completedTasks());
        assertEquals(List.of("b", "a"), completedTaskOrder(engine));
        assertTrue(engine.events().stream().anyMatch(event ->
                event.type().equals("task_requeued")
                        && event.fields().get("task_id").equals("a")
                        && event.fields().get("queue_position").equals("tail")));
        assertTrue(port.allWindowsClosed());
    }

    @Test
    void cargoDestinationRetryRetainsOwnershipAndClosesLateWindow() {
        VirtualLogisticsPort port = new VirtualLogisticsPort();
        port.put("source-a", "light_gray_shulker_box", 1);
        port.put("source-b", "yellow_shulker_box", 1);
        port.scriptOpen("dest-a",
                new VirtualLogisticsPort.OpenPlan(5, true),
                new VirtualLogisticsPort.OpenPlan(1, true));

        LogisticsOrchestrator engine = new LogisticsOrchestrator(FAST, port);
        engine.submit(task("cargo", "source-a", "dest-a", "light_gray_shulker_box"));
        engine.submit(task("later", "source-b", "dest-b", "yellow_shulker_box"));

        runUntilTerminal(engine, port, 100);

        assertEquals(LogisticsOrchestrator.Phase.DONE, engine.snapshot().phase());
        assertEquals(List.of("cargo", "later"), completedTaskOrder(engine));
        assertEquals(2, engine.snapshot().totalTasks());
        assertEquals(1, port.containerCount("dest-a", "light_gray_shulker_box"));
        assertEquals(0, port.playerCount("light_gray_shulker_box"));
        assertEquals(1, port.totalCount("light_gray_shulker_box"));
        assertTrue(engine.events().stream().anyMatch(event -> event.type().equals("late_open_result")));
        assertTrue(port.closedWindowCount() >= 3);
        assertTrue(port.allWindowsClosed());
    }

    @Test
    void exhaustedDestinationRetriesBlockWithCargoPreserved() {
        VirtualLogisticsPort port = new VirtualLogisticsPort();
        port.put("source", "light_gray_shulker_box", 1);
        port.scriptOpen("destination",
                new VirtualLogisticsPort.OpenPlan(1, false),
                new VirtualLogisticsPort.OpenPlan(1, false),
                new VirtualLogisticsPort.OpenPlan(1, false));

        LogisticsOrchestrator engine = new LogisticsOrchestrator(FAST, port);
        engine.submit(task("cargo", "source", "destination", "light_gray_shulker_box"));

        runUntilTerminal(engine, port, 100);

        assertEquals(LogisticsOrchestrator.Phase.BLOCKED, engine.snapshot().phase());
        assertTrue(engine.snapshot().cargoOwned());
        assertEquals(1, engine.snapshot().totalTasks());
        assertEquals(0, engine.snapshot().completedTasks());
        assertEquals(0, port.containerCount("source", "light_gray_shulker_box"));
        assertEquals(1, port.playerCount("light_gray_shulker_box"));
        assertEquals(0, port.containerCount("destination", "light_gray_shulker_box"));
        assertEquals(1, port.totalCount("light_gray_shulker_box"));
    }

    @Test
    void exactItemVariantsRemainSeparatedAndConserved() {
        String fortune = "diamond_pickaxe[fortune]";
        String silk = "diamond_pickaxe[silk_touch]";
        VirtualLogisticsPort port = new VirtualLogisticsPort();
        port.put("fortune-source", fortune, 1);
        port.put("silk-source", silk, 1);

        LogisticsOrchestrator engine = new LogisticsOrchestrator(FAST, port);
        engine.submit(task("fortune", "fortune-source", "fortune-lane", fortune));
        engine.submit(task("silk", "silk-source", "silk-lane", silk));

        runUntilTerminal(engine, port, 100);

        assertEquals(1, port.containerCount("fortune-lane", fortune));
        assertEquals(0, port.containerCount("fortune-lane", silk));
        assertEquals(1, port.containerCount("silk-lane", silk));
        assertEquals(0, port.containerCount("silk-lane", fortune));
        assertEquals(1, port.totalCount(fortune));
        assertEquals(1, port.totalCount(silk));
        assertFalse(engine.snapshot().cargoOwned());
    }

    @Test
    void ambiguousLateTransferBlocksAllFollowingWorkUntilReconciliation() {
        VirtualLogisticsPort port = new VirtualLogisticsPort();
        port.put("source-a", "red_shulker_box", 1);
        port.put("source-b", "blue_shulker_box", 1);
        port.setTransferDelayTicks(5);

        LogisticsOrchestrator engine = new LogisticsOrchestrator(FAST, port);
        engine.submit(task("ambiguous", "source-a", "dest-a", "red_shulker_box"));
        engine.submit(task("must-wait", "source-b", "dest-b", "blue_shulker_box"));

        runUntilTerminal(engine, port, 100);
        // The simulated server still completes the click after local cancellation.
        port.advanceTo(20);

        assertEquals(LogisticsOrchestrator.Phase.BLOCKED, engine.snapshot().phase());
        assertTrue(engine.snapshot().cargoOwned());
        assertEquals("ambiguous", engine.snapshot().activeTaskId());
        assertEquals(0, engine.snapshot().completedTasks());
        assertEquals(2, engine.snapshot().totalTasks());
        assertEquals(1, port.playerCount("red_shulker_box"));
        assertEquals(1, port.containerCount("source-b", "blue_shulker_box"));
        assertTrue(engine.events().stream().anyMatch(event ->
                event.type().equals("transfer_reconciliation_required")));
        assertTrue(engine.events().stream().anyMatch(event ->
                event.type().equals("late_transfer_result")
                        && event.fields().get("reconciliation_required").equals(true)));
    }

    private static LogisticsOrchestrator.Task task(
            String id, String source, String destination, String itemId
    ) {
        return new LogisticsOrchestrator.Task(id, source, destination, itemId, 1);
    }

    private static void runUntilTerminal(
            LogisticsOrchestrator engine, VirtualLogisticsPort port, int maxTicks
    ) {
        engine.tick(0);
        for (long tick = 1; tick <= maxTicks; tick++) {
            engine.tick(tick);
            port.advanceTo(tick);
            LogisticsOrchestrator.Phase phase = engine.snapshot().phase();
            if (phase == LogisticsOrchestrator.Phase.DONE
                    || phase == LogisticsOrchestrator.Phase.BLOCKED) {
                return;
            }
        }
        throw new AssertionError("orchestrator did not reach a terminal state: " + engine.snapshot());
    }

    private static List<String> completedTaskOrder(LogisticsOrchestrator engine) {
        return engine.events().stream()
                .filter(event -> event.type().equals("task_completed"))
                .map(event -> String.valueOf(event.fields().get("task_id")))
                .toList();
    }
}
