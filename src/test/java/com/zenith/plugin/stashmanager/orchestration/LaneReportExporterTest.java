package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneReportExporterTest {
    @Test
    void exportsStyledCoordinateFreeWorkbookWithSeparateActionPlan() throws Exception {
        var allocatedLane = new LaneStorageCapacity.Lane(1, 10, 70, 20, 12);
        var spareLane = new LaneStorageCapacity.Lane(2, 11, 70, 20, 8);
        var protectedLane = new LaneStorageCapacity.Lane(3, 12, 70, 20, 6);
        var allocatedDemand = LaneStorageCapacity.Demand.calculate(
                "diamond_pickaxe[fortune]", 2, List.of(10), 27);
        var oversizedDemand = LaneStorageCapacity.Demand.calculate(
                "end_crystal", 20, List.of(), 1);
        var newLaneDemand = LaneStorageCapacity.Demand.calculate(
                "totem_of_undying", 60, List.of(), 1);
        var storage = new LaneStorageCapacity.Report(
                List.of(new LaneStorageCapacity.Allocation(allocatedDemand, allocatedLane, 11)),
                List.of(oversizedDemand, newLaneDemand),
                List.of(spareLane),
                20, 81, 80);
        var report = LaneCapacityReport.assess(
                25, 3, 1,
                List.of("diamond_pickaxe[fortune]", "end_crystal", "totem_of_undying"),
                1, 0, 0, 0, storage)
                .withLanes(List.of(allocatedLane, spareLane, protectedLane));

        byte[] workbook = LaneReportExporter.exportWorkbook(report);
        assertArrayEquals(new byte[]{'P', 'K'}, new byte[]{workbook[0], workbook[1]});

        Map<String, String> entries = unzipText(workbook);
        var xmlParser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (var entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".rels")) {
                xmlParser.parse(new ByteArrayInputStream(
                        entry.getValue().getBytes(StandardCharsets.UTF_8)));
            }
        }
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"));
        assertTrue(entries.containsKey("xl/worksheets/sheet2.xml"));
        assertTrue(entries.containsKey("xl/worksheets/sheet3.xml"));

        String workbookXml = entries.get("xl/workbook.xml");
        assertTrue(workbookXml.contains("name=\"Overview\""));
        assertTrue(workbookXml.contains("name=\"What to Build\""));
        assertTrue(workbookXml.contains("name=\"All Lanes\""));

        String overview = entries.get("xl/worksheets/sheet1.xml");
        assertTrue(overview.contains("What you need to build"));
        assertTrue(overview.contains("Double chests to place"));
        assertTrue(overview.contains("What to do next"));

        String actions = entries.get("xl/worksheets/sheet2.xml");
        assertTrue(actions.contains("MAKE A NEW LANE"));
        assertTrue(actions.contains("MAKE THIS LANE BIGGER"));
        assertTrue(actions.contains("End Crystal"));
        assertTrue(actions.contains("Totem Of Undying"));
        assertTrue(actions.contains("autoFilter"));
        assertTrue(actions.contains("state=\"frozen\""));

        String audit = entries.get("xl/worksheets/sheet3.xml");
        assertTrue(audit.contains("READY"));
        assertTrue(audit.contains("LEFT ALONE"));
        assertTrue(audit.contains("NEW LANE"));

        String allXml = String.join("\n", entries.values());
        assertFalse(allXml.contains("Top X"));
        assertFalse(allXml.contains("Top Y"));
        assertFalse(allXml.contains("Top Z"));
        assertTrue(entries.get("xl/styles.xml").contains("FF1F4E78"));
    }

    private static Map<String, String> unzipText(byte[] workbook) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                entries.put(entry.getName(), output.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
