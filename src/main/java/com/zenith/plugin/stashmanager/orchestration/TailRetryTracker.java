package com.zenith.plugin.stashmanager.orchestration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic retry accounting for work that can be safely returned to a queue tail.
 * Attempt totals are keyed by stable task identity and are independent of queue length.
 */
public final class TailRetryTracker<K> {
    public enum Disposition {
        RETRY_TAIL,
        TERMINAL_FAILURE
    }

    public record Decision(int attempt, int maxAttempts, Disposition disposition) {
        public boolean shouldRetry() {
            return disposition == Disposition.RETRY_TAIL;
        }
    }

    private final int maxAttempts;
    private final Map<K, Integer> failedAttempts = new HashMap<>();

    public TailRetryTracker(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    public Decision recordFailure(K taskId) {
        Objects.requireNonNull(taskId, "taskId");
        int attempt = failedAttempts.merge(taskId, 1, Integer::sum);
        return new Decision(
            attempt,
            maxAttempts,
            attempt < maxAttempts ? Disposition.RETRY_TAIL : Disposition.TERMINAL_FAILURE
        );
    }

    /** Returns the number of failed attempts preceding this successful recovery. */
    public int recordSuccess(K taskId) {
        Objects.requireNonNull(taskId, "taskId");
        Integer attempts = failedAttempts.remove(taskId);
        return attempts != null ? attempts : 0;
    }

    public void clear() {
        failedAttempts.clear();
    }
}
