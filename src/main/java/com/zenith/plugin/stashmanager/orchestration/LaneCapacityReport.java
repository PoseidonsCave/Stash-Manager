package com.zenith.plugin.stashmanager.orchestration;

import java.util.List;

/** Immutable result of auditing scanned storage against the organizer's lane policy. */
public record LaneCapacityReport(
        Status status,
        int regionContainers,
        int detectedLanes,
        int protectedLanes,
        int assignableLanes,
        int requiredStorageClasses,
        int laneShortfall,
        int spareLanes,
        int bulkShulkers,
        int emptyShulkers,
        int mixedShulkers,
        int unclassifiedShulkers,
        List<String> storageClasses) {

    public enum Status {
        READY,
        INSUFFICIENT_LANES,
        NEEDS_FRESH_SCAN,
        REGION_NOT_DEFINED,
        NO_SCANNED_CONTAINERS,
        NO_LANES_DETECTED
    }

    public LaneCapacityReport {
        storageClasses = storageClasses == null ? List.of() : List.copyOf(storageClasses);
    }

    public static LaneCapacityReport unavailable(Status status) {
        return new LaneCapacityReport(status, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, List.of());
    }

    public static LaneCapacityReport assess(
            int regionContainers,
            int detectedLanes,
            int protectedLanes,
            List<String> storageClasses,
            int bulkShulkers,
            int emptyShulkers,
            int mixedShulkers,
            int unclassifiedShulkers) {
        List<String> classes = storageClasses == null ? List.of() : List.copyOf(storageClasses);
        DedicatedLaneCapacity capacity = DedicatedLaneCapacity.assess(
                detectedLanes, protectedLanes, classes.size());

        Status status;
        if (unclassifiedShulkers > 0) {
            status = Status.NEEDS_FRESH_SCAN;
        } else if (capacity.detectedLanes() == 0) {
            status = Status.NO_LANES_DETECTED;
        } else if (!capacity.feasible()) {
            status = Status.INSUFFICIENT_LANES;
        } else {
            status = Status.READY;
        }

        return new LaneCapacityReport(
                status,
                Math.max(0, regionContainers),
                capacity.detectedLanes(),
                capacity.protectedLanes(),
                capacity.assignableLanes(),
                capacity.requiredStorageClasses(),
                capacity.laneShortfall(),
                Math.max(0, capacity.assignableLanes() - capacity.requiredStorageClasses()),
                Math.max(0, bulkShulkers),
                Math.max(0, emptyShulkers),
                Math.max(0, mixedShulkers),
                Math.max(0, unclassifiedShulkers),
                classes);
    }

    public boolean canOrganize() {
        return status == Status.READY;
    }
}
