package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;

final class VirtualLogisticsPort implements LogisticsOrchestrator.Port {

    record OpenPlan(int delayTicks, boolean opened) {
        OpenPlan {
            if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be non-negative");
        }
    }

    private record Scheduled(long tick, long sequence, Runnable action) {}

    private final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>(Comparator
            .comparingLong(Scheduled::tick)
            .thenComparingLong(Scheduled::sequence));
    private final Map<String, Deque<OpenPlan>> openPlans = new HashMap<>();
    private final Map<String, Map<String, Integer>> containers = new HashMap<>();
    private final Map<String, Integer> playerInventory = new HashMap<>();
    private final Map<Integer, String> openWindows = new HashMap<>();
    private final Set<Long> cancelledOperations = new HashSet<>();
    private final Set<Integer> closedWindows = new HashSet<>();

    private long now;
    private long eventSequence;
    private int nextWindowId = 1;
    private int playerCapacity = Integer.MAX_VALUE;
    private int transferDelayTicks = 1;

    void setPlayerCapacity(int playerCapacity) {
        this.playerCapacity = playerCapacity;
    }

    void setTransferDelayTicks(int transferDelayTicks) {
        if (transferDelayTicks < 0) throw new IllegalArgumentException("delay must be non-negative");
        this.transferDelayTicks = transferDelayTicks;
    }

    void put(String container, String itemId, int quantity) {
        containers.computeIfAbsent(container, ignored -> new HashMap<>()).put(itemId, quantity);
    }

    void scriptOpen(String target, OpenPlan... plans) {
        Deque<OpenPlan> queue = openPlans.computeIfAbsent(target, ignored -> new ArrayDeque<>());
        for (OpenPlan plan : plans) queue.addLast(plan);
    }

    int containerCount(String container, String itemId) {
        return containers.getOrDefault(container, Map.of()).getOrDefault(itemId, 0);
    }

    int playerCount(String itemId) {
        return playerInventory.getOrDefault(itemId, 0);
    }

    int totalCount(String itemId) {
        int total = playerCount(itemId);
        for (Map<String, Integer> contents : containers.values()) {
            total += contents.getOrDefault(itemId, 0);
        }
        return total;
    }

    boolean allWindowsClosed() {
        return openWindows.isEmpty();
    }

    int closedWindowCount() {
        return closedWindows.size();
    }

    void advanceTo(long tick) {
        if (tick < now) throw new IllegalArgumentException("virtual time cannot move backwards");
        now = tick;
        while (!scheduled.isEmpty() && scheduled.peek().tick() <= now) {
            scheduled.remove().action().run();
        }
    }

    @Override
    public void requestOpen(
            long operationId,
            String target,
            Consumer<LogisticsOrchestrator.OpenResult> completion
    ) {
        Deque<OpenPlan> plans = openPlans.get(target);
        OpenPlan plan = plans == null || plans.isEmpty()
                ? new OpenPlan(1, true)
                : plans.removeFirst();
        schedule(plan.delayTicks(), () -> {
            int windowId = -1;
            if (plan.opened()) {
                windowId = nextWindowId++;
                openWindows.put(windowId, target);
            }
            completion.accept(new LogisticsOrchestrator.OpenResult(
                    operationId, target, windowId, plan.opened()));
        });
    }

    @Override
    public void requestTransfer(
            long operationId,
            int windowId,
            LogisticsOrchestrator.TransferDirection direction,
            String itemId,
            int quantity,
            Consumer<LogisticsOrchestrator.TransferResult> completion
    ) {
        schedule(transferDelayTicks, () -> {
            String target = openWindows.get(windowId);
            int moved = 0;
            if (target != null) {
                if (direction == LogisticsOrchestrator.TransferDirection.TO_PLAYER) {
                    int available = containerCount(target, itemId);
                    int inventoryUsed = playerInventory.values().stream().mapToInt(Integer::intValue).sum();
                    moved = Math.min(quantity, Math.min(available, playerCapacity - inventoryUsed));
                    if (moved > 0) {
                        change(containers.computeIfAbsent(target, ignored -> new HashMap<>()), itemId, -moved);
                        change(playerInventory, itemId, moved);
                    }
                } else {
                    moved = Math.min(quantity, playerCount(itemId));
                    if (moved > 0) {
                        change(playerInventory, itemId, -moved);
                        change(containers.computeIfAbsent(target, ignored -> new HashMap<>()), itemId, moved);
                    }
                }
            }
            completion.accept(new LogisticsOrchestrator.TransferResult(operationId, windowId, moved));
        });
    }

    @Override
    public void cancelOperation(long operationId) {
        // Intentionally do not cancel scheduled responses. Real server packets may arrive after
        // local cancellation; this is the race the orchestrator must quarantine.
        cancelledOperations.add(operationId);
    }

    @Override
    public void closeWindow(int windowId) {
        if (windowId > 0) {
            openWindows.remove(windowId);
            closedWindows.add(windowId);
        }
    }

    private void schedule(int delayTicks, Runnable action) {
        scheduled.add(new Scheduled(now + delayTicks, ++eventSequence, action));
    }

    private static void change(Map<String, Integer> inventory, String itemId, int delta) {
        inventory.compute(itemId, (ignored, current) -> {
            int next = (current == null ? 0 : current) + delta;
            return next == 0 ? null : next;
        });
    }
}
