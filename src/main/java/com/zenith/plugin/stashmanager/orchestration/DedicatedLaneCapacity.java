package com.zenith.plugin.stashmanager.orchestration;

/** Conservative one-storage-class-per-lane capacity audit. */
public record DedicatedLaneCapacity(
        int detectedLanes,
        int protectedLanes,
        int assignableLanes,
        int requiredStorageClasses,
        int laneShortfall) {

    public static DedicatedLaneCapacity assess(
            int detectedLanes, int protectedLanes, int requiredStorageClasses) {
        int detected = Math.max(0, detectedLanes);
        int protectedCount = Math.min(detected, Math.max(0, protectedLanes));
        int assignable = detected - protectedCount;
        int required = Math.max(0, requiredStorageClasses);
        return new DedicatedLaneCapacity(
                detected, protectedCount, assignable, required, Math.max(0, required - assignable));
    }

    public boolean feasible() {
        return laneShortfall == 0;
    }
}
