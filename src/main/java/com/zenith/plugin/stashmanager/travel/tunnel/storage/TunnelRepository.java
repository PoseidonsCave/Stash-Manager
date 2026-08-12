package com.zenith.plugin.stashmanager.travel.tunnel.storage;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelDiscovery;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelWaypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Persistence layer for Tunnel entities. Thread-safe; each call gets its own DB connection.
public class TunnelRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelRepo");

    private final DatabaseManager db;

    public TunnelRepository(DatabaseManager db) {
        this.db = db;
    }

    // Schema
    // Called by DatabaseManager.createSchema() during startup.
    public static void createSchema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS tunnels (
                id                BIGSERIAL PRIMARY KEY,
                dimension         VARCHAR(64)   NOT NULL DEFAULT 'minecraft:the_nether',
                start_x           INTEGER       NOT NULL,
                start_z           INTEGER       NOT NULL,
                end_x             INTEGER       NOT NULL,
                end_z             INTEGER       NOT NULL,
                floor_y           INTEGER       NOT NULL DEFAULT 8,
                discovery_method  VARCHAR(32)   NOT NULL DEFAULT 'self_built',
                status            VARCHAR(32)   NOT NULL DEFAULT 'unverified',
                confidence        REAL          NOT NULL DEFAULT 1.0,
                discovered_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_verified_at  TIMESTAMP,
                last_used_at      TIMESTAMP,
                times_used        INTEGER       NOT NULL DEFAULT 0,
                network_id        VARCHAR(128),
                shared_to_network BOOLEAN       NOT NULL DEFAULT FALSE,
                UNIQUE (start_x, start_z, end_x, end_z, floor_y, dimension)
            )
            """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS tunnel_waypoints (
                id         BIGSERIAL PRIMARY KEY,
                tunnel_id  BIGINT  NOT NULL REFERENCES tunnels(id) ON DELETE CASCADE,
                seq        INTEGER NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                UNIQUE (tunnel_id, seq)
            )
            """);

        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_tunnels_start
                ON tunnels (start_x, start_z)
            """);

        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_tunnels_end
                ON tunnels (end_x, end_z)
            """);

        stmt.execute("""
            CREATE INDEX IF NOT EXISTS idx_tunnels_dimension
                ON tunnels (dimension)
            """);
    }

    // Write
    // Inserts or returns existing id if same start/end/floor/dimension already exists.
    public Optional<Long> save(Tunnel tunnel) {
        if (!db.isInitialized()) return Optional.empty();
        String sql = """
            INSERT INTO tunnels
                (dimension, start_x, start_z, end_x, end_z, floor_y,
                 discovery_method, status, confidence,
                 discovered_at, last_verified_at, last_used_at,
                 times_used, network_id, shared_to_network)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (start_x, start_z, end_x, end_z, floor_y, dimension)
                DO UPDATE SET
                    status            = EXCLUDED.status,
                    confidence        = EXCLUDED.confidence,
                    last_verified_at  = EXCLUDED.last_verified_at,
                    last_used_at      = EXCLUDED.last_used_at,
                    times_used        = EXCLUDED.times_used,
                    network_id        = EXCLUDED.network_id,
                    shared_to_network = EXCLUDED.shared_to_network
            RETURNING id
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tunnel.dimension);
            ps.setInt(2, tunnel.startX);
            ps.setInt(3, tunnel.startZ);
            ps.setInt(4, tunnel.endX);
            ps.setInt(5, tunnel.endZ);
            ps.setInt(6, tunnel.floorY);
            ps.setString(7, tunnel.discovery.name().toLowerCase());
            ps.setString(8, tunnel.status.name().toLowerCase());
            ps.setDouble(9, tunnel.confidence);
            ps.setTimestamp(10, Timestamp.from(tunnel.discoveredAt));
            ps.setTimestamp(11, tunnel.lastVerifiedAt != null ? Timestamp.from(tunnel.lastVerifiedAt) : null);
            ps.setTimestamp(12, tunnel.lastUsedAt != null ? Timestamp.from(tunnel.lastUsedAt) : null);
            ps.setInt(13, tunnel.timesUsed);
            ps.setString(14, tunnel.networkId);
            ps.setBoolean(15, tunnel.sharedToNetwork);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    tunnel.id = id;
                    saveWaypoints(conn, id, tunnel.waypoints);
                    LOGGER.info("Saved tunnel id={} {} → {}", id, tunnel.startX + "," + tunnel.startZ, tunnel.endX + "," + tunnel.endZ);
                    return Optional.of(id);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to save tunnel", e);
        }
        return Optional.empty();
    }

    private void saveWaypoints(Connection conn, long tunnelId, List<TunnelWaypoint> waypoints)
            throws SQLException {
        if (waypoints.isEmpty()) return;

        // Delete existing (upsert behaviour for waypoints)
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM tunnel_waypoints WHERE tunnel_id = ?")) {
            del.setLong(1, tunnelId);
            del.executeUpdate();
        }

        String sql = "INSERT INTO tunnel_waypoints (tunnel_id, seq, x, y, z) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (TunnelWaypoint wp : waypoints) {
                ps.setLong(1, tunnelId);
                ps.setInt(2, wp.sequence());
                ps.setInt(3, wp.x());
                ps.setInt(4, wp.y());
                ps.setInt(5, wp.z());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // Update status and confidence for an existing tunnel.
    public void updateStatus(long tunnelId, TunnelStatus status, double confidence) {
        if (!db.isInitialized()) return;
        String sql = """
            UPDATE tunnels SET status = ?, confidence = ?, last_verified_at = ?
            WHERE id = ?
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name().toLowerCase());
            ps.setDouble(2, confidence);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setLong(4, tunnelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to update tunnel status", e);
        }
    }

    // Record that a tunnel was successfully used.
    public void recordUse(long tunnelId) {
        if (!db.isInitialized()) return;
        String sql = """
            UPDATE tunnels SET times_used = times_used + 1, last_used_at = ?
            WHERE id = ?
            """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setLong(2, tunnelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to record tunnel use", e);
        }
    }

    // Read
    // Finds tunnels whose start is within radius blocks; confidence >= minConfidence.
    public List<Tunnel> findNearStart(int x, int z, int radius,
                                      double minConfidence, String dimension) {
        if (!db.isInitialized()) return List.of();
        String sql = """
            SELECT * FROM tunnels
            WHERE dimension = ?
              AND confidence >= ?
              AND status != 'compromised'
              AND ABS(start_x - ?) <= ?
              AND ABS(start_z - ?) <= ?
            ORDER BY confidence DESC, times_used DESC
            LIMIT 50
            """;
        return queryTunnels(sql, dimension, minConfidence, x, radius, z, radius);
    }

    // Finds tunnels whose end is within radius blocks; confidence >= minConfidence.
    public List<Tunnel> findNearEnd(int x, int z, int radius,
                                    double minConfidence, String dimension) {
        if (!db.isInitialized()) return List.of();
        String sql = """
            SELECT * FROM tunnels
            WHERE dimension = ?
              AND confidence >= ?
              AND status != 'compromised'
              AND ABS(end_x - ?) <= ?
              AND ABS(end_z - ?) <= ?
            ORDER BY confidence DESC, times_used DESC
            LIMIT 50
            """;
        return queryTunnels(sql, dimension, minConfidence, x, radius, z, radius);
    }

    // Return all known tunnels ordered by database id.
    public List<Tunnel> findAll() {
        if (!db.isInitialized()) return List.of();
        return queryTunnels("SELECT * FROM tunnels ORDER BY id ASC");
    }

    // Get a specific tunnel by database id.
    public Optional<Tunnel> findById(long id) {
        if (!db.isInitialized()) return Optional.empty();
        String sql = "SELECT * FROM tunnels WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Tunnel t = mapRow(rs);
                    loadWaypoints(conn, t);
                    return Optional.of(t);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to find tunnel by id", e);
        }
        return Optional.empty();
    }

    // Total number of known tunnels.
    public int count() {
        if (!db.isInitialized()) return 0;
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM tunnels")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            LOGGER.error("Failed to count tunnels", e);
        }
        return 0;
    }

    // Helpers
    private List<Tunnel> queryTunnels(String sql, Object... params) {
        List<Tunnel> results = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Tunnel t = mapRow(rs);
                    loadWaypoints(conn, t);
                    results.add(t);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to query tunnels", e);
        }
        return results;
    }

    private Tunnel mapRow(ResultSet rs) throws SQLException {
        Tunnel t = new Tunnel();
        t.id            = rs.getLong("id");
        t.dimension     = rs.getString("dimension");
        t.startX        = rs.getInt("start_x");
        t.startZ        = rs.getInt("start_z");
        t.endX          = rs.getInt("end_x");
        t.endZ          = rs.getInt("end_z");
        t.floorY        = rs.getInt("floor_y");
        t.discovery     = TunnelDiscovery.valueOf(rs.getString("discovery_method").toUpperCase());
        t.status        = TunnelStatus.valueOf(rs.getString("status").toUpperCase());
        t.confidence    = rs.getDouble("confidence");
        t.discoveredAt  = rs.getTimestamp("discovered_at").toInstant();
        Timestamp lv = rs.getTimestamp("last_verified_at");
        t.lastVerifiedAt = lv != null ? lv.toInstant() : null;
        Timestamp lu = rs.getTimestamp("last_used_at");
        t.lastUsedAt    = lu != null ? lu.toInstant() : null;
        t.timesUsed     = rs.getInt("times_used");
        t.networkId     = rs.getString("network_id");
        t.sharedToNetwork = rs.getBoolean("shared_to_network");
        return t;
    }

    private void loadWaypoints(Connection conn, Tunnel tunnel) throws SQLException {
        String sql = """
            SELECT seq, x, y, z FROM tunnel_waypoints
            WHERE tunnel_id = ? ORDER BY seq
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tunnel.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tunnel.waypoints.add(new TunnelWaypoint(
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("seq")));
                }
            }
        }
    }
}
