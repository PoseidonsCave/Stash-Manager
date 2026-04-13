package com.zenith.plugin.stashmanager.api;

import com.sun.net.httpserver.HttpServer;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerIndex;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP API server for external integrations
 * (Grafana, n8n, custom dashboards, etc.).
 *
 * Endpoints:
 *   GET  /api/v1/status       — Scanner state and statistics
 *   GET  /api/v1/containers   — Paginated container list (?page=1&size=50)
 *   GET  /api/v1/search       — Search by item name (?item=diamond)
 *   GET  /api/v1/stats        — Aggregate statistics (JSON)
 *   GET  /api/v1/metrics      — Prometheus-compatible metrics
 *   GET  /api/v1/organizer    — Organizer state and progress
 *   GET  /api/v1/regions      — Saved region list
 *   POST /api/v1/webhook/test — Webhook connectivity test
 */
public class ApiServer implements AutoCloseable {

    private HttpServer server;
    private volatile boolean running = false;

    private final StashManagerConfig config;
    private final StashManagerModule module;
    private final ContainerIndex index;
    private final DatabaseManager database;

    public ApiServer(StashManagerConfig config, StashManagerModule module,
                     ContainerIndex index, DatabaseManager database) {
        this.config = config;
        this.module = module;
        this.index = index;
        this.database = database;
    }

    public boolean start() {
        if (!config.apiEnabled) return false;
        if (running) return true;

        try {
            server = HttpServer.create(new InetSocketAddress(config.apiBindAddress, config.apiPort), 0);
            server.setExecutor(Executors.newFixedThreadPool(config.apiThreads));

            ApiHandler handler = new ApiHandler(config, module, index, database);

            server.createContext("/api/v1/status", handler::handleStatus);
            server.createContext("/api/v1/containers", handler::handleContainers);
            server.createContext("/api/v1/search", handler::handleSearch);
            server.createContext("/api/v1/stats", handler::handleStats);
            server.createContext("/api/v1/metrics", handler::handleMetrics);
            server.createContext("/api/v1/organizer", handler::handleOrganizer);
            server.createContext("/api/v1/regions", handler::handleRegions);
            server.createContext("/api/v1/webhook/test", handler::handleWebhookTest);

            server.start();
            running = true;
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to start API server on port " + config.apiPort, e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
            running = false;
        }
    }
}
