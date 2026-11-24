package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaneCapacityReportTest {
    @Test
    void reportsSpareDedicatedLanes() {
        var report = LaneCapacityReport.assess(
                50, 12, 2, List.of("end_crystal", "firework_rocket"), 9, 4, 1, 0);

        assertEquals(LaneCapacityReport.Status.READY, report.status());
        assertEquals(8, report.spareLanes());
        assertTrue(report.canOrganize());
        assertFalse(report.canOrganizeWithImportStaging(false));
        assertTrue(report.canOrganizeWithImportStaging(true));
    }

    @Test
    void reportsExactShortfall() {
        var report = LaneCapacityReport.assess(
                50, 5, 1, List.of("a", "b", "c", "d", "e", "f"), 6, 0, 0, 0);

        assertEquals(LaneCapacityReport.Status.INSUFFICIENT_LANES, report.status());
        assertEquals(2, report.laneShortfall());
        assertFalse(report.canOrganize());
        assertFalse(report.canOrganizeWithImportStaging(false));
        assertTrue(report.canOrganizeWithImportStaging(true));
    }

    @Test
    void legacyShulkersRequireFreshScanEvenWhenRawCapacityIsEnough() {
        var report = LaneCapacityReport.assess(
                50, 20, 1, List.of("end_crystal"), 2, 0, 0, 3);

        assertEquals(LaneCapacityReport.Status.NEEDS_FRESH_SCAN, report.status());
        assertFalse(report.canOrganize());
        assertFalse(report.canOrganizeWithImportStaging(true));
    }

    @Test
    void distinguishesLaneCountFromPerLaneStorageShortfall() {
        var storage = LaneStorageCapacity.assess(
                List.of(LaneStorageCapacity.Demand.calculate("end_crystal", 10, List.of(), 1)),
                List.of(new LaneStorageCapacity.Lane(1, 0, 64, 0, 5)));
        var report = LaneCapacityReport.assess(
                50, 1, 0, List.of("end_crystal"), 0, 0, 0, 0, storage);

        assertEquals(LaneCapacityReport.Status.INSUFFICIENT_LANE_STORAGE, report.status());
        assertEquals(0, report.laneShortfall(), "the lane count itself is sufficient");
        assertFalse(report.canOrganize());
        assertTrue(report.canOrganizeWithImportStaging(true));
    }

    @Test
    void safetyOverridePreservesCalculatedLaneGeometry() {
        var report = LaneCapacityReport.assess(
                50, 12, 2, List.of("end_crystal"), 2, 0, 0, 0)
                .withStatus(LaneCapacityReport.Status.NEEDS_FRESH_CONTAINER_SCAN);

        assertEquals(LaneCapacityReport.Status.NEEDS_FRESH_CONTAINER_SCAN, report.status());
        assertEquals(12, report.detectedLanes());
        assertEquals(10, report.assignableLanes());
        assertEquals(9, report.spareLanes());
        assertFalse(report.canOrganize());
    }
}
