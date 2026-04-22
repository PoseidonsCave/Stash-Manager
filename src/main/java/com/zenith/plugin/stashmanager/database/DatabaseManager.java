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
            migrateSchema();
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

            // Named regions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS regions (
                    name VARCHAR(128) PRIMARY KEY,
                    pos1_x INTEGER NOT NULL,
                    pos1_y INTEGER NOT NULL,
                    pos1_z INTEGER NOT NULL,
                    pos2_x INTEGER NOT NULL,
                    pos2_y INTEGER NOT NULL,
                    pos2_z INTEGER NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // Key-value config storage
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS config (
                    key VARCHAR(128) PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);

            // Storage chest sorting configuration
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS storage_chests (
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    item_type VARCHAR(128),
                    is_overflow BOOLEAN NOT NULL DEFAULT FALSE,
                    PRIMARY KEY (x, y, z)
                )
                """);

            // Keep-items set (items the organizer should not move)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS keep_items (
                    item_id VARCHAR(128) PRIMARY KEY
                )
                """);

            // Retrieval kits
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS kits (
                    name VARCHAR(128) PRIMARY KEY,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS kit_items (
                    kit_name VARCHAR(128) NOT NULL REFERENCES kits(name) ON DELETE CASCADE,
                    item_id VARCHAR(128) NOT NULL,
                    quantity INTEGER NOT NULL,
                    PRIMARY KEY (kit_name, item_id)
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_kit_items_kit ON kit_items(kit_name)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_container_items_item_id ON container_items(item_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_position ON containers(x, y, z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_block_type ON containers(block_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_scan_ts ON containers(scan_timestamp)");
        }
    }

    private void migrateSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Add label column to containers if missing
            boolean hasLabel = false;
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "containers", "label")) {
                hasLabel = rs.next();
            }
            if (!hasLabel) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE containers ADD COLUMN label VARCHAR(128)");
                }
            }
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
            INSERT INTO containers (x, y, z, block_type, is_double, shulker_count, total_items, scan_timestamp, label, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (x, y, z) DO UPDATE SET
                block_type = EXCLUDED.block_type,
                is_double = EXCLUDED.is_double,
                shulker_count = EXCLUDED.shulker_count,
                total_items = EXCLUDED.total_items,
                scan_timestamp = EXCLUDED.scan_timestamp,
                label = EXCLUDED.label,
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
            ps.setString(9, entry.label());

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

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp, label FROM containers ORDER BY scan_timestamp DESC";
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

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp, label FROM containers ORDER BY scan_timestamp DESC LIMIT ? OFFSET ?";
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
            SELECT DISTINCT c.id, c.x, c.y, c.z, c.block_type, c.is_double, c.shulker_count, c.scan_timestamp, c.label
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

    // ── Named Regions ───────────────────────────────────────────────────

    public void saveRegion(String name, int[] pos1, int[] pos2) throws SQLException {
        if (!initialized) return;

        String sql = """
            INSERT INTO regions (name, pos1_x, pos1_y, pos1_z, pos2_x, pos2_y, pos2_z)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (name) DO UPDATE SET
                pos1_x = EXCLUDED.pos1_x, pos1_y = EXCLUDED.pos1_y, pos1_z = EXCLUDED.pos1_z,
                pos2_x = EXCLUDED.pos2_x, pos2_y = EXCLUDED.pos2_y, pos2_z = EXCLUDED.pos2_z
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, pos1[0]);
            ps.setInt(3, pos1[1]);
            ps.setInt(4, pos1[2]);
            ps.setInt(5, pos2[0]);
            ps.setInt(6, pos2[1]);
            ps.setInt(7, pos2[2]);
            ps.executeUpdate();
        }
    }

    public record SavedRegion(String name, int[] pos1, int[] pos2) {}

    public SavedRegion loadRegion(String name) throws SQLException {
        if (!initialized) return null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT pos1_x, pos1_y, pos1_z, pos2_x, pos2_y, pos2_z FROM regions WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SavedRegion(name,
                    new int[]{rs.getInt("pos1_x"), rs.getInt("pos1_y"), rs.getInt("pos1_z")},
                    new int[]{rs.getInt("pos2_x"), rs.getInt("pos2_y"), rs.getInt("pos2_z")});
            }
        }
    }

    public List<SavedRegion> listRegions() throws SQLException {
        if (!initialized) return Collections.emptyList();

        List<SavedRegion> regions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, pos1_x, pos1_y, pos1_z, pos2_x, pos2_y, pos2_z FROM regions ORDER BY name")) {
            while (rs.next()) {
                regions.add(new SavedRegion(rs.getString("name"),
                    new int[]{rs.getInt("pos1_x"), rs.getInt("pos1_y"), rs.getInt("pos1_z")},
                    new int[]{rs.getInt("pos2_x"), rs.getInt("pos2_y"), rs.getInt("pos2_z")}));
            }
        }
        return regions;
    }

    public boolean deleteRegion(String name) throws SQLException {
        if (!initialized) return false;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM regions WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }
    }

    // ── Container Labels ────────────────────────────────────────────────

    public void updateLabel(int x, int y, int z, String label) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE containers SET label = ? WHERE x = ? AND y = ? AND z = ?")) {
            ps.setString(1, label);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        }
    }

    public String getLabel(int x, int y, int z) throws SQLException {
        if (!initialized) return null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT label FROM containers WHERE x = ? AND y = ? AND z = ?")) {
            ps.setInt(1, x);
            ps.setInt(2, y);
            ps.setInt(3, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("label") : null;
            }
        }
    }

    public Map<String, String> getAllLabels() throws SQLException {
        if (!initialized) return Collections.emptyMap();

        Map<String, String> labels = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT x, y, z, label FROM containers WHERE label IS NOT NULL ORDER BY label")) {
            while (rs.next()) {
                String key = rs.getInt("x") + "," + rs.getInt("y") + "," + rs.getInt("z");
                labels.put(key, rs.getString("label"));
            }
        }
        return labels;
    }

    // ── Config Key-Value Store ──────────────────────────────────────────

    public void setConfig(String key, String value) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO config (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public String getConfig(String key) throws SQLException {
        if (!initialized) return null;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM config WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        }
    }

    public void deleteConfig(String key) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM config WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    // ── Storage Chests (Sorting Config) ─────────────────────────────────

    public record StorageChestConfig(
        List<int[]> chests,
        Map<String, String> chestTypes,
        int[] overflowChest
    ) {}

    public void saveStorageChests(List<int[]> chests, Map<String, String> chestTypes, int[] overflowChest) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement del = conn.createStatement()) {
                del.executeUpdate("DELETE FROM storage_chests");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO storage_chests (x, y, z, sort_order, item_type, is_overflow) VALUES (?, ?, ?, ?, ?, ?)")) {
                int order = 0;
                for (int[] pos : chests) {
                    ps.setInt(1, pos[0]);
                    ps.setInt(2, pos[1]);
                    ps.setInt(3, pos[2]);
                    ps.setInt(4, order++);
                    String key = pos[0] + "," + pos[1] + "," + pos[2];
                    ps.setString(5, chestTypes.get(key));
                    ps.setBoolean(6, overflowChest != null && pos[0] == overflowChest[0]
                        && pos[1] == overflowChest[1] && pos[2] == overflowChest[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    public StorageChestConfig loadStorageChests() throws SQLException {
        if (!initialized) return new StorageChestConfig(Collections.emptyList(), Collections.emptyMap(), null);

        List<int[]> chests = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();
        int[] overflow = null;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT x, y, z, item_type, is_overflow FROM storage_chests ORDER BY sort_order")) {
            while (rs.next()) {
                int[] pos = {rs.getInt("x"), rs.getInt("y"), rs.getInt("z")};
                chests.add(pos);
                String itemType = rs.getString("item_type");
                if (itemType != null) {
                    types.put(pos[0] + "," + pos[1] + "," + pos[2], itemType);
                }
                if (rs.getBoolean("is_overflow")) {
                    overflow = pos;
                }
            }
        }
        return new StorageChestConfig(chests, types, overflow);
    }

    // ── Keep Items ──────────────────────────────────────────────────────

    public void saveKeepItems(Collection<String> itemIds) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement del = conn.createStatement()) {
                del.executeUpdate("DELETE FROM keep_items");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO keep_items (item_id) VALUES (?)")) {
                for (String id : itemIds) {
                    ps.setString(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    public Set<String> loadKeepItems() throws SQLException {
        if (!initialized) return Collections.emptySet();

        Set<String> result = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT item_id FROM keep_items")) {
            while (rs.next()) {
                result.add(rs.getString("item_id"));
            }
        }
        return result;
    }

    // ── Kits ───────────────────────────────────────────────────────────

    public static final int KIT_MAX_SLOTS = 27;

    public static Map<String, Integer> truncateKitItems(Map<String, Integer> items) {
        if (items == null || items.isEmpty()) return Collections.emptyMap();

        Map<String, Integer> truncated = new LinkedHashMap<>();
        int slots = 0;
        for (var entry : items.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            if (slots >= KIT_MAX_SLOTS) break;
            truncated.put(entry.getKey(), entry.getValue());
            slots++;
        }
        return truncated;
    }

    public void saveKit(String name, Map<String, Integer> items) throws SQLException {
        if (!initialized) return;

        Map<String, Integer> persistedItems = truncateKitItems(items);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO kits (name, updated_at) VALUES (?, CURRENT_TIMESTAMP) " +
                            "ON CONFLICT (name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM kit_items WHERE kit_name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO kit_items (kit_name, item_id, quantity) VALUES (?, ?, ?)") ) {
                    for (var item : persistedItems.entrySet()) {
                        ps.setString(1, name);
                        ps.setString(2, item.getKey());
                        ps.setInt(3, item.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public int countKitSlots(String name) throws SQLException {
        if (!initialized) return 0;

        try (Connection conn = dataSource.getConnection()) {
            return countKitSlots(conn, name);
        }
    }

    private int countKitSlots(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM kit_items WHERE kit_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public Map<String, Integer> loadKit(String name) throws SQLException {
        if (!initialized) return Collections.emptyMap();

        Map<String, Integer> items = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT item_id, quantity FROM kit_items WHERE kit_name = ? ORDER BY item_id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.put(rs.getString("item_id"), rs.getInt("quantity"));
                }
            }
        }
        return items;
    }

    public List<String> listKits() throws SQLException {
        if (!initialized) return Collections.emptyList();

        List<String> kits = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM kits ORDER BY name")) {
            while (rs.next()) {
                kits.add(rs.getString("name"));
            }
        }
        return kits;
    }

    public boolean deleteKit(String name) throws SQLException {
        if (!initialized) return false;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM kits WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean setKitItem(String kitName, String itemId, int quantity) throws SQLException {
        if (!initialized) return false;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO kits (name, updated_at) VALUES (?, CURRENT_TIMESTAMP) " +
                            "ON CONFLICT (name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP")) {
                    ps.setString(1, kitName);
                    ps.executeUpdate();
                }

                boolean exists;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM kit_items WHERE kit_name = ? AND item_id = ?")) {
                    ps.setString(1, kitName);
                    ps.setString(2, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }

                if (!exists && countKitSlots(conn, kitName) >= KIT_MAX_SLOTS) {
                    conn.rollback();
                    return false;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO kit_items (kit_name, item_id, quantity) VALUES (?, ?, ?) " +
                            "ON CONFLICT (kit_name, item_id) DO UPDATE SET quantity = EXCLUDED.quantity")) {
                    ps.setString(1, kitName);
                    ps.setString(2, itemId);
                    ps.setInt(3, quantity);
                    ps.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public boolean removeKitItem(String kitName, String itemId) throws SQLException {
        if (!initialized) return false;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM kit_items WHERE kit_name = ? AND item_id = ?")) {
            ps.setString(1, kitName);
            ps.setString(2, itemId);
            return ps.executeUpdate() > 0;
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
        String label = null;
        try { label = rs.getString("label"); } catch (SQLException ignored) {}

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

        return new ContainerEntry(x, y, z, blockType, isDouble, items, shulkerCount, shulkerDetails, scanTimestamp, label);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        initialized = false;
    }
}
