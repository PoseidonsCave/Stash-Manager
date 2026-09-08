package com.zenith.plugin.stashmanager.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;

import java.sql.*;
import java.util.*;

// Persists stash data through a PostgreSQL connection pool.
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
                    label VARCHAR(128),
                    hopper_facing VARCHAR(16),
                    inventory_x INTEGER,
                    inventory_y INTEGER,
                    inventory_z INTEGER,
                    inventory_identity_known BOOLEAN NOT NULL DEFAULT FALSE,
                    double_chest_axis VARCHAR(1),
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
                    shulker_color VARCHAR(32),
                    shulker_instance INTEGER
                )
                """);

            // One row per physical shulker, including empty boxes that have no content rows.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS container_shulkers (
                    container_id BIGINT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
                    slot INTEGER NOT NULL,
                    color VARCHAR(32) NOT NULL,
                    PRIMARY KEY (container_id, slot)
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
                    completion_status VARCHAR(16) NOT NULL DEFAULT 'complete',
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

            // Explicit intake inventories. These coordinates are configuration rather than
            // scan data, so they deliberately have no foreign key to containers and survive
            // index clears/rescans. Both halves of a double chest are registered together.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS import_chests (
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (x, y, z)
                )
                """);

            // Keep-items set (items the organizer should not move)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS keep_items (
                    item_id VARCHAR(128) PRIMARY KEY,
                    keep_quantity INTEGER
                )
                """);

            // Organizer column assignments (item_id -> the top chest position of the column it's
            // assigned to). Persisted so item->column mapping stays stable across organize runs
            // instead of being recomputed greedily from scratch (and potentially reshuffled) every time.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS column_assignments (
                    item_id VARCHAR(128) PRIMARY KEY,
                    col_x INTEGER NOT NULL,
                    col_y INTEGER NOT NULL,
                    col_z INTEGER NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_container_shulkers_container ON container_shulkers(container_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_position ON containers(x, y, z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_block_type ON containers(block_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_containers_scan_ts ON containers(scan_timestamp)");

            // Tunnel travel system tables
            TunnelRepository.createSchema(stmt);
        }
    }

    // Expose a pooled connection to other repository classes in this package.
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void migrateSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // PostgreSQL metadata searches without a schema pattern can see another bot's
            // same-named table. That made a migrated schema suppress migrations in every new
            // bot schema. Execute idempotent DDL against the connection's active search_path
            // instead, so each schema is upgraded independently.
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS label VARCHAR(128)");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS hopper_facing VARCHAR(16)");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS inventory_x INTEGER");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS inventory_y INTEGER");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS inventory_z INTEGER");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS inventory_identity_known BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("ALTER TABLE containers ADD COLUMN IF NOT EXISTS double_chest_axis VARCHAR(1)");
            // NULL means keep unlimited.
            stmt.execute("ALTER TABLE keep_items ADD COLUMN IF NOT EXISTS keep_quantity INTEGER");
            // Older rows remain NULL and are treated as legacy aggregate data until rescanned.
            stmt.execute("ALTER TABLE container_items ADD COLUMN IF NOT EXISTS shulker_instance INTEGER");
            // Existing completed rows remain trusted. New scans explicitly transition through
            // running -> complete/aborted so partial snapshots survive restarts as unsafe.
            stmt.execute("ALTER TABLE scan_history ADD COLUMN IF NOT EXISTS completion_status VARCHAR(16) NOT NULL DEFAULT 'complete'");

            // Old organizer runs only upserted current assignments, leaving retired item
            // classes pointed at lanes that had since been reassigned. Keep the newest owner
            // of each physical lane before enforcing the one-class-per-lane invariant.
            stmt.execute("""
                DELETE FROM column_assignments stale
                USING column_assignments keeper
                WHERE stale.col_x = keeper.col_x
                  AND stale.col_y = keeper.col_y
                  AND stale.col_z = keeper.col_z
                  AND (stale.updated_at < keeper.updated_at
                    OR (stale.updated_at = keeper.updated_at AND stale.item_id > keeper.item_id))
                """);
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_column_assignments_lane
                ON column_assignments(col_x, col_y, col_z)
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS import_chests (
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (x, y, z)
                )
                """);

            // Installations upgrading from schemas created before physical shulker persistence
            // still need the side table that can represent empty boxes.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS container_shulkers (
                        container_id BIGINT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
                        slot INTEGER NOT NULL,
                        color VARCHAR(32) NOT NULL,
                        PRIMARY KEY (container_id, slot)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_container_shulkers_container ON container_shulkers(container_id)");
        }
    }

    // Container CRUD
    // Replace the container at this position atomically.
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
            INSERT INTO containers (x, y, z, block_type, is_double, shulker_count, total_items, scan_timestamp, label, hopper_facing,
                                    inventory_x, inventory_y, inventory_z, inventory_identity_known, double_chest_axis, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (x, y, z) DO UPDATE SET
                block_type = EXCLUDED.block_type,
                is_double = EXCLUDED.is_double,
                shulker_count = EXCLUDED.shulker_count,
                total_items = EXCLUDED.total_items,
                scan_timestamp = EXCLUDED.scan_timestamp,
                label = EXCLUDED.label,
                hopper_facing = EXCLUDED.hopper_facing,
                inventory_x = EXCLUDED.inventory_x,
                inventory_y = EXCLUDED.inventory_y,
                inventory_z = EXCLUDED.inventory_z,
                inventory_identity_known = EXCLUDED.inventory_identity_known,
                double_chest_axis = EXCLUDED.double_chest_axis,
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
            ps.setString(10, entry.hopperFacing());
            ps.setInt(11, entry.inventoryX());
            ps.setInt(12, entry.inventoryY());
            ps.setInt(13, entry.inventoryZ());
            ps.setBoolean(14, entry.inventoryIdentityKnown());
            ps.setString(15, entry.doubleChestAxis());

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
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM container_shulkers WHERE container_id = ?")) {
            ps.setLong(1, containerId);
            ps.executeUpdate();
        }

        // Persist the physical box independently from its contents so empty boxes survive a
        // database reload. Legacy aggregate details deliberately have no physical slot.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO container_shulkers (container_id, slot, color) VALUES (?, ?, ?)")) {
            for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
                if (!shulker.isPhysicalInstance()) continue;
                ps.setLong(1, containerId);
                ps.setInt(2, shulker.slot());
                ps.setString(3, shulker.color());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // entry.items() is the merged total (loose items + everything inside shulkers).
        // Subtract out what's already itemized per-shulker below so each unit is only
        // counted once, rather than once here and again in the shulker breakdown rows.
        Map<String, Integer> directItems = new LinkedHashMap<>(entry.items());
        for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
            for (var item : shulker.items().entrySet()) {
                directItems.merge(item.getKey(), -item.getValue(), Integer::sum);
            }
        }
        directItems.values().removeIf(qty -> qty <= 0);

        // Insert direct (non-shulker) container items
        String insertSql = "INSERT INTO container_items (container_id, item_id, quantity, in_shulker, shulker_color, shulker_instance) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (var item : directItems.entrySet()) {
                ps.setLong(1, containerId);
                ps.setString(2, item.getKey());
                ps.setInt(3, item.getValue());
                ps.setBoolean(4, false);
                ps.setNull(5, Types.VARCHAR);
                ps.setNull(6, Types.INTEGER);
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
                    if (shulker.isPhysicalInstance()) ps.setInt(6, shulker.slot());
                    else ps.setNull(6, Types.INTEGER);
                    ps.addBatch();
                }
            }

            ps.executeBatch();
        }
    }

    // Queries
    public List<ContainerEntry> getAllContainers() throws SQLException {
        if (!initialized) return Collections.emptyList();

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp, label, hopper_facing, inventory_x, inventory_y, inventory_z, inventory_identity_known, double_chest_axis FROM containers ORDER BY scan_timestamp DESC";
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

        String sql = "SELECT id, x, y, z, block_type, is_double, shulker_count, scan_timestamp, label, hopper_facing, inventory_x, inventory_y, inventory_z, inventory_identity_known, double_chest_axis FROM containers ORDER BY scan_timestamp DESC LIMIT ? OFFSET ?";
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
            SELECT DISTINCT c.id, c.x, c.y, c.z, c.block_type, c.is_double, c.shulker_count, c.scan_timestamp, c.label, c.hopper_facing,
                            c.inventory_x, c.inventory_y, c.inventory_z, c.inventory_identity_known,
                            c.double_chest_axis
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

    // Return aggregate container, item, type, and scan statistics.
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

    /** Deletes obsolete scan rows atomically; child item/shulker rows cascade. */
    public void deleteContainers(Collection<ContainerEntry> containers) throws SQLException {
        if (!initialized || containers == null || containers.isEmpty()) return;

        String sql = "DELETE FROM containers WHERE x = ? AND y = ? AND z = ?";
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ContainerEntry entry : containers) {
                    if (entry == null) continue;
                    ps.setInt(1, entry.x());
                    ps.setInt(2, entry.y());
                    ps.setInt(3, entry.z());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    // Named Regions
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

    // Container Labels
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

    // Import Chests
    public void addImportChests(Collection<int[]> positions) throws SQLException {
        if (!initialized || positions == null || positions.isEmpty()) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO import_chests (x, y, z) VALUES (?, ?, ?) ON CONFLICT (x, y, z) DO NOTHING")) {
                for (int[] pos : positions) {
                    if (pos == null || pos.length < 3) continue;
                    ps.setInt(1, pos[0]);
                    ps.setInt(2, pos[1]);
                    ps.setInt(3, pos[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    public void removeImportChests(Collection<int[]> positions) throws SQLException {
        if (!initialized || positions == null || positions.isEmpty()) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM import_chests WHERE x = ? AND y = ? AND z = ?")) {
                for (int[] pos : positions) {
                    if (pos == null || pos.length < 3) continue;
                    ps.setInt(1, pos[0]);
                    ps.setInt(2, pos[1]);
                    ps.setInt(3, pos[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    public List<int[]> loadImportChests() throws SQLException {
        if (!initialized) return Collections.emptyList();

        List<int[]> positions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT x, y, z FROM import_chests ORDER BY x, y, z")) {
            while (rs.next()) {
                positions.add(new int[]{rs.getInt("x"), rs.getInt("y"), rs.getInt("z")});
            }
        }
        return positions;
    }

    /** Removes every persisted import assignment and returns the number of chest blocks removed. */
    public int clearImportChests() throws SQLException {
        if (!initialized) return 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM import_chests")) {
            return ps.executeUpdate();
        }
    }

    // Config Key-Value Store
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

    // Storage Chests (Sorting Config)
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

    // Keep Items
    // Value is the max quantity to keep in inventory during organize; null/absent means keep all.
    public void saveKeepItems(Map<String, Integer> itemQuantities) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement del = conn.createStatement()) {
                del.executeUpdate("DELETE FROM keep_items");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO keep_items (item_id, keep_quantity) VALUES (?, ?)")) {
                for (var entry : itemQuantities.entrySet()) {
                    ps.setString(1, entry.getKey());
                    if (entry.getValue() == null) {
                        ps.setNull(2, Types.INTEGER);
                    } else {
                        ps.setInt(2, entry.getValue());
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    // Returns item_id -> max quantity to keep (null value means keep all of that item).
    public Map<String, Integer> loadKeepItems() throws SQLException {
        if (!initialized) return Collections.emptyMap();

        Map<String, Integer> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT item_id, keep_quantity FROM keep_items")) {
            while (rs.next()) {
                int qty = rs.getInt("keep_quantity");
                result.put(rs.getString("item_id"), rs.wasNull() ? null : qty);
            }
        }
        return result;
    }

    // Column Assignments
    // Persists which column (identified by its top chest position) each item type is assigned
    // to, so the organizer reuses the same column on future runs instead of recomputing a fresh
    // greedy assignment each time. Requires the plugin's Postgres connection to be configured —
    // if it isn't, this silently no-ops and the organizer falls back to in-memory-only behavior
    // for that run (see README for how to configure persistent storage).
    public void saveColumnAssignments(Map<String, int[]> assignments) throws SQLException {
        if (!initialized) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            // This table represents the latest complete plan, not an append-only history.
            // Removing retired rows prevents old item classes from claiming reassigned lanes.
            try (Statement clear = conn.createStatement()) {
                clear.executeUpdate("DELETE FROM column_assignments");
            }
            if (assignments.isEmpty()) {
                conn.commit();
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO column_assignments (item_id, col_x, col_y, col_z, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (item_id) DO UPDATE SET
                        col_x = EXCLUDED.col_x, col_y = EXCLUDED.col_y, col_z = EXCLUDED.col_z,
                        updated_at = EXCLUDED.updated_at
                    """)) {
                for (var entry : assignments.entrySet()) {
                    int[] pos = entry.getValue();
                    ps.setString(1, entry.getKey());
                    ps.setInt(2, pos[0]);
                    ps.setInt(3, pos[1]);
                    ps.setInt(4, pos[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    // Returns item_id -> {x, y, z} of the persisted column's top chest.
    public Map<String, int[]> loadColumnAssignments() throws SQLException {
        if (!initialized) return Collections.emptyMap();

        Map<String, int[]> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT item_id, col_x, col_y, col_z FROM column_assignments")) {
            while (rs.next()) {
                result.put(rs.getString("item_id"), new int[]{rs.getInt("col_x"), rs.getInt("col_y"), rs.getInt("col_z")});
            }
        }
        return result;
    }

    // Kits
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

    // Scan History
    public long recordScanStart(int[] pos1, int[] pos2) throws SQLException {
        if (!initialized) return -1;

        String sql = """
            INSERT INTO scan_history (
                region_pos1_x, region_pos1_y, region_pos1_z,
                region_pos2_x, region_pos2_y, region_pos2_z,
                completion_status
            )
            VALUES (?, ?, ?, ?, ?, ?, 'running')
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
        recordScanFinished(scanId, found, indexed, failed, "complete");
    }

    public void recordScanAborted(long scanId, int found, int indexed, int failed) throws SQLException {
        recordScanFinished(scanId, found, indexed, failed, "aborted");
    }

    private void recordScanFinished(
            long scanId,
            int found,
            int indexed,
            int failed,
            String completionStatus) throws SQLException {
        if (!initialized || scanId < 0) return;

        String sql = """
            UPDATE scan_history SET containers_found = ?, containers_indexed = ?, containers_failed = ?,
                completion_status = ?, completed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, found);
            ps.setInt(2, indexed);
            ps.setInt(3, failed);
            ps.setString(4, completionStatus);
            ps.setLong(5, scanId);
            ps.executeUpdate();
        }
    }

    public Optional<ScanSummary> getLatestScanSummary(int[] pos1, int[] pos2) throws SQLException {
        if (!initialized || pos1 == null || pos2 == null) return Optional.empty();

        int minX = Math.min(pos1[0], pos2[0]);
        int minY = Math.min(pos1[1], pos2[1]);
        int minZ = Math.min(pos1[2], pos2[2]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int maxY = Math.max(pos1[1], pos2[1]);
        int maxZ = Math.max(pos1[2], pos2[2]);
        String sql = """
            SELECT containers_found, containers_indexed, containers_failed, completion_status
            FROM scan_history
            WHERE LEAST(region_pos1_x, region_pos2_x) = ?
              AND LEAST(region_pos1_y, region_pos2_y) = ?
              AND LEAST(region_pos1_z, region_pos2_z) = ?
              AND GREATEST(region_pos1_x, region_pos2_x) = ?
              AND GREATEST(region_pos1_y, region_pos2_y) = ?
              AND GREATEST(region_pos1_z, region_pos2_z) = ?
            ORDER BY id DESC
            LIMIT 1
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, minX);
            ps.setInt(2, minY);
            ps.setInt(3, minZ);
            ps.setInt(4, maxX);
            ps.setInt(5, maxY);
            ps.setInt(6, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ScanSummary(
                        rs.getInt("containers_found"),
                        rs.getInt("containers_indexed"),
                        rs.getInt("containers_failed"),
                        rs.getString("completion_status")));
            }
        }
    }

    public record ScanSummary(int found, int indexed, int failed, String completionStatus) {
        public boolean completed() {
            return "complete".equalsIgnoreCase(completionStatus);
        }
    }

    // Helpers
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
        String hopperFacing = null;
        try { hopperFacing = rs.getString("hopper_facing"); } catch (SQLException ignored) {}
        int inventoryX = x;
        int inventoryY = y;
        int inventoryZ = z;
        boolean inventoryIdentityKnown = !isDouble;
        String doubleChestAxis = null;
        try {
            Object storedX = rs.getObject("inventory_x");
            Object storedY = rs.getObject("inventory_y");
            Object storedZ = rs.getObject("inventory_z");
            if (storedX != null && storedY != null && storedZ != null) {
                inventoryX = ((Number) storedX).intValue();
                inventoryY = ((Number) storedY).intValue();
                inventoryZ = ((Number) storedZ).intValue();
            }
            inventoryIdentityKnown = rs.getBoolean("inventory_identity_known");
            doubleChestAxis = rs.getString("double_chest_axis");
        } catch (SQLException ignored) {
            // Compatibility with a database that has not run the identity migration yet.
        }
        if (!isDouble) {
            inventoryX = x;
            inventoryY = y;
            inventoryZ = z;
            inventoryIdentityKnown = true;
            doubleChestAxis = null;
        }

        // Load items
        Map<String, Integer> items = new LinkedHashMap<>();
        List<ContainerEntry.ShulkerDetail> shulkerDetails = new ArrayList<>();
        Map<Integer, String> physicalShulkerColors = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> physicalShulkerItems = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> legacyShulkerItemsByColor = new LinkedHashMap<>();

        try (PreparedStatement shulkerPs = conn.prepareStatement(
                "SELECT slot, color FROM container_shulkers WHERE container_id = ? ORDER BY slot")) {
            shulkerPs.setLong(1, containerId);
            try (ResultSet shulkerRs = shulkerPs.executeQuery()) {
                while (shulkerRs.next()) {
                    int slot = shulkerRs.getInt("slot");
                    physicalShulkerColors.put(slot, shulkerRs.getString("color"));
                    physicalShulkerItems.put(slot, new LinkedHashMap<>());
                }
            }
        }

        try (PreparedStatement itemPs = conn.prepareStatement(
                 "SELECT item_id, quantity, in_shulker, shulker_color, shulker_instance FROM container_items WHERE container_id = ?")) {
            itemPs.setLong(1, containerId);

            try (ResultSet itemRs = itemPs.executeQuery()) {
                while (itemRs.next()) {
                    String itemId = itemRs.getString("item_id");
                    int quantity = itemRs.getInt("quantity");
                    boolean inShulker = itemRs.getBoolean("in_shulker");
                    String shulkerColor = itemRs.getString("shulker_color");
                    int shulkerInstance = itemRs.getInt("shulker_instance");
                    boolean physicalInstance = !itemRs.wasNull();

                    if (inShulker && shulkerColor != null) {
                        if (physicalInstance) {
                            physicalShulkerColors.putIfAbsent(shulkerInstance, shulkerColor);
                            physicalShulkerItems.computeIfAbsent(shulkerInstance, k -> new LinkedHashMap<>())
                                    .merge(itemId, quantity, Integer::sum);
                        } else {
                            legacyShulkerItemsByColor.computeIfAbsent(shulkerColor, k -> new LinkedHashMap<>())
                                    .merge(itemId, quantity, Integer::sum);
                        }
                    }
                    // All items go into the main map (same as the in-memory behavior)
                    items.merge(itemId, quantity, Integer::sum);
                }
            }
        }

        for (var entry : physicalShulkerItems.entrySet()) {
            shulkerDetails.add(new ContainerEntry.ShulkerDetail(
                    entry.getKey(), physicalShulkerColors.get(entry.getKey()), entry.getValue()));
        }
        for (var entry : legacyShulkerItemsByColor.entrySet()) {
            shulkerDetails.add(new ContainerEntry.ShulkerDetail(entry.getKey(), entry.getValue()));
        }

        return new ContainerEntry(x, y, z, blockType, isDouble, items, shulkerCount,
                shulkerDetails, scanTimestamp, label, hopperFacing,
                inventoryX, inventoryY, inventoryZ, inventoryIdentityKnown, doubleChestAxis);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        initialized = false;
    }
}
