package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.index.IndexExporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the coordinate-free lane plan as XLSX. */
public final class LaneReportExporter {
    private LaneReportExporter() {}

    private static final int STYLE_TITLE = 1;
    private static final int STYLE_SUBTITLE = 2;
    private static final int STYLE_SECTION = 3;
    private static final int STYLE_LABEL = 4;
    private static final int STYLE_VALUE = 5;
    private static final int STYLE_HEADER = 6;
    private static final int STYLE_BODY = 7;
    private static final int STYLE_STRIPE = 8;
    private static final int STYLE_SUCCESS = 9;
    private static final int STYLE_WARNING = 10;
    private static final int STYLE_DANGER = 11;
    private static final int STYLE_NOTE = 12;
    private static final int STYLE_DECIMAL = 13;
    private static final int STYLE_DECIMAL_STRIPE = 14;

    public static byte[] exportWorkbook(LaneCapacityReport report) {
        LaneConstructionPlan construction = LaneConstructionPlan.assess(report.laneStorage());
        ByteArrayOutputStream output = new ByteArrayOutputStream(24_576);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", packageRelationships());
            put(zip, "docProps/core.xml", coreProperties());
            put(zip, "docProps/app.xml", appProperties());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml", overviewSheet(report, construction));
            put(zip, "xl/worksheets/sheet2.xml", actionPlanSheet(construction));
            put(zip, "xl/worksheets/sheet3.xml", laneAuditSheet(report, construction));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create lane report workbook", e);
        }
        return output.toByteArray();
    }

    private static String overviewSheet(
            LaneCapacityReport report,
            LaneConstructionPlan construction) {
        SheetBuilder sheet = new SheetBuilder(new double[]{31, 22, 22, 56});
        sheet.mergedText(1, 1, 4, "Your Stash Lane Plan", STYLE_TITLE, 28);
        sheet.mergedText(2, 1, 4,
                "Built from your latest scan. Coordinates are left out on purpose, so this is safe to share with your group.",
                STYLE_SUBTITLE, 22);

        sheet.section(4, "Are you ready?", 4);
        sheet.metric(5, "Where things stand", friendlyStatus(report), statusStyle(report));
        sheet.metric(6, "Can the bot organize now?", report.canOrganize() ? "Yes — you're good to go" : "Not yet",
                report.canOrganize() ? STYLE_SUCCESS : STYLE_DANGER);
        sheet.metric(7, "Containers in the scan", report.regionContainers());
        sheet.metric(8, "Lanes found", report.detectedLanes());
        sheet.metric(9, "Lanes we're leaving alone", report.protectedLanes());
        sheet.metric(10, "Lanes we can use", report.assignableLanes());
        sheet.metric(11, "Item lanes needed", report.requiredStorageClasses());
        sheet.metric(12, "Lanes still free", report.spareLanes());
        sheet.metric(13, "More lanes needed", report.laneShortfall(),
                report.laneShortfall() > 0 ? STYLE_DANGER : STYLE_SUCCESS);

        sheet.section(15, "What you need to build", 4);
        sheet.metric(16, "New lanes to make", construction.newLanesToBuild(),
                construction.newLanesToBuild() > 0 ? STYLE_WARNING : STYLE_SUCCESS);
        sheet.metric(17, "Lanes that need more chests", construction.existingLanesToExpand(),
                construction.existingLanesToExpand() > 0 ? STYLE_WARNING : STYLE_SUCCESS);
        sheet.metric(18, "Double chests to place", construction.doubleChestsToAdd(),
                construction.doubleChestsToAdd() > 0 ? STYLE_WARNING : STYLE_SUCCESS);
        sheet.metric(19, "Double chests needed altogether",
                construction.requiredDedicatedDoubleChests());
        sheet.decimalMetric(20, "Double-chest space you already have",
                construction.existingAssignableDoubleChestEquivalent());

        sheet.section(22, "What's in the stash", 4);
        sheet.metric(23, "Shulker spots available",
                report.laneStorage().totalAssignableShulkerSlots());
        sheet.metric(24, "Shulker spots needed",
                report.laneStorage().totalRequiredShulkerSlots());
        sheet.metric(25, "Needed spots with nowhere to go",
                report.laneStorage().unassignedRequiredShulkerSlots());
        sheet.metric(26, "Ready-to-sort shulkers", report.bulkShulkers());
        sheet.metric(27, "Empty shulkers", report.emptyShulkers());
        sheet.metric(28, "Mixed shulkers we're leaving alone", report.mixedShulkers());
        sheet.metric(29, "Shulkers we still need to identify", report.unclassifiedShulkers());

        sheet.section(31, "What to do next", 4);
        String nextStep = construction.requirements().isEmpty()
                ? "You don't need to build anything. Check All Lanes, and if the status above says you're good to go, you can start organizing."
                : "Head over to What to Build and work through that list. When you're done, scan the stash again before you organize.";
        sheet.mergedText(32, 1, 4, nextStep,
                construction.requirements().isEmpty() ? STYLE_SUCCESS : STYLE_WARNING, 38);
        sheet.mergedText(34, 1, 4,
                "Quick rule: one double chest holds 54 shulker boxes, and every exact item type gets a lane of its own.",
                STYLE_NOTE, 34);
        return sheet.finish(null, null, null);
    }

    private static String actionPlanSheet(LaneConstructionPlan construction) {
        String[] headers = {
                "Order", "What to do", "Item", "Exact item ID", "Lane",
                "Spots right now", "Spots needed", "Lane size (double chests)",
                "Double chests to add", "Spots after the work", "Extra spots",
                "What this means"
        };
        SheetBuilder sheet = new SheetBuilder(new double[]{10, 22, 25, 31, 12, 20, 21, 21, 20, 18, 21, 62});
        sheet.mergedText(1, 1, headers.length, "What to Build", STYLE_TITLE, 28);
        sheet.mergedText(2, 1, headers.length,
                "Start at the top and work your way down. Run another scan when you're finished.",
                STYLE_SUBTITLE, 22);
        sheet.header(4, headers);

        int row = 5;
        int priority = 1;
        for (LaneConstructionPlan.Requirement requirement : construction.requirements()) {
            boolean build = requirement.action() == LaneConstructionPlan.Action.BUILD_NEW_LANE;
            int plannedCapacity = requirement.currentShulkerSlots()
                    + requirement.doubleChestsToAdd()
                    * LaneConstructionPlan.SHULKER_SLOTS_PER_DOUBLE_CHEST;
            int spareAfter = Math.max(0, plannedCapacity - requirement.targetShulkerSlots());
            String item = IndexExporter.toReadableName(requirement.demand().storageClass());
            String recommendation = build
                    ? "Make a new lane just for " + item + ". Give it at least "
                        + requirement.requiredDoubleChests() + " double chest(s)."
                    : "Add " + requirement.doubleChestsToAdd() + " double chest(s) to Lane "
                        + requirement.lane().id() + ", then use that lane for " + item + ".";
            int bodyStyle = row % 2 == 0 ? STYLE_STRIPE : STYLE_BODY;
            sheet.row(row++, new Cell[]{
                    Cell.number(priority++, bodyStyle),
                    Cell.text(build ? "MAKE A NEW LANE" : "MAKE THIS LANE BIGGER", STYLE_WARNING),
                    Cell.text(item, bodyStyle),
                    Cell.text(requirement.demand().storageClass(), bodyStyle),
                    Cell.text(build ? "NEW" : String.valueOf(requirement.lane().id()), bodyStyle),
                    Cell.number(requirement.currentShulkerSlots(), bodyStyle),
                    Cell.number(requirement.targetShulkerSlots(), bodyStyle),
                    Cell.number(requirement.requiredDoubleChests(), bodyStyle),
                    Cell.number(requirement.doubleChestsToAdd(), STYLE_WARNING),
                    Cell.number(plannedCapacity, bodyStyle),
                    Cell.number(spareAfter, bodyStyle),
                    Cell.text(recommendation, bodyStyle)
            }, 32);
        }
        if (construction.requirements().isEmpty()) {
            sheet.mergedText(row++, 1, headers.length,
                    "You're all set — the latest scan doesn't show anything you need to build.", STYLE_SUCCESS, 28);
        }
        return sheet.finish(4, headers.length, Math.max(4, row - 1));
    }

    private static String laneAuditSheet(
            LaneCapacityReport report,
            LaneConstructionPlan construction) {
        String[] headers = {
                "How it's being used", "Lane", "Item", "Exact item ID",
                "Shulker spots now", "Double-chest space now",
                "Shulker spots needed", "Smallest lane that'll work", "Chests to add",
                "Extra spots afterward", "What this means"
        };
        SheetBuilder sheet = new SheetBuilder(new double[]{19, 12, 25, 31, 21, 27, 21, 21, 20, 22, 58});
        sheet.mergedText(1, 1, headers.length, "Every Lane at a Glance", STYLE_TITLE, 28);
        sheet.mergedText(2, 1, headers.length,
                "This is every lane we found, plus any new ones you need. Lane numbers don't reveal stash coordinates.",
                STYLE_SUBTITLE, 22);
        sheet.header(4, headers);

        Map<Integer, LaneStorageCapacity.Allocation> allocations = new HashMap<>();
        for (LaneStorageCapacity.Allocation allocation : report.laneStorage().allocations()) {
            allocations.put(allocation.lane().id(), allocation);
        }
        Map<Integer, LaneConstructionPlan.Requirement> expansions = new HashMap<>();
        for (LaneConstructionPlan.Requirement requirement : construction.requirements()) {
            if (requirement.action() == LaneConstructionPlan.Action.EXPAND_EXISTING_LANE
                    && requirement.lane() != null) {
                expansions.put(requirement.lane().id(), requirement);
            }
        }
        Set<Integer> spareLaneIds = new HashSet<>();
        for (LaneStorageCapacity.Lane lane : report.laneStorage().unallocatedLanes()) {
            spareLaneIds.add(lane.id());
        }

        int row = 5;
        for (LaneStorageCapacity.Lane lane : report.lanes().stream()
                .sorted(Comparator.comparingInt(LaneStorageCapacity.Lane::id)).toList()) {
            LaneStorageCapacity.Allocation allocation = allocations.get(lane.id());
            LaneConstructionPlan.Requirement expansion = expansions.get(lane.id());
            int bodyStyle = row % 2 == 0 ? STYLE_STRIPE : STYLE_BODY;
            if (allocation != null) {
                LaneStorageCapacity.Demand demand = allocation.demand();
                sheet.auditRow(row++, "READY", STYLE_SUCCESS, String.valueOf(lane.id()),
                        demand, lane.shulkerSlots(), 0, allocation.spareShulkerSlots(),
                        "This lane already has enough room.", bodyStyle);
            } else if (expansion != null) {
                int spare = lane.shulkerSlots()
                        + expansion.doubleChestsToAdd()
                        * LaneConstructionPlan.SHULKER_SLOTS_PER_DOUBLE_CHEST
                        - expansion.targetShulkerSlots();
                sheet.auditRow(row++, "MAKE BIGGER", STYLE_WARNING, String.valueOf(lane.id()),
                        expansion.demand(), lane.shulkerSlots(), expansion.doubleChestsToAdd(), spare,
                        "Add the listed chests before you organize.", bodyStyle);
            } else if (spareLaneIds.contains(lane.id())) {
                sheet.auditRow(row++, "FREE", STYLE_SUCCESS, String.valueOf(lane.id()),
                        null, lane.shulkerSlots(), 0, lane.shulkerSlots(),
                        "Nothing needs this lane yet, so keep it free for later.", bodyStyle);
            } else {
                sheet.auditRow(row++, "LEFT ALONE", STYLE_NOTE, String.valueOf(lane.id()),
                        null, lane.shulkerSlots(), 0, 0,
                        "We're not touching this lane because its contents aren't normal bulk storage.", bodyStyle);
            }
        }
        for (LaneConstructionPlan.Requirement requirement : construction.requirements()) {
            if (requirement.action() != LaneConstructionPlan.Action.BUILD_NEW_LANE) continue;
            int plannedCapacity = requirement.doubleChestsToAdd()
                    * LaneConstructionPlan.SHULKER_SLOTS_PER_DOUBLE_CHEST;
            int bodyStyle = row % 2 == 0 ? STYLE_STRIPE : STYLE_BODY;
            sheet.auditRow(row++, "NEW LANE", STYLE_WARNING, "NEW", requirement.demand(),
                    0, requirement.doubleChestsToAdd(),
                    plannedCapacity - requirement.targetShulkerSlots(),
                    "Make this lane before you organize.", bodyStyle);
        }
        if (row == 5) {
            sheet.mergedText(row++, 1, headers.length,
                    "The latest scan didn't find any lanes.", STYLE_NOTE, 28);
        }
        return sheet.finish(4, headers.length, Math.max(4, row - 1));
    }

    private static int statusStyle(LaneCapacityReport report) {
        if (report.canOrganize()) return STYLE_SUCCESS;
        return switch (report.status()) {
            case INSUFFICIENT_LANES, INSUFFICIENT_LANE_STORAGE -> STYLE_DANGER;
            default -> STYLE_WARNING;
        };
    }

    private static String friendlyStatus(LaneCapacityReport report) {
        return switch (report.status()) {
            case READY -> "Good to go";
            case INSUFFICIENT_LANES -> "You need a few more lanes";
            case INSUFFICIENT_LANE_STORAGE -> "Some lanes are too small";
            case NEEDS_FRESH_SCAN -> "Run a fresh scan first";
            case NEEDS_FRESH_CONTAINER_SCAN -> "Scan the chests again first";
            case REGION_NOT_DEFINED -> "Set the stash area first";
            case NO_SCANNED_CONTAINERS -> "Scan the stash first";
            case NO_LANES_DETECTED -> "No lanes found yet";
        };
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """;
    }

    private static String packageRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>
                """;
    }

    private static String coreProperties() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>StashManager Lane Capacity Report</dc:title>
                  <dc:creator>StashManager</dc:creator>
                  <cp:lastModifiedBy>StashManager</cp:lastModifiedBy>
                </cp:coreProperties>
                """;
    }

    private static String appProperties() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                  <Application>StashManager</Application>
                  <TitlesOfParts><vt:vector size="3" baseType="lpstr"><vt:lpstr>Overview</vt:lpstr><vt:lpstr>What to Build</vt:lpstr><vt:lpstr>All Lanes</vt:lpstr></vt:vector></TitlesOfParts>
                </Properties>
                """;
    }

    private static String workbook() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <bookViews><workbookView activeTab="0"/></bookViews>
                  <sheets>
                    <sheet name="Overview" sheetId="1" r:id="rId1"/>
                    <sheet name="What to Build" sheetId="2" r:id="rId2"/>
                    <sheet name="All Lanes" sheetId="3" r:id="rId3"/>
                  </sheets>
                </workbook>
                """;
    }

    private static String workbookRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
                  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="1"><numFmt numFmtId="164" formatCode="0.00"/></numFmts>
                  <fonts count="5">
                    <font><sz val="11"/><name val="Calibri"/><family val="2"/></font>
                    <font><b/><color rgb="FFFFFFFF"/><sz val="16"/><name val="Calibri"/></font>
                    <font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font>
                    <font><b/><color rgb="FF1F2937"/><sz val="11"/><name val="Calibri"/></font>
                    <font><i/><color rgb="FF5B6573"/><sz val="10"/><name val="Calibri"/></font>
                  </fonts>
                  <fills count="9">
                    <fill><patternFill patternType="none"/></fill>
                    <fill><patternFill patternType="gray125"/></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FF0F6B78"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFDCE6F1"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFF3F6F8"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFE2F0D9"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFF4CCCC"/><bgColor indexed="64"/></patternFill></fill>
                  </fills>
                  <borders count="2">
                    <border><left/><right/><top/><bottom/><diagonal/></border>
                    <border><left style="thin"><color rgb="FFD0D7DE"/></left><right style="thin"><color rgb="FFD0D7DE"/></right><top style="thin"><color rgb="FFD0D7DE"/></top><bottom style="thin"><color rgb="FFD0D7DE"/></bottom><diagonal/></border>
                  </borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="15">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="0" fontId="4" fillId="0" borderId="0" xfId="0" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="2" fillId="3" borderId="0" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="0" fontId="3" fillId="4" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"><alignment vertical="center"/></xf>
                    <xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="3" fillId="6" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="3" fillId="7" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="3" fillId="8" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="0" fontId="4" fillId="5" borderId="1" xfId="0" applyFill="1" applyFont="1"><alignment vertical="center" wrapText="1"/></xf>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1"><alignment vertical="center"/></xf>
                    <xf numFmtId="164" fontId="0" fillId="5" borderId="1" xfId="0" applyFill="1" applyNumberFormat="1"><alignment vertical="center"/></xf>
                  </cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }

    private record Cell(String value, boolean number, int style) {
        static Cell text(String value, int style) {
            return new Cell(value == null ? "" : value, false, style);
        }

        static Cell number(Number value, int style) {
            return new Cell(String.valueOf(value), true, style);
        }
    }

    private static final class SheetBuilder {
        private final StringBuilder rows = new StringBuilder(16_384);
        private final StringBuilder merges = new StringBuilder();
        private final double[] widths;
        private int mergeCount;

        private SheetBuilder(double[] widths) {
            this.widths = widths;
        }

        private void mergedText(int row, int firstColumn, int lastColumn, String value, int style, int height) {
            row(row, new Cell[]{Cell.text(value, style)}, height);
            merges.append("<mergeCell ref=\"")
                    .append(columnName(firstColumn)).append(row).append(':')
                    .append(columnName(lastColumn)).append(row).append("\"/>");
            mergeCount++;
        }

        private void section(int row, String title, int lastColumn) {
            mergedText(row, 1, lastColumn, title, STYLE_SECTION, 22);
        }

        private void metric(int row, String label, Object value) {
            metric(row, label, value, STYLE_VALUE);
        }

        private void metric(int row, String label, Object value, int valueStyle) {
            Cell valueCell = value instanceof Number number
                    ? Cell.number(number, valueStyle)
                    : Cell.text(String.valueOf(value), valueStyle);
            row(row, new Cell[]{Cell.text(label, STYLE_LABEL), valueCell}, 21);
        }

        private void decimalMetric(int row, String label, double value) {
            row(row, new Cell[]{Cell.text(label, STYLE_LABEL), Cell.number(value, STYLE_DECIMAL)}, 21);
        }

        private void header(int row, String[] headers) {
            Cell[] cells = new Cell[headers.length];
            for (int i = 0; i < headers.length; i++) cells[i] = Cell.text(headers[i], STYLE_HEADER);
            row(row, cells, 32);
        }

        private void auditRow(
                int row,
                String status,
                int statusStyle,
                String laneId,
                LaneStorageCapacity.Demand demand,
                int currentSlots,
                int chestsToAdd,
                int spareSlots,
                String notes,
                int bodyStyle) {
            int requiredSlots = demand == null ? 0 : demand.requiredShulkerSlots();
            int requiredChests = demand == null ? 0
                    : LaneConstructionPlan.doubleChestsForSlots(requiredSlots);
            row(row, new Cell[]{
                    Cell.text(status, statusStyle),
                    Cell.text(laneId, bodyStyle),
                    Cell.text(demand == null ? "" : IndexExporter.toReadableName(demand.storageClass()), bodyStyle),
                    Cell.text(demand == null ? "" : demand.storageClass(), bodyStyle),
                    Cell.number(currentSlots, bodyStyle),
                    Cell.number(currentSlots / (double) LaneConstructionPlan.SHULKER_SLOTS_PER_DOUBLE_CHEST,
                            bodyStyle == STYLE_STRIPE ? STYLE_DECIMAL_STRIPE : STYLE_DECIMAL),
                    Cell.number(requiredSlots, bodyStyle),
                    Cell.number(requiredChests, bodyStyle),
                    Cell.number(chestsToAdd, chestsToAdd > 0 ? STYLE_WARNING : bodyStyle),
                    Cell.number(Math.max(0, spareSlots), bodyStyle),
                    Cell.text(notes, bodyStyle)
            }, 30);
        }

        private void row(int rowNumber, Cell[] cells, int height) {
            rows.append("<row r=\"").append(rowNumber).append("\" ht=\"")
                    .append(height).append("\" customHeight=\"1\">");
            for (int i = 0; i < cells.length; i++) {
                Cell cell = cells[i];
                String reference = columnName(i + 1) + rowNumber;
                if (cell.number()) {
                    rows.append("<c r=\"").append(reference).append("\" s=\"")
                            .append(cell.style()).append("\"><v>")
                            .append(escapeXml(cell.value())).append("</v></c>");
                } else {
                    rows.append("<c r=\"").append(reference).append("\" s=\"")
                            .append(cell.style()).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escapeXml(cell.value())).append("</t></is></c>");
                }
            }
            rows.append("</row>");
        }

        private String finish(Integer headerRow, Integer lastColumn, Integer lastRow) {
            StringBuilder xml = new StringBuilder(24_576);
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    .append("<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\">");
            if (headerRow != null) {
                xml.append("<pane ySplit=\"").append(headerRow)
                        .append("\" topLeftCell=\"A").append(headerRow + 1)
                        .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>");
            }
            xml.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>");
            for (int i = 0; i < widths.length; i++) {
                xml.append("<col min=\"").append(i + 1).append("\" max=\"")
                        .append(i + 1).append("\" width=\"").append(widths[i])
                        .append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols><sheetData>").append(rows).append("</sheetData>");
            if (headerRow != null && lastColumn != null && lastRow != null) {
                xml.append("<autoFilter ref=\"A").append(headerRow).append(':')
                        .append(columnName(lastColumn)).append(lastRow).append("\"/>");
            }
            if (mergeCount > 0) {
                xml.append("<mergeCells count=\"").append(mergeCount).append("\">")
                        .append(merges).append("</mergeCells>");
            }
            xml.append("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
                    .append("</worksheet>");
            return xml.toString();
        }
    }

    private static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        int value = column;
        while (value > 0) {
            value--;
            name.append((char) ('A' + value % 26));
            value /= 26;
        }
        return name.reverse().toString();
    }

    private static String escapeXml(String value) {
        StringBuilder escaped = new StringBuilder(value == null ? 0 : value.length() + 16);
        if (value == null) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
