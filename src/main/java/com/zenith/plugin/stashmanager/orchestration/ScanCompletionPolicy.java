package com.zenith.plugin.stashmanager.orchestration;

/** Rejects incomplete scans before they can be treated as a trusted snapshot. */
public final class ScanCompletionPolicy {
    private static final int ABSOLUTE_FAILURE_ALLOWANCE = 10;
    private static final double FAILURE_RATE_ALLOWANCE = 0.05;

    private ScanCompletionPolicy() { }

    public static int allowedFailures(int containersFound) {
        return Math.max(
                ABSOLUTE_FAILURE_ALLOWANCE,
                (int) Math.floor(Math.max(0, containersFound) * FAILURE_RATE_ALLOWANCE));
    }

    public static int unresolvedContainers(
            int containersFound,
            int containersIndexed,
            int containersFailed) {
        long unresolved = (long) containersFound - containersIndexed - containersFailed;
        if (unresolved <= 0) return 0;
        return unresolved > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) unresolved;
    }

    public static Assessment assess(
            int containersFound,
            int containersIndexed,
            int containersFailed) {
        int allowed = allowedFailures(containersFound);
        if (containersFound < 0 || containersIndexed < 0 || containersFailed < 0) {
            return new Assessment(false, 0, Integer.MAX_VALUE, allowed,
                    "invalid_scan_counters");
        }

        long processed = (long) containersIndexed + containersFailed;
        if (processed > containersFound) {
            return new Assessment(false, 0, containersFailed, allowed,
                    "processed_count_exceeds_discovered_count");
        }

        int unresolved = unresolvedContainers(
                containersFound, containersIndexed, containersFailed);
        long effective = (long) containersFailed + unresolved;
        int effectiveFailures = effective > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) effective;
        boolean accepted = effectiveFailures <= allowed;
        return new Assessment(
                accepted,
                unresolved,
                effectiveFailures,
                allowed,
                accepted ? null : "scan_failure_budget_exceeded");
    }

    public static boolean shouldAbort(
            int containersFound,
            int containersIndexed,
            int containersFailed) {
        return !assess(containersFound, containersIndexed, containersFailed).accepted();
    }

    public record Assessment(
            boolean accepted,
            int unresolved,
            int effectiveFailures,
            int allowedFailures,
            String rejectionReason) {
    }
}
