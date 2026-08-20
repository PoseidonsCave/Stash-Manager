package com.zenith.plugin.stashmanager.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.LaneConstructionPlan;
import com.zenith.plugin.stashmanager.index.IndexExporter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Serves JSON endpoints and Prometheus metrics.
public class ApiHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final String TEXT_TYPE = "text/plain; charset=utf-8";

    private final StashManagerConfig config;
    private final StashManagerModule module;
    private final ContainerIndex index;
    private final DatabaseManager database;

    public ApiHandler(StashManagerConfig config, StashManagerModule module,
                      ContainerIndex index, DatabaseManager database) {
        this.config = config;
        this.module = module;
        this.index = index;
        this.database = database;
    }

    // GET /api/v1/status
    public void handleStatus(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", module.getState().name());
        body.put("index_size", index.size());
        body.put("containers_found", module.getContainersFound());
        body.put("containers_indexed", module.getContainersIndexed());
        body.put("containers_failed", module.getContainersFailed());
        body.put("containers_pending", module.getPendingCount());
        body.put("containers_processed", module.getProcessedCount());
        body.put("scan_processed_ratio", module.getProcessedRatio());
        body.put("scan_success_rate", module.getSuccessRate());
        body.put("scan_failure_rate", module.getFailureRate());
        body.put("scan_preemptions", module.getScanPreemptionCount());
        body.put("scan_preemption_cooldown_remaining_seconds",
            module.getScanPreemptionCooldownRemainingSeconds());
        body.put("organizer_preemptions", module.getOrganizerPreemptionCount());
        body.put("organizer_preemption_cooldown_remaining_seconds",
            module.getOrganizerPreemptionCooldownRemainingSeconds());
        body.put("proxy_control_grace_remaining_seconds",
            module.getProxyControlGraceRemainingSeconds());
        body.put("proxy_control_job", module.getControlledJob().name());
        body.put("last_scan", index.timeSinceLastScan());
        body.put("database_connected", database != null && database.isInitialized());
        body.put("database_write_healthy", index.isDatabaseWriteHealthy());
        body.put("database_write_failures", index.getDatabaseWriteFailures());
        body.put("import_chest_blocks", index.getImportChestBlockCount());

        if (config.pos1 != null && config.pos2 != null) {
            body.put("region_pos1", Map.of("x", config.pos1[0], "y", config.pos1[1], "z", config.pos1[2]));
            body.put("region_pos2", Map.of("x", config.pos2[0], "y", config.pos2[1], "z", config.pos2[2]));
            int[] dims = module.getRegionDimensions();
            if (dims != null) {
                body.put("region_dimensions", Map.of("x", dims[0], "y", dims[1], "z", dims[2]));
            }
        }

        sendJson(exchange, 200, body);
    }

    // GET /api/v1/containers?page=1&size=50
    public void handleContainers(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        int page = parseIntParam(params, "page", 1);
        int size = Math.min(parseIntParam(params, "size", 50), 200);

        List<ContainerEntry> entries;
        int totalCount;

        // Query durable data first.
        if (database != null && database.isInitialized()) {
            try {
                entries = database.getContainersPage(page, size);
                totalCount = database.getContainerCount();
            } catch (Exception e) {
                sendError(exchange, 500, "Database query failed: " + e.getMessage());
                return;
            }
        } else {
            entries = index.getPage(page, size);
            totalCount = index.size();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page);
        body.put("page_size", size);
        body.put("total_count", totalCount);
        body.put("total_pages", Math.max(1, (int) Math.ceil((double) totalCount / size)));
        body.put("containers", entries.stream().map(this::containerToMap).toList());

        sendJson(exchange, 200, body);
    }

    // GET /api/v1/search?item=diamond
    public void handleSearch(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        String item = params.get("item");

        if (item == null || item.isBlank()) {
            sendError(exchange, 400, "Missing required parameter: item");
            return;
        }

        List<ContainerEntry> results;
        int totalItemCount;

        if (database != null && database.isInitialized()) {
            try {
                results = database.searchContainers(item);
                totalItemCount = database.getTotalItemCount(item);
            } catch (Exception e) {
                sendError(exchange, 500, "Database query failed: " + e.getMessage());
                return;
            }
        } else {
            results = index.search(item);
            totalItemCount = index.totalItemCount(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", item);
        body.put("readable_name", IndexExporter.toReadableName(item));
        body.put("total_item_count", totalItemCount);
        body.put("container_count", results.size());
        body.put("containers", results.stream().map(this::containerToMap).toList());

        sendJson(exchange, 200, body);
    }

    // GET /api/v1/stats
    public void handleStats(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, Object> body = new LinkedHashMap<>();

        if (database != null && database.isInitialized()) {
            try {
                body.putAll(database.getStatistics());
            } catch (Exception e) {
                sendError(exchange, 500, "Database query failed: " + e.getMessage());
                return;
            }
        } else {
            // Fall back to the in-memory index.
            body.put("total_containers", index.size());
            body.put("last_scan_timestamp", index.getLastScanTimestamp());

            long totalItems = 0;
            Set<String> uniqueTypes = new HashSet<>();
            Map<String, Integer> byType = new LinkedHashMap<>();

            for (ContainerEntry entry : index.getAll()) {
                totalItems += entry.totalItems();
                for (String itemId : entry.items().keySet()) {
                    uniqueTypes.add(itemId);
                }
                byType.merge(entry.blockType(), 1, Integer::sum);
            }

            body.put("total_items", totalItems);
            body.put("unique_item_types", uniqueTypes.size());
            body.put("containers_by_type", byType);
        }

        // Add live scanner state.
        body.put("scanner_state", module.getState().name());
        body.put("scan_containers_found", module.getContainersFound());
        body.put("scan_containers_indexed", module.getContainersIndexed());
        body.put("scan_containers_failed", module.getContainersFailed());
        body.put("scan_containers_pending", module.getPendingCount());
        body.put("scan_containers_processed", module.getProcessedCount());
        body.put("scan_processed_ratio", module.getProcessedRatio());
        body.put("scan_success_rate", module.getSuccessRate());
        body.put("scan_failure_rate", module.getFailureRate());

        sendJson(exchange, 200, body);
    }

    // GET /api/v1/metrics (Prometheus format)
    public void handleMetrics(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        StringBuilder sb = new StringBuilder();

        sb.append("# HELP stash_containers_total Total number of indexed containers\n");
        sb.append("# TYPE stash_containers_total gauge\n");
        sb.append("stash_containers_total ").append(index.size()).append('\n');

        sb.append("# HELP stash_scanner_state Current scanner state (0=IDLE,1=ZONE_SCANNING,2=WALKING,3=OPENING,4=READING,5=CLOSING,6=WALKING_TO_ZONE,7=RETURNING,8=YIELDED,9=DONE)\n");
        sb.append("# TYPE stash_scanner_state gauge\n");
        sb.append("stash_scanner_state ").append(module.getState().ordinal()).append('\n');

        sb.append("# HELP stash_scan_containers_found Containers found in current/last scan\n");
        sb.append("# TYPE stash_scan_containers_found gauge\n");
        sb.append("stash_scan_containers_found ").append(module.getContainersFound()).append('\n');

        sb.append("# HELP stash_scan_containers_indexed Containers successfully indexed in current/last scan\n");
        sb.append("# TYPE stash_scan_containers_indexed gauge\n");
        sb.append("stash_scan_containers_indexed ").append(module.getContainersIndexed()).append('\n');

        sb.append("# HELP stash_scan_containers_failed Containers failed to index in current/last scan\n");
        sb.append("# TYPE stash_scan_containers_failed gauge\n");
        sb.append("stash_scan_containers_failed ").append(module.getContainersFailed()).append('\n');

        sb.append("# HELP stash_scan_preemptions_total Cooperative scanner handoffs in the current or last scan\n");
        sb.append("# TYPE stash_scan_preemptions_total gauge\n");
        sb.append("stash_scan_preemptions_total ").append(module.getScanPreemptionCount()).append('\n');

        sb.append("# HELP stash_scan_preemption_cooldown_remaining_seconds Minimum scanner yield hold remaining\n");
        sb.append("# TYPE stash_scan_preemption_cooldown_remaining_seconds gauge\n");
        sb.append("stash_scan_preemption_cooldown_remaining_seconds ")
            .append(module.getScanPreemptionCooldownRemainingSeconds()).append('\n');

        sb.append("# HELP stash_organizer_preemptions_total Cooperative organizer handoffs in the current or last run\n");
        sb.append("# TYPE stash_organizer_preemptions_total gauge\n");
        sb.append("stash_organizer_preemptions_total ")
            .append(module.getOrganizerPreemptionCount()).append('\n');

        sb.append("# HELP stash_organizer_preemption_cooldown_remaining_seconds Minimum organizer yield hold remaining\n");
        sb.append("# TYPE stash_organizer_preemption_cooldown_remaining_seconds gauge\n");
        sb.append("stash_organizer_preemption_cooldown_remaining_seconds ")
            .append(module.getOrganizerPreemptionCooldownRemainingSeconds()).append('\n');

        sb.append("# HELP stash_proxy_control_active Whether proxy control is holding a resumable stash job\n");
        sb.append("# TYPE stash_proxy_control_active gauge\n");
        sb.append("stash_proxy_control_active ")
            .append(module.getControlledJob()
                    == com.zenith.plugin.stashmanager.orchestration.JobContinuanceManager.Job.NONE ? 0 : 1)
            .append('\n');

        sb.append("# HELP stash_proxy_control_grace_remaining_seconds Time before proxy control discards the active checkpoint\n");
        sb.append("# TYPE stash_proxy_control_grace_remaining_seconds gauge\n");
        sb.append("stash_proxy_control_grace_remaining_seconds ")
            .append(module.getProxyControlGraceRemainingSeconds()).append('\n');

        sb.append("# HELP stash_scan_containers_pending Containers pending in current scan\n");
        sb.append("# TYPE stash_scan_containers_pending gauge\n");
        sb.append("stash_scan_containers_pending ").append(module.getPendingCount()).append('\n');

        sb.append("# HELP stash_scan_containers_processed Containers processed in current or last scan\n");
        sb.append("# TYPE stash_scan_containers_processed gauge\n");
        sb.append("stash_scan_containers_processed ").append(module.getProcessedCount()).append('\n');

        sb.append("# HELP stash_scan_processed_ratio Fraction of found containers already processed in current or last scan (0..1)\n");
        sb.append("# TYPE stash_scan_processed_ratio gauge\n");
        sb.append("stash_scan_processed_ratio ").append(module.getProcessedRatio()).append('\n');

        sb.append("# HELP stash_scan_success_rate Fraction of found containers successfully indexed in current or last scan (0..1)\n");
        sb.append("# TYPE stash_scan_success_rate gauge\n");
        sb.append("stash_scan_success_rate ").append(module.getSuccessRate()).append('\n');

        sb.append("# HELP stash_scan_failure_rate Fraction of found containers that failed in current or last scan (0..1)\n");
        sb.append("# TYPE stash_scan_failure_rate gauge\n");
        sb.append("stash_scan_failure_rate ").append(module.getFailureRate()).append('\n');

        sb.append("# HELP stash_last_scan_timestamp_seconds Unix timestamp of last scan completion\n");
        sb.append("# TYPE stash_last_scan_timestamp_seconds gauge\n");
        sb.append("stash_last_scan_timestamp_seconds ").append(index.getLastScanTimestamp() / 1000.0).append('\n');

        sb.append("# HELP stash_database_connected Whether the database is connected (1=yes, 0=no)\n");
        sb.append("# TYPE stash_database_connected gauge\n");
        sb.append("stash_database_connected ").append(database != null && database.isInitialized() ? 1 : 0).append('\n');

        sb.append("# HELP stash_database_write_healthy Whether the latest attempted container persistence succeeded (1=yes, 0=no)\n");
        sb.append("# TYPE stash_database_write_healthy gauge\n");
        sb.append("stash_database_write_healthy ").append(index.isDatabaseWriteHealthy() ? 1 : 0).append('\n');

        sb.append("# HELP stash_database_write_failures_total Container persistence failures since plugin startup\n");
        sb.append("# TYPE stash_database_write_failures_total counter\n");
        sb.append("stash_database_write_failures_total ").append(index.getDatabaseWriteFailures()).append('\n');

        if (database != null && database.isInitialized()) {
            try {
                Map<String, Object> stats = database.getStatistics();
                if (stats.containsKey("total_items")) {
                    sb.append("# HELP stash_items_total Total number of items across all containers\n");
                    sb.append("# TYPE stash_items_total gauge\n");
                    sb.append("stash_items_total ").append(stats.get("total_items")).append('\n');
                }
                if (stats.containsKey("unique_item_types")) {
                    sb.append("# HELP stash_unique_item_types Number of unique item types\n");
                    sb.append("# TYPE stash_unique_item_types gauge\n");
                    sb.append("stash_unique_item_types ").append(stats.get("unique_item_types")).append('\n');
                }
                if (stats.containsKey("total_shulkers")) {
                    sb.append("# HELP stash_shulkers_total Total number of shulker boxes in containers\n");
                    sb.append("# TYPE stash_shulkers_total gauge\n");
                    sb.append("stash_shulkers_total ").append(stats.get("total_shulkers")).append('\n');
                }
            } catch (Exception ignored) {
                // Keep metrics available when the database fails.
            }
        }

        // Organizer
        var organizer = module.getOrganizer();
        if (organizer != null) {
            sb.append("# HELP stash_organizer_active Whether the organizer is running (1=yes, 0=no)\n");
            sb.append("# TYPE stash_organizer_active gauge\n");
            sb.append("stash_organizer_active ").append(organizer.isActive() ? 1 : 0).append('\n');

            sb.append("# HELP stash_organizer_tasks_completed Organizer tasks completed in current run\n");
            sb.append("# TYPE stash_organizer_tasks_completed gauge\n");
            sb.append("stash_organizer_tasks_completed ").append(organizer.getCompletedTasks()).append('\n');

            sb.append("# HELP stash_organizer_tasks_total Organizer tasks planned in current run\n");
            sb.append("# TYPE stash_organizer_tasks_total gauge\n");
            sb.append("stash_organizer_tasks_total ").append(organizer.getTotalTasks()).append('\n');

            appendGauge(sb, "stash_organizer_staged_shulkers",
                    "Reconciled bulk shulkers stored temporarily in imports during the current run",
                    organizer.getStagedShulkers());
            appendGauge(sb, "stash_organizer_staging_storage_classes",
                    "Item classes routed to temporary import staging during the current run",
                    organizer.getStagingStorageClassCount());
            appendGauge(sb, "stash_organizer_permanent_lane_gaps",
                    "Item classes still lacking a suitable permanent lane",
                    organizer.getPermanentLaneGaps());
        }

        LaneCapacityReport capacity = module.getLaneCapacityReport();
        sb.append("# HELP stash_lane_capacity_ready Whether the latest scan is trusted and has enough dedicated lanes\n");
        sb.append("# TYPE stash_lane_capacity_ready gauge\n");
        sb.append("stash_lane_capacity_ready ").append(capacity.canOrganize() ? 1 : 0).append('\n');

        sb.append("# HELP stash_lane_capacity_status Current lane audit status as a labeled one-hot gauge\n");
        sb.append("# TYPE stash_lane_capacity_status gauge\n");
        sb.append("stash_lane_capacity_status{status=\"")
                .append(capacity.status().name().toLowerCase(Locale.ROOT)).append("\"} 1\n");

        appendGauge(sb, "stash_lanes_detected", "Storage lanes detected in the configured region",
                capacity.detectedLanes());
        appendGauge(sb, "stash_lanes_protected", "Storage lanes reserved by mixed, empty, or unclassified shulkers",
                capacity.protectedLanes());
        appendGauge(sb, "stash_lanes_assignable", "Storage lanes available for exact bulk item classes",
                capacity.assignableLanes());
        appendGauge(sb, "stash_lanes_required", "Unique exact bulk item classes requiring dedicated lanes",
                capacity.requiredStorageClasses());
        appendGauge(sb, "stash_lanes_spare", "Assignable lanes remaining after one lane per bulk class",
                capacity.spareLanes());
        appendGauge(sb, "stash_lanes_shortfall", "Additional dedicated lanes required before organizing",
                capacity.laneShortfall());
        appendGauge(sb, "stash_lane_shulker_slots_assignable", "Total shulker-box slots across assignable lanes",
                capacity.laneStorage().totalAssignableShulkerSlots());
        appendGauge(sb, "stash_lane_shulker_slots_required", "Shulker-box slots required by managed bulk classes",
                capacity.laneStorage().totalRequiredShulkerSlots());
        appendGauge(sb, "stash_lane_shulker_slots_compacted", "Shulker-box slots required after compatible partial boxes are consolidated",
                capacity.laneStorage().totalCompactedShulkerSlots());
        appendGauge(sb, "stash_lane_shulker_slots_reclaimable", "Existing shulker-box slots reclaimable through consolidation",
                capacity.laneStorage().totalReclaimableShulkerSlots());
        appendGauge(sb, "stash_lane_stack_sizes_unresolved", "Storage classes using conservative non-stackable capacity",
                capacity.laneStorage().unresolvedStackSizeClasses().size());
        appendGauge(sb, "stash_lane_storage_classes_unassigned", "Bulk classes which cannot fit any assignable lane",
                capacity.laneStorage().unassigned().size());
        appendGauge(sb, "stash_lane_shulker_slots_unassigned_required", "Required slots belonging to unassigned bulk classes",
                capacity.laneStorage().unassignedRequiredShulkerSlots());
        LaneConstructionPlan construction = LaneConstructionPlan.assess(capacity.laneStorage());
        appendGauge(sb, "stash_lane_construction_new_lanes", "New dedicated storage lanes which must be built",
                construction.newLanesToBuild());
        appendGauge(sb, "stash_lane_construction_expansions", "Existing lanes which require additional double chests",
                construction.existingLanesToExpand());
        appendGauge(sb, "stash_lane_construction_double_chests_to_add", "Total double chests required by the current construction plan",
                construction.doubleChestsToAdd());
        appendGauge(sb, "stash_lane_required_dedicated_double_chests", "Minimum double chests required across all dedicated item lanes",
                construction.requiredDedicatedDoubleChests());
        appendGauge(sb, "stash_lane_compacted_required_dedicated_double_chests", "Minimum double chests required after compatible partial boxes are consolidated",
                construction.compactedRequiredDedicatedDoubleChests());
        appendGauge(sb, "stash_shulkers_bulk", "Physical homogeneous bulk shulkers in the latest index",
                capacity.bulkShulkers());
        appendGauge(sb, "stash_shulkers_empty", "Physical empty shulkers in the latest index",
                capacity.emptyShulkers());
        appendGauge(sb, "stash_shulkers_mixed", "Physical mixed-content shulkers preserved from bulk organization",
                capacity.mixedShulkers());
        appendGauge(sb, "stash_shulkers_unclassified", "Legacy or incomplete shulkers requiring a fresh scan",
                capacity.unclassifiedShulkers());
        appendGauge(sb, "stash_import_chest_blocks", "Block positions assigned as organizer intake inventories",
                index.getImportChestBlockCount());

        sendText(exchange, 200, sb.toString());
    }

    private static void appendGauge(StringBuilder output, String name, String help, Number value) {
        output.append("# HELP ").append(name).append(' ').append(help).append('\n');
        output.append("# TYPE ").append(name).append(" gauge\n");
        output.append(name).append(' ').append(value).append('\n');
    }

    // POST /api/v1/webhook/test
    public void handleWebhookTest(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "POST")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("message", "Webhook connectivity confirmed");
        body.put("timestamp", System.currentTimeMillis());
        body.put("scanner_state", module.getState().name());

        sendJson(exchange, 200, body);
    }
    // GET /api/v1/organizer
    public void handleOrganizer(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        var organizer = module.getOrganizer();
        if (organizer == null) {
            body.put("available", false);
        } else {
            body.put("available", true);
            body.put("state", organizer.getState().name());
            body.put("active", organizer.isActive());
            body.put("completed_tasks", organizer.getCompletedTasks());
            body.put("total_tasks", organizer.getTotalTasks());
            body.put("using_import_staging", organizer.isUsingImportStaging());
            body.put("staged_shulkers", organizer.getStagedShulkers());
            body.put("staging_storage_classes", organizer.getStagingStorageClassCount());
            body.put("permanent_lane_gaps", organizer.getPermanentLaneGaps());
            body.put("yielded", organizer.isYielded());
            body.put("yielded_from_state", organizer.getYieldedFromState() == null
                    ? null : organizer.getYieldedFromState().name());
            body.put("preemptions", module.getOrganizerPreemptionCount());
            body.put("preemption_cooldown_remaining_seconds",
                    module.getOrganizerPreemptionCooldownRemainingSeconds());
            body.put("proxy_control_grace_remaining_seconds",
                    module.getProxyControlGraceRemainingSeconds());
            body.put("status", organizer.getStatus());
        }
        LaneCapacityReport capacity = module.getLaneCapacityReport();
        body.put("import_chest_blocks", index.getImportChestBlockCount());
        body.put("import_chests", index.getImportChests().stream()
                .map(pos -> Map.of("x", pos[0], "y", pos[1], "z", pos[2]))
                .toList());
        body.put("lane_capacity", Map.ofEntries(
                Map.entry("status", capacity.status().name()),
                Map.entry("detected_lanes", capacity.detectedLanes()),
                Map.entry("protected_lanes", capacity.protectedLanes()),
                Map.entry("assignable_lanes", capacity.assignableLanes()),
                Map.entry("required_storage_classes", capacity.requiredStorageClasses()),
                Map.entry("spare_lanes", capacity.spareLanes()),
                Map.entry("lane_shortfall", capacity.laneShortfall()),
                Map.entry("bulk_shulkers", capacity.bulkShulkers()),
                Map.entry("empty_shulkers", capacity.emptyShulkers()),
                Map.entry("mixed_shulkers", capacity.mixedShulkers()),
                Map.entry("unclassified_shulkers", capacity.unclassifiedShulkers()),
                Map.entry("storage_classes", capacity.storageClasses())
        ));
        body.put("lane_storage", Map.of(
                "assignable_shulker_slots", capacity.laneStorage().totalAssignableShulkerSlots(),
                "required_shulker_slots", capacity.laneStorage().totalRequiredShulkerSlots(),
                "compacted_required_shulker_slots", capacity.laneStorage().totalCompactedShulkerSlots(),
                "reclaimable_shulker_slots", capacity.laneStorage().totalReclaimableShulkerSlots(),
                "unassigned_required_shulker_slots", capacity.laneStorage().unassignedRequiredShulkerSlots(),
                "unresolved_stack_size_classes", capacity.laneStorage().unresolvedStackSizeClasses(),
                "allocations", capacity.laneStorage().allocations().stream().map(allocation -> Map.ofEntries(
                        Map.entry("storage_class", allocation.demand().storageClass()),
                        Map.entry("lane_id", allocation.lane().id()),
                        Map.entry("loose_items", allocation.demand().looseItems()),
                        Map.entry("items_in_existing_shulkers", allocation.demand().itemsInExistingShulkers()),
                        Map.entry("max_stack_size", allocation.demand().maxStackSize()),
                        Map.entry("stack_size_resolved", allocation.demand().stackSizeResolved()),
                        Map.entry("items_per_shulker", allocation.demand().itemsPerShulker()),
                        Map.entry("required_shulker_slots", allocation.demand().requiredShulkerSlots()),
                        Map.entry("compacted_required_shulker_slots", allocation.demand().compactedShulkerSlots()),
                        Map.entry("reclaimable_shulker_slots", allocation.demand().reclaimableShulkerSlots()),
                        Map.entry("lane_shulker_slots", allocation.lane().shulkerSlots()),
                        Map.entry("spare_shulker_slots", allocation.spareShulkerSlots())
                )).toList(),
                "unassigned", capacity.laneStorage().unassigned().stream().map(demand -> Map.ofEntries(
                        Map.entry("storage_class", demand.storageClass()),
                        Map.entry("required_shulker_slots", demand.requiredShulkerSlots()),
                        Map.entry("compacted_required_shulker_slots", demand.compactedShulkerSlots()),
                        Map.entry("reclaimable_shulker_slots", demand.reclaimableShulkerSlots()),
                        Map.entry("existing_bulk_shulkers", demand.existingBulkShulkers()),
                        Map.entry("items_in_existing_shulkers", demand.itemsInExistingShulkers()),
                        Map.entry("loose_items", demand.looseItems()),
                        Map.entry("max_stack_size", demand.maxStackSize()),
                        Map.entry("stack_size_resolved", demand.stackSizeResolved()),
                        Map.entry("items_per_shulker", demand.itemsPerShulker())
                )).toList()
        ));
        LaneConstructionPlan construction = LaneConstructionPlan.assess(capacity.laneStorage());
        body.put("lane_construction", Map.of(
                "new_lanes_to_build", construction.newLanesToBuild(),
                "existing_lanes_to_expand", construction.existingLanesToExpand(),
                "double_chests_to_add", construction.doubleChestsToAdd(),
                "existing_assignable_double_chest_equivalent",
                construction.existingAssignableDoubleChestEquivalent(),
                "required_dedicated_double_chests", construction.requiredDedicatedDoubleChests(),
                "compacted_required_dedicated_double_chests",
                construction.compactedRequiredDedicatedDoubleChests(),
                "requirements", construction.requirements().stream().map(requirement -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("storage_class", requirement.demand().storageClass());
                    row.put("action", requirement.action().name());
                    if (requirement.lane() != null) {
                        row.put("lane_id", requirement.lane().id());
                    }
                    row.put("current_shulker_slots", requirement.currentShulkerSlots());
                    row.put("target_shulker_slots", requirement.targetShulkerSlots());
                    row.put("required_double_chests", requirement.requiredDoubleChests());
                    row.put("double_chests_to_add", requirement.doubleChestsToAdd());
                    return row;
                }).toList()
        ));
        sendJson(exchange, 200, body);
    }

    // GET /api/v1/regions
    public void handleRegions(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, Object> body = new LinkedHashMap<>();
        if (database != null && database.isInitialized()) {
            try {
                var regions = database.listRegions();
                List<Map<String, Object>> list = new ArrayList<>();
                for (var region : regions) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", region.name());
                    r.put("pos1", Map.of("x", region.pos1()[0], "y", region.pos1()[1], "z", region.pos1()[2]));
                    r.put("pos2", Map.of("x", region.pos2()[0], "y", region.pos2()[1], "z", region.pos2()[2]));
                    list.add(r);
                }
                body.put("regions", list);
                body.put("count", regions.size());
            } catch (Exception e) {
                sendError(exchange, 500, "Database query failed: " + e.getMessage());
                return;
            }
        } else {
            body.put("regions", List.of());
            body.put("count", 0);
        }
        sendJson(exchange, 200, body);
    }
    // Response helpers
    private Map<String, Object> containerToMap(ContainerEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", entry.x());
        map.put("y", entry.y());
        map.put("z", entry.z());
        map.put("block_type", entry.blockType());
        map.put("readable_type", entry.readableBlockType());
        map.put("is_double", entry.isDouble());
        map.put("total_items", entry.totalItems());
        map.put("shulker_count", entry.shulkerCount());
        map.put("timestamp", entry.timestamp());
        if (entry.label() != null) {
            map.put("label", entry.label());
        }
        if (index.isImportChest(entry)) {
            map.put("role", "import");
        }

        // Flatten item counts.
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (var item : entry.items().entrySet()) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("id", item.getKey());
            itemMap.put("name", IndexExporter.toReadableName(item.getKey()));
            itemMap.put("quantity", item.getValue());
            itemsList.add(itemMap);
        }
        map.put("items", itemsList);

        // Include shulker contents.
        if (!entry.shulkerDetails().isEmpty()) {
            List<Map<String, Object>> shulkersList = new ArrayList<>();
            for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
                Map<String, Object> shulkerMap = new LinkedHashMap<>();
                shulkerMap.put("color", shulker.color());
                List<Map<String, Object>> shulkerItems = new ArrayList<>();
                for (var item : shulker.items().entrySet()) {
                    shulkerItems.add(Map.of(
                        "id", item.getKey(),
                        "name", IndexExporter.toReadableName(item.getKey()),
                        "quantity", item.getValue()
                    ));
                }
                shulkerMap.put("items", shulkerItems);
                shulkersList.add(shulkerMap);
            }
            map.put("shulker_details", shulkersList);
        }

        return map;
    }

    private boolean checkMethod(HttpExchange exchange, String expected) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase(expected)) {
            sendError(exchange, 405, "Method not allowed. Expected: " + expected);
            return false;
        }
        return true;
    }

    private boolean checkApiKey(HttpExchange exchange) throws IOException {
        if (config.apiKey == null || config.apiKey.isBlank()) return true;

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length());
            if (config.apiKey.equals(token)) return true;
        }

        sendError(exchange, 401, "Unauthorized: invalid or missing API key");
        return false;
    }

    private Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return params;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private int parseIntParam(Map<String, String> params, String name, int defaultValue) {
        String val = params.get(name);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", TEXT_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }
}
