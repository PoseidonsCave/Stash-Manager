package com.zenith.plugin.stashmanager.organizer;

import java.util.List;

/** Requires a return chest's live slot snapshot to settle before a deposit click. */
final class KitReturnTargetStability<T> {
    enum Result { SETTLING, STABLE, TIMED_OUT }

    record Observation(Result result, int changedSlots, int stableTicks, int observedTicks) { }

    private List<T> snapshot;
    private int stableTicks;
    private int observedTicks;

    void reset() {
        snapshot = null;
        stableTicks = 0;
        observedTicks = 0;
    }

    List<T> snapshot() { return snapshot; }

    Observation observe(List<T> current, int requiredStableTicks, int maxObservedTicks) {
        if (current == null || requiredStableTicks < 2 || maxObservedTicks < requiredStableTicks)
            throw new IllegalArgumentException("invalid return target stability gate");
        observedTicks++;
        int changed = changedSlots(snapshot, current);
        if (snapshot == null || changed > 0) {
            snapshot = List.copyOf(current);
            stableTicks = 1;
        } else {
            stableTicks++;
        }
        Result result = stableTicks >= requiredStableTicks ? Result.STABLE
                : observedTicks >= maxObservedTicks ? Result.TIMED_OUT : Result.SETTLING;
        return new Observation(result, changed, stableTicks, observedTicks);
    }

    private static int changedSlots(List<?> previous, List<?> current) {
        if (previous == null) return current.size();
        int changed = Math.abs(previous.size() - current.size());
        int shared = Math.min(previous.size(), current.size());
        for (int slot = 0; slot < shared; slot++) {
            if (!java.util.Objects.equals(previous.get(slot), current.get(slot))) changed++;
        }
        return changed;
    }
}
