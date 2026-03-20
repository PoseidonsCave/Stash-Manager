package com.zenith.plugin.stashmanager.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL persistence layer for container scan data.
 * Uses HikariCP connection pooling for thread-safe, efficient DB access.
 */
public class DatabaseManager implements AutoCloseable {

    private HikariDataSource dataSource;
    private volatile boolean initialized = false;

    public boolean initialize(StashManagerConfig config) {
        if (!config.databaseEnabled) return false;

        try {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(config.databaseUrl);
            hikariConfig.setUsername(config.databaseUser);
            hikariConfig.setPassword(config.databasePassword);
            hikariConfig.setMaximumPoolSize(config.databasePoolSize);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setIdleTimeout(300000);
            hikariConfig.setMaxLifetime(600000);
            hikariConfig.setPoolName("StashManager-DB");

            dataSource = new HikariDataSource(hikariConfig);
            createSchema();
            initialized = true;
            return true;
        } catch (Exception e) {
            initialized = false;
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void createSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS containers (
                    id BIGSERIAL PRIMARY KEY,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    block_type VARCHAR(64) NOT NULL,
                    is_double BOOLEAN NOT NULL DEFAULT FALSE,
                    shulker_count INTEGER NOT NULL DEFAULT 0,
                    total_items INTEGER NOT NULL DEFAULT 0,
                    scan_timestamp BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(x, y, z)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS container_items (
                    id BIGSERIAL PRIMARY KEY,
                    container_id BIGINT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
                    item_id VARCHAR(128) NOT NULL,
                    quantity INTEGER NOT NULL,
                    in_shulker BOOLEAN NOT NULL DEFAULT FALSE,
                    shulker_color VARCHAR(32)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS scan_history (
                    id BIGSERIAL PRIMARY KEY,
                    region_pos1_x INTEGER,
                    region_pos1_y INTEGER,
                    region_pos1_z INTEGER,
                    region_pos2_x INTEGER,
                    region_pos2_y INTEGER,
                    region_pos2_z INTEGER,
                    containers_found INTEGER NOT NULL DEFAULT 0,
                    containers_indexed INTEGER NOT NULL DEFAULT 0,
                    containers_failed INTEGER NOT NULL DEFAULT 0,
                    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_container_items_item_id ON container_items(item_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_position ON containers(x, y, z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_block_type ON containers(block_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_scan_ts ON containers(scan_timestamp)");
        }
    }

    // ── Container CRUD ──────────────────────────────────────────────────

    /**
     * Upsert a container entry. Updates existing entry at the same position,
     * or inserts a new one.
     */
    public void upsertContainer(ContainerEntry entry) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long containerId = upsertContainerRow(conn, entry);
                replaceContainerItems(conn, containerId, entry);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private long upsertContainerRow(Connection conn, ContainerEntry entry) throws SQLException {
        String sql = """
            INSERT INTO containers (x, y, z, block_type, is_double, shulker_count, total_items, scan_timestamp, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (x, y, z) DO UPDATE SET
                block_type = EXCLUDED.block_type,
                is_double = EXCLUDED.is_double,
                shulker_count = EXCLUDED.shulker_count,
                total_items = EXCLUDED.total_items,
                scan_timestamp = EXCLUDED.scan_timestamp,
                updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, entry.x());
            ps.setInt(2, entry.y());
            ps.setInt(3, entry.z());
            ps.setString(4, entry.blockType());
            ps.setBoolean(5, entry.isDouble());
            ps.setInt(6, entry.shulkerCount());
            ps.setInt(7, entry.totalItems());
            ps.setLong(8, entry.timestamp());

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void replaceContainerItems(Connection conn, long containerId, ContainerEntry entry) throws SQLException {
        // Delete existing items for this container
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM container_items WHERE container_id = ?")) {
            ps.setLong(1, containerId);
            ps.executeUpdate();
        }

        // Insert direct container items
        String insertSql = "INSERT INTO container_items (container_id, item_id, quantity, in_shulker, shulker_color) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (var item : entry.items().entrySet()) {
                ps.setLong(1, containerId);
                ps.setString(2, item.getKey());
                ps.setInt(3, item.getValue());
                ps.setBoolean(4, false);
                ps.setNull(5, Types.VARCHAR);
                ps.addBatch();
            }

            // Insert shulker detail items
            for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
                for (var item : shulker.items().entrySet()) {
                    ps.setLong(1, containerId);
                    ps.setString(2, item.getKey());
                    ps.setInt(3, item.getValue());
                    ps.setBoolean(4, true);
                    ps.setString(5, shulker.color());
                    ps.addBatch();
                }
            }

            ps.executeBatch();
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────

    public List<ContainerEntry> getAllContainers() throws SQLException {
        if (!initialized) return Collections.emptyList();

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp FROM containers ORDER BY scan_timestamp DESC";
        List<ContainerEntry> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                results.add(buildContainerEntry(conn, rs, id));
            }
        }
        return results;
    }

    public List<ContainerEntry> getContainersPage(int page, int pageSize) throws SQLException {
        if (!initialized) return Collections.emptyList();

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp FROM containers ORDER BY scan_timestamp DESC LIMIT ? OFFSET ?";
        List<ContainerEntry> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, (page - 1) * pageSize);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    results.add(buildContainerEntry(conn, rs, id));
                }
            }
        }
        return results;
    }

    public List<ContainerEntry> searchContainers(String itemSearch) throws SQLException {
        if (!initialized) return Collections.emptyList();

        String sql = """
            SELECT DISTINCT c.id, c.x, c.y, c.z, c.block_type, c.is_double, c.shulker_count, c.scan_timestamp
            FROM containers c
            JOIN container_items ci ON c.id = ci.container_id
            WHERE LOWER(ci.item_id) LIKE ?
            ORDER BY c.scan_timestamp DESC
            """;

        List<ContainerEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + itemSearch.toLowerCase() + "%");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    results.add(buildContainerEntry(conn, rs, id));
                }
            }
        }
        return results;
    }

    public int getContainerCount() throws SQLException {
        if (!initialized) return 0;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM containers");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public int getTotalItemCount(String itemSearch) throws SQLException {
        if (!initialized) return 0;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COALESCE(SUM(quantity), 0) FROM container_items WHERE LOWER(item_id) LIKE ?")) {
            ps.setString(1, "%" + itemSearch.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Returns aggregate statistics: total containers, total items, unique item types,
     * containers by block type, and last scan timestamp.
     */
    public Map<String, Object> getStatistics() throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!initialized) return stats;

        try (Connection conn = dataSource.getConnection()) {
            // Total containers
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM containers");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stats.put("total_containers", rs.getInt(1));
            }

            // Total item stacks
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(quantity), 0) FROM container_items");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stats.put("total_items", rs.getLong(1));
            }

            // Unique item types
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(DISTINCT item_id) FROM container_items");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stats.put("unique_item_types", rs.getInt(1));
            }

            // Containers by block type
            Map<String, Integer> byType = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT block_type, COUNT(*) as cnt FROM containers GROUP BY block_type ORDER BY cnt DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byType.put(rs.getString("block_type"), rs.getInt("cnt"));
                }
            }
            stats.put("containers_by_type", byType);

            // Last scan timestamp
            try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(scan_timestamp) FROM containers");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stats.put("last_scan_timestamp", rs.getLong(1));
            }

            // Total shulker boxes
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(shulker_count), 0) FROM containers");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stats.put("total_shulkers", rs.getInt(1));
            }
        }

        return stats;
    }

    public void clearAll() throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE container_items, containers RESTART IDENTITY CASCADE");
        }
    }

    // ── Scan History ────────────────────────────────────────────────────

    public long recordScanStart(int[] pos1, int[] pos2) throws SQLException {
        if (!initialized) return -1;

        String sql = """
            INSERT INTO scan_history (region_pos1_x, region_pos1_y, region_pos1_z, region_pos2_x, region_pos2_y, region_pos2_z)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pos1[0]);
            ps.setInt(2, pos1[1]);
            ps.setInt(3, pos1[2]);
            ps.setInt(4, pos2[0]);
            ps.setInt(5, pos2[1]);
            ps.setInt(6, pos2[2]);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void recordScanComplete(long scanId, int found, int indexed, int failed) throws SQLException {
        if (!initialized || scanId < 0) return;

        String sql = """
            UPDATE scan_history SET containers_found = ?, containers_indexed = ?, containers_failed = ?, completed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, found);
            ps.setInt(2, indexed);
            ps.setInt(3, failed);
            ps.setLong(4, scanId);
            ps.executeUpdate();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ContainerEntry buildContainerEntry(Connection conn, ResultSet rs, long containerId) throws SQLException {
        int x = rs.getInt("x");
        int y = rs.getInt("y");
        int z = rs.getInt("z");
        String blockType = rs.getString("block_type");
        boolean isDouble = rs.getBoolean("is_double");
        int shulkerCount = rs.getInt("shulker_count");
        long scanTimestamp = rs.getLong("scan_timestamp");

        // Load items
        Map<String, Integer> items = new LinkedHashMap<>();
        List<ContainerEntry.ShulkerDetail> shulkerDetails = new ArrayList<>();
        Map<String, Map<String, Integer>> shulkerItemsByColor = new LinkedHashMap<>();

        try (PreparedStatement itemPs = conn.prepareStatement(
                 "SELECT item_id, quantity, in_shulker, shulker_color FROM container_items WHERE container_id = ?")) {
            itemPs.setLong(1, containerId);

            try (ResultSet itemRs = itemPs.executeQuery()) {
                while (itemRs.next()) {
                    String itemId = itemRs.getString("item_id");
                    int quantity = itemRs.getInt("quantity");
                    boolean inShulker = itemRs.getBoolean("in_shulker");
                    String shulkerColor = itemRs.getString("shulker_color");

                    if (inShulker && shulkerColor != null) {
                        shulkerItemsByColor.computeIfAbsent(shulkerColor, k -> new LinkedHashMap<>())
                            .merge(itemId, quantity, Integer::sum);
                    }
                    // All items go into the main map (same as the in-memory behavior)
                    items.merge(itemId, quantity, Integer::sum);
                }
            }
        }

        for (var entry : shulkerItemsByColor.entrySet()) {
            shulkerDetails.add(new ContainerEntry.ShulkerDetail(entry.getKey(), entry.getValue()));
        }

        return new ContainerEntry(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, scanTimestamp);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        initialized = false;
    }
}
