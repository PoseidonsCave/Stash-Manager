package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pure, deterministic logistics state machine. It has no Zenith, Minecraft, wall-clock, or
 * thread dependencies: production and simulation adapters drive it with monotonically
 * increasing logical ticks and report operation results through callbacks.
 */
public final class LogisticsOrchestrator {

    public enum Phase {
        IDLE,
        WAITING_TO_RETRY,
        OPENING_SOURCE,
        TAKING_SOURCE,
        OPENING_DESTINATION,
        DEPOSITING_DESTINATION,
        DONE,
        BLOCKED
    }

    public enum TargetRole { SOURCE, DESTINATION }

    public enum TransferDirection { TO_PLAYER, TO_CONTAINER }

    private enum OperationKind { OPEN_SOURCE, OPEN_DESTINATION, TAKE_SOURCE, DEPOSIT_DESTINATION }

    public record Task(
            String id,
            String source,
            String destination,
            String itemId,
            int quantity
    ) {
        public Task {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(itemId, "itemId");
            if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public record Config(
            int openTimeoutTicks,
            int transferTimeoutTicks,
            int retryBackoffTicks,
            int maxSourceAttempts,
            int maxDestinationAttempts
    ) {
        public Config {
            if (openTimeoutTicks <= 0 || transferTimeoutTicks <= 0) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
            if (retryBackoffTicks < 0) throw new IllegalArgumentException("backoff must be non-negative");
            if (maxSourceAttempts <= 0 || maxDestinationAttempts <= 0) {
                throw new IllegalArgumentException("attempt limits must be positive");
            }
        }

        public static Config defaults() {
            return new Config(400, 100, 20, 3, 3);
        }
    }

    public record OpenResult(long operationId, String target, int windowId, boolean opened) {}

    public record TransferResult(long operationId, int windowId, int moved) {}

    public record Event(long tick, String type, Map<String, Object> fields) {
        public Event {
            fields = Map.copyOf(fields);
        }
    }

    public record Snapshot(
            Phase phase,
            int totalTasks,
            int completedTasks,
            int failedTasks,
            int queuedTasks,
            String activeTaskId,
            boolean cargoOwned,
            long expectedOperationId
    ) {}

    /** Runtime boundary implemented by both the Zenith adapter and deterministic simulator. */
    public interface Port {
        void requestOpen(long operationId, String target, Consumer<OpenResult> completion);

        void requestTransfer(
                long operationId,
                int windowId,
                TransferDirection direction,
                String itemId,
                int quantity,
                Consumer<TransferResult> completion
        );

        void cancelOperation(long operationId);

        void closeWindow(int windowId);
    }

    private static final class Work {
        private final Task task;
        private int sourceAttempts;
        private int destinationAttempts;
        private long eligibleAtTick;

        private Work(Task task) {
            this.task = task;
        }
    }

    private final Config config;
    private final Port port;
    private final Deque<Work> queue = new ArrayDeque<>();
    private final Set<String> taskIds = new HashSet<>();
    private final List<Event> events = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private Work active;
    private boolean cargoOwned;
    private int activeWindowId = -1;
    private long logicalTick;
    private long operationSequence;
    private long expectedOperationId = -1;
    private OperationKind expectedOperationKind;
    private long operationDeadline = Long.MAX_VALUE;
    private int totalTasks;
    private int completedTasks;
    private int failedTasks;

    public LogisticsOrchestrator(Config config, Port port) {
        this.config = Objects.requireNonNull(config, "config");
        this.port = Objects.requireNonNull(port, "port");
    }

    public void submit(Task task) {
        Objects.requireNonNull(task, "task");
        if (phase == Phase.BLOCKED || phase == Phase.DONE) {
            throw new IllegalStateException("cannot submit to terminal orchestrator");
        }
        if (!taskIds.add(task.id())) throw new IllegalArgumentException("duplicate task id: " + task.id());
        queue.addLast(new Work(task));
        totalTasks++;
        emit("task_queued", Map.of("task_id", task.id()));
    }

    public void tick(long tick) {
        if (tick < logicalTick) throw new IllegalArgumentException("logical ticks cannot move backwards");
        logicalTick = tick;
        if (phase == Phase.DONE || phase == Phase.BLOCKED) return;

        if (expectedOperationId >= 0 && tick > operationDeadline) {
            long expired = expectedOperationId;
            OperationKind expiredKind = expectedOperationKind;
            expectedOperationId = -1;
            expectedOperationKind = null;
            port.cancelOperation(expired);
            emit("operation_timed_out", Map.of(
                    "operation_id", expired,
                    "phase", phase.name()
            ));
            switch (expiredKind) {
                case OPEN_SOURCE -> retrySourceAtTail("operation_timeout");
                case OPEN_DESTINATION -> retryDestination("operation_timeout");
                case TAKE_SOURCE, DEPOSIT_DESTINATION -> blockForAmbiguousTransfer(expired, expiredKind);
            }
            return;
        }

        if (expectedOperationId >= 0) return;
        if (active != null && logicalTick < active.eligibleAtTick) {
            phase = Phase.WAITING_TO_RETRY;
            return;
        }
        if (active == null) acquireNextEligible();
        if (active == null) return;

        if (phase == Phase.IDLE || phase == Phase.WAITING_TO_RETRY) {
            if (cargoOwned) beginOpen(TargetRole.DESTINATION);
            else beginOpen(TargetRole.SOURCE);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                phase,
                totalTasks,
                completedTasks,
                failedTasks,
                queue.size(),
                active == null ? null : active.task.id(),
                cargoOwned,
                expectedOperationId
        );
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    private void acquireNextEligible() {
        if (queue.isEmpty()) {
            finishIfTerminal();
            return;
        }

        int candidates = queue.size();
        long earliest = Long.MAX_VALUE;
        while (candidates-- > 0) {
            Work candidate = queue.removeFirst();
            if (candidate.eligibleAtTick <= logicalTick) {
                active = candidate;
                cargoOwned = false;
                phase = Phase.IDLE;
                emit("task_started", Map.of("task_id", active.task.id()));
                return;
            }
            earliest = Math.min(earliest, candidate.eligibleAtTick);
            queue.addLast(candidate);
        }
        phase = Phase.WAITING_TO_RETRY;
        emit("queue_waiting", Map.of("eligible_at", earliest));
    }

    private void beginOpen(TargetRole role) {
        String target = role == TargetRole.SOURCE ? active.task.source() : active.task.destination();
        phase = role == TargetRole.SOURCE ? Phase.OPENING_SOURCE : Phase.OPENING_DESTINATION;
        OperationKind operationKind = role == TargetRole.SOURCE
                ? OperationKind.OPEN_SOURCE
                : OperationKind.OPEN_DESTINATION;
        long operationId = nextOperation(config.openTimeoutTicks(), operationKind);
        emit("open_requested", Map.of(
                "operation_id", operationId,
                "target", target,
                "role", role.name(),
                "task_id", active.task.id()
        ));
        port.requestOpen(operationId, target, this::onOpenResult);
    }

    private void onOpenResult(OpenResult result) {
        if (result.operationId() != expectedOperationId) {
            emit("late_open_result", Map.of(
                    "operation_id", result.operationId(),
                    "target", result.target(),
                    "opened", result.opened()
            ));
            if (result.opened() && result.windowId() > 0) port.closeWindow(result.windowId());
            return;
        }

        expectedOperationId = -1;
        expectedOperationKind = null;
        if (!result.opened()) {
            if (cargoOwned) retryDestination("open_rejected");
            else retrySourceAtTail("open_rejected");
            return;
        }

        activeWindowId = result.windowId();
        TransferDirection direction = cargoOwned
                ? TransferDirection.TO_CONTAINER
                : TransferDirection.TO_PLAYER;
        phase = cargoOwned ? Phase.DEPOSITING_DESTINATION : Phase.TAKING_SOURCE;
        OperationKind operationKind = direction == TransferDirection.TO_PLAYER
                ? OperationKind.TAKE_SOURCE
                : OperationKind.DEPOSIT_DESTINATION;
        long operationId = nextOperation(config.transferTimeoutTicks(), operationKind);
        emit("transfer_requested", Map.of(
                "operation_id", operationId,
                "window_id", activeWindowId,
                "direction", direction.name(),
                "task_id", active.task.id()
        ));
        port.requestTransfer(
                operationId,
                activeWindowId,
                direction,
                active.task.itemId(),
                active.task.quantity(),
                this::onTransferResult
        );
    }

    private void onTransferResult(TransferResult result) {
        if (result.operationId() != expectedOperationId) {
            if (result.moved() > 0) cargoOwned = true;
            emit("late_transfer_result", Map.of(
                    "operation_id", result.operationId(),
                    "moved", result.moved(),
                    "reconciliation_required", result.moved() > 0
            ));
            return;
        }
        expectedOperationId = -1;
        expectedOperationKind = null;
        if (result.moved() <= 0) {
            closeActiveWindow();
            if (cargoOwned) retryDestination("nothing_deposited");
            else failActive("nothing_at_source");
            return;
        }

        emit("transfer_confirmed", Map.of(
                "task_id", active.task.id(),
                "moved", result.moved(),
                "cargo_owned_before", cargoOwned
        ));
        closeActiveWindow();
        if (!cargoOwned) {
            cargoOwned = true;
            active.destinationAttempts = 0;
            active.eligibleAtTick = logicalTick;
            phase = Phase.IDLE;
            return;
        }

        cargoOwned = false;
        completedTasks++;
        emit("task_completed", Map.of("task_id", active.task.id()));
        active = null;
        phase = Phase.IDLE;
        finishIfTerminal();
    }

    private void retrySourceAtTail(String reason) {
        closeActiveWindow();
        active.sourceAttempts++;
        if (active.sourceAttempts >= config.maxSourceAttempts()) {
            failActive("source_retry_exhausted:" + reason);
            return;
        }
        active.eligibleAtTick = logicalTick + config.retryBackoffTicks();
        emit("task_requeued", Map.of(
                "task_id", active.task.id(),
                "attempt", active.sourceAttempts,
                "reason", reason,
                "queue_position", "tail"
        ));
        queue.addLast(active);
        active = null;
        phase = Phase.WAITING_TO_RETRY;
    }

    private void retryDestination(String reason) {
        closeActiveWindow();
        active.destinationAttempts++;
        if (active.destinationAttempts >= config.maxDestinationAttempts()) {
            phase = Phase.BLOCKED;
            emit("cargo_blocked", Map.of(
                    "task_id", active.task.id(),
                    "attempts", active.destinationAttempts,
                    "reason", reason
            ));
            return;
        }
        active.eligibleAtTick = logicalTick + config.retryBackoffTicks();
        phase = Phase.WAITING_TO_RETRY;
        emit("cargo_retry_scheduled", Map.of(
                "task_id", active.task.id(),
                "attempt", active.destinationAttempts,
                "reason", reason
        ));
    }

    private void failActive(String reason) {
        cargoOwned = false;
        failedTasks++;
        emit("task_failed", Map.of("task_id", active.task.id(), "reason", reason));
        active = null;
        phase = Phase.IDLE;
        finishIfTerminal();
    }

    private void finishIfTerminal() {
        if (active == null && queue.isEmpty() && completedTasks + failedTasks == totalTasks) {
            phase = Phase.DONE;
            emit("orchestration_done", Map.of(
                    "completed", completedTasks,
                    "failed", failedTasks,
                    "total", totalTasks
            ));
        }
    }

    private void blockForAmbiguousTransfer(long operationId, OperationKind operationKind) {
        // A vanilla click can mutate server inventory even when its response missed our local
        // deadline. Conservatively retain cargo ownership and stop all subsequent work until a
        // runtime reconciliation compares the player and container postconditions.
        cargoOwned = true;
        phase = Phase.BLOCKED;
        emit("transfer_reconciliation_required", Map.of(
                "task_id", active.task.id(),
                "operation_id", operationId,
                "operation_kind", operationKind.name()
        ));
    }

    private long nextOperation(int timeoutTicks, OperationKind operationKind) {
        expectedOperationId = ++operationSequence;
        expectedOperationKind = operationKind;
        operationDeadline = logicalTick + timeoutTicks;
        return expectedOperationId;
    }

    private void closeActiveWindow() {
        if (activeWindowId > 0) {
            port.closeWindow(activeWindowId);
            activeWindowId = -1;
        }
    }

    private void emit(String type, Map<String, ?> rawFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        rawFields.forEach(fields::put);
        events.add(new Event(logicalTick, type, fields));
    }
}
