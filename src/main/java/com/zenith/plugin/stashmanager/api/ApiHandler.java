package com.zenith.plugin.stashmanager.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.IndexExporter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HTTP request handlers for the embedded API.
 * All responses are JSON unless otherwise noted (metrics endpoint is Prometheus text format).
 */
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

    // ── GET /api/v1/status ──────────────────────────────────────────────

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
        body.put("last_scan", index.timeSinceLastScan());
        body.put("database_connected", database != null && database.isInitialized());

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

    // ── GET /api/v1/containers?page=1&size=50 ──────────────────────────

    public void handleContainers(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        Map<String, String> params = parseQueryParams(exchange.getRequestURI());
        int page = parseIntParam(params, "page", 1);
        int size = Math.min(parseIntParam(params, "size", 50), 200);

        List<ContainerEntry> entries;
        int totalCount;

        // Prefer DB if available
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

    // ── GET /api/v1/search?item=diamond ─────────────────────────────────

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

    // ── GET /api/v1/stats ───────────────────────────────────────────────

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
            // Fallback to in-memory index stats
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

        // Include scanner state
        body.put("scanner_state", module.getState().name());
        body.put("scan_containers_found", module.getContainersFound());
        body.put("scan_containers_indexed", module.getContainersIndexed());
        body.put("scan_containers_failed", module.getContainersFailed());
        body.put("scan_containers_pending", module.getPendingCount());

        sendJson(exchange, 200, body);
    }

    // ── GET /api/v1/metrics (Prometheus format) ─────────────────────────

    public void handleMetrics(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        if (!checkApiKey(exchange)) return;

        StringBuilder sb = new StringBuilder();

        sb.append("# HELP stash_containers_total Total number of indexed containers\n");
        sb.append("# TYPE stash_containers_total gauge\n");
        sb.append("stash_containers_total ").append(index.size()).append('\n');

        sb.append("# HELP stash_scanner_state Current scanner state (0=IDLE,1=SCANNING,2=WALKING,3=OPENING,4=READING,5=CLOSING,6=WALKING_TO_ZONE,7=RETURNING,8=DONE)\n");
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

        sb.append("# HELP stash_scan_containers_pending Containers pending in current scan\n");
        sb.append("# TYPE stash_scan_containers_pending gauge\n");
        sb.append("stash_scan_containers_pending ").append(module.getPendingCount()).append('\n');

        sb.append("# HELP stash_last_scan_timestamp_seconds Unix timestamp of last scan completion\n");
        sb.append("# TYPE stash_last_scan_timestamp_seconds gauge\n");
        sb.append("stash_last_scan_timestamp_seconds ").append(index.getLastScanTimestamp() / 1000.0).append('\n');

        sb.append("# HELP stash_database_connected Whether the database is connected (1=yes, 0=no)\n");
        sb.append("# TYPE stash_database_connected gauge\n");
        sb.append("stash_database_connected ").append(database != null && database.isInitialized() ? 1 : 0).append('\n');

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
                // Metrics should not fail due to DB issues
            }
        }

        // Organizer metrics
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
        }

        sendText(exchange, 200, sb.toString());
    }

    // ── POST /api/v1/webhook/test ───────────────────────────────────────

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
    // ── GET /api/v1/organizer ──────────────────────────────────────────

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
            body.put("status", organizer.getStatus());
        }
        sendJson(exchange, 200, body);
    }

    // ── GET /api/v1/regions ──────────────────────────────────────────────

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
    // ── Utility ─────────────────────────────────────────────────────────

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

        // Items summary
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (var item : entry.items().entrySet()) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("id", item.getKey());
            itemMap.put("name", IndexExporter.toReadableName(item.getKey()));
            itemMap.put("quantity", item.getValue());
            itemsList.add(itemMap);
        }
        map.put("items", itemsList);

        // Shulker details
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
