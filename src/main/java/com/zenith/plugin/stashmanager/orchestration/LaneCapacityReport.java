package com.zenith.plugin.stashmanager.orchestration;

import java.util.List;

/** Snapshot of the latest lane-capacity audit. */
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
        List<String> storageClasses,
        LaneStorageCapacity.Report laneStorage,
        List<LaneStorageCapacity.Lane> lanes) {

    public enum Status {
        READY,
        INSUFFICIENT_LANES,
        INSUFFICIENT_LANE_STORAGE,
        NEEDS_FRESH_SCAN,
        NEEDS_FRESH_CONTAINER_SCAN,
        REGION_NOT_DEFINED,
        NO_SCANNED_CONTAINERS,
        NO_LANES_DETECTED
    }

    public LaneCapacityReport {
        storageClasses = storageClasses == null ? List.of() : List.copyOf(storageClasses);
        laneStorage = laneStorage == null
                ? LaneStorageCapacity.assess(List.of(), List.of())
                : laneStorage;
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
    }

    public static LaneCapacityReport unavailable(Status status) {
        return new LaneCapacityReport(status, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, List.of(), LaneStorageCapacity.assess(List.of(), List.of()), List.of());
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
        return assess(regionContainers, detectedLanes, protectedLanes, storageClasses,
                bulkShulkers, emptyShulkers, mixedShulkers, unclassifiedShulkers, null);
    }

    public static LaneCapacityReport assess(
            int regionContainers,
            int detectedLanes,
            int protectedLanes,
            List<String> storageClasses,
            int bulkShulkers,
            int emptyShulkers,
            int mixedShulkers,
            int unclassifiedShulkers,
            LaneStorageCapacity.Report laneStorage) {
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
        } else if (laneStorage != null && !laneStorage.feasible()) {
            status = Status.INSUFFICIENT_LANE_STORAGE;
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
                classes,
                laneStorage,
                laneStorage == null ? List.of() : java.util.stream.Stream.concat(
                        laneStorage.allocations().stream().map(LaneStorageCapacity.Allocation::lane),
                        laneStorage.unallocatedLanes().stream())
                        .distinct()
                        .toList());
    }

    public boolean canOrganize() {
        return status == Status.READY;
    }

    /** Lane shortages may reconcile into explicit import staging; stale scan states never may. */
    public boolean canOrganizeWithImportStaging(boolean importStagingAvailable) {
        if (canOrganize()) return true;
        if (!importStagingAvailable) return false;
        return status == Status.INSUFFICIENT_LANES
                || status == Status.INSUFFICIENT_LANE_STORAGE
                || status == Status.NO_LANES_DETECTED;
    }

    /** Override status without discarding calculated capacity. */
    public LaneCapacityReport withStatus(Status replacement) {
        return new LaneCapacityReport(
                replacement,
                regionContainers,
                detectedLanes,
                protectedLanes,
                assignableLanes,
                requiredStorageClasses,
                laneShortfall,
                spareLanes,
                bulkShulkers,
                emptyShulkers,
                mixedShulkers,
                unclassifiedShulkers,
                storageClasses,
                laneStorage,
                lanes);
    }

    public LaneCapacityReport withLanes(List<LaneStorageCapacity.Lane> replacement) {
        return new LaneCapacityReport(
                status,
                regionContainers,
                detectedLanes,
                protectedLanes,
                assignableLanes,
                requiredStorageClasses,
                laneShortfall,
                spareLanes,
                bulkShulkers,
                emptyShulkers,
                mixedShulkers,
                unclassifiedShulkers,
                storageClasses,
                laneStorage,
                replacement);
    }
}
