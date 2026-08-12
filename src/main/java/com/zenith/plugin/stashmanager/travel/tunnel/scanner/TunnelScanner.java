package com.zenith.plugin.stashmanager.travel.tunnel.scanner;

import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelDiscovery;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.mc.block.Block;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CACHE;

// Scans Zenith's chunk cache for 2-tall air columns at Y=8 that indicate player-carved tunnels.
// Runs inline (no threads); only called on explicit request from TunnelManager.
public final class TunnelScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelScanner");

    /** Floor block Y. */
    static final int SCAN_Y = 8;
    /** Feet level. */
    static final int SCAN_Y_FEET = SCAN_Y + 1;
    /** Head level. */
    static final int SCAN_Y_HEAD = SCAN_Y + 2;

    /** Minimum run of contiguous air blocks to be worth recording as a tunnel candidate. */
    private static final int MIN_RUN_BLOCKS = 16;

    // Scans loaded chunks; returns candidates without persisting them.
    public List<Tunnel> scan(int playerX, int playerZ) {
        List<Tunnel> results = new ArrayList<>();

        try {
            var chunkCache = CACHE.getChunkCache();
            if (chunkCache == null) return results;

            // Build a set of air positions at our target Y levels, organized by chunk column
            // We look for connected horizontal runs (X-axis and Z-axis separately)
            List<int[]> airPositions = collectAirPositions(playerX, playerZ);

            if (airPositions.isEmpty()) return results;

            // Group air positions into linear runs and convert to tunnels
            results.addAll(extractXAxisRuns(airPositions));
            results.addAll(extractZAxisRuns(airPositions));

            LOGGER.info("TunnelScanner: found {} candidates from {} air positions",
                    results.size(), airPositions.size());

        } catch (Exception e) {
            // Chunk cache API may change; degrade gracefully
            LOGGER.debug("TunnelScanner: chunk cache scan failed (API incompatibility?)", e);
        }

        return results;
    }

    /**
     * Collect all block positions at SCAN_Y+1 and SCAN_Y+2 that are air AND
     * have a solid block at SCAN_Y (floor present), indicating a carved passage.
     *
     */
    private List<int[]> collectAirPositions(int playerX, int playerZ) {
        List<int[]> positions = new ArrayList<>();

        // Scan a 16-chunk radius around the player (256 blocks each direction)
        // Chunks are 16×16 blocks; sample every block in each chunk column
        int chunkRadius = 8; // 8 chunks = 128 blocks radius
        int playerChunkX = playerX >> 4;
        int playerChunkZ = playerZ >> 4;

        for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
            for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                try {
                    scanChunkColumn(cx, cz, positions);
                } catch (Exception e) {
                    // Individual chunk scan failure is non-fatal
                }
            }
        }
        return positions;
    }

    /**
     * Scan a single 16×16 chunk column for tunnel air positions.
     */
    private void scanChunkColumn(int chunkX, int chunkZ, List<int[]> out) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                if (isTunnelAir(wx, wz)) {
                    out.add(new int[]{wx, wz});
                }
            }
        }
    }

    /**
     * Check if a column at (x, z) has the tunnel signature:
     * - SCAN_Y is solid (floor)
     * - SCAN_Y_FEET is air (feet level)
     * - SCAN_Y_HEAD is air (head level)
     */
    private boolean isTunnelAir(int x, int z) {
        try {
            var chunkCache = CACHE.getChunkCache();
            if (!chunkCache.isChunkLoaded(x >> 4, z >> 4)) return false;

            Chunk chunk = chunkCache.get(x >> 4, z >> 4);
            if (chunk == null) return false;

            int lx = x & 15;
            int lz = z & 15;

            int floorState = chunk.getBlockStateId(lx, SCAN_Y,      lz);
            int feetState  = chunk.getBlockStateId(lx, SCAN_Y_FEET, lz);
            int headState  = chunk.getBlockStateId(lx, SCAN_Y_HEAD, lz);

            Block floor = BLOCK_DATA.getBlockDataFromBlockStateId(floorState);
            Block feet  = BLOCK_DATA.getBlockDataFromBlockStateId(feetState);
            Block head  = BLOCK_DATA.getBlockDataFromBlockStateId(headState);

            if (floor == null || feet == null || head == null) return false;

            boolean floorIsSolid = BlockCompat.isSolid(x, SCAN_Y, z) && !BlockCompat.isAir(floor);
            boolean feetIsAir    = BlockCompat.isAir(feet);
            boolean headIsAir    = BlockCompat.isAir(head);

            return floorIsSolid && feetIsAir && headIsAir;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Run extraction ────────────────────────────────────────────────────────

    /** Extract tunnels running along the X axis (constant Z). */
    private List<Tunnel> extractXAxisRuns(List<int[]> positions) {
        // Group by Z coordinate
        java.util.Map<Integer, List<Integer>> byZ = new java.util.TreeMap<>();
        for (int[] pos : positions) {
            byZ.computeIfAbsent(pos[1], k -> new ArrayList<>()).add(pos[0]);
        }

        List<Tunnel> tunnels = new ArrayList<>();
        for (var entry : byZ.entrySet()) {
            int z = entry.getKey();
            List<Integer> xs = new ArrayList<>(entry.getValue());
            java.util.Collections.sort(xs);
            tunnels.addAll(extractRuns(xs, z, true));
        }
        return tunnels;
    }

    /** Extract tunnels running along the Z axis (constant X). */
    private List<Tunnel> extractZAxisRuns(List<int[]> positions) {
        // Group by X coordinate
        java.util.Map<Integer, List<Integer>> byX = new java.util.TreeMap<>();
        for (int[] pos : positions) {
            byX.computeIfAbsent(pos[0], k -> new ArrayList<>()).add(pos[1]);
        }

        List<Tunnel> tunnels = new ArrayList<>();
        for (var entry : byX.entrySet()) {
            int x = entry.getKey();
            List<Integer> zs = new ArrayList<>(entry.getValue());
            java.util.Collections.sort(zs);
            tunnels.addAll(extractRuns(zs, x, false));
        }
        return tunnels;
    }

    // Groups sorted coords into contiguous runs of MIN_RUN_BLOCKS or more.
    private List<Tunnel> extractRuns(List<Integer> coords, int fixed, boolean xVaries) {
        List<Tunnel> tunnels = new ArrayList<>();
        if (coords.size() < MIN_RUN_BLOCKS) return tunnels;

        int runStart = coords.get(0);
        int prev = coords.get(0);

        for (int i = 1; i < coords.size(); i++) {
            int cur = coords.get(i);
            if (cur - prev > 2) {
                // Gap — evaluate and potentially close the run
                int runLen = prev - runStart + 1;
                if (runLen >= MIN_RUN_BLOCKS) {
                    tunnels.add(makeTunnel(runStart, prev, fixed, xVaries));
                }
                runStart = cur;
            }
            prev = cur;
        }
        // Final run
        int runLen = prev - runStart + 1;
        if (runLen >= MIN_RUN_BLOCKS) {
            tunnels.add(makeTunnel(runStart, prev, fixed, xVaries));
        }
        return tunnels;
    }

    private Tunnel makeTunnel(int coordStart, int coordEnd, int fixed, boolean xVaries) {
        Tunnel t = new Tunnel();
        t.floorY     = SCAN_Y;
        t.discovery  = TunnelDiscovery.SCANNED;
        t.status     = TunnelStatus.UNVERIFIED;
        // Confidence starts at 0.6 for passive scan — needs traversal to confirm
        t.confidence = 0.6;

        if (xVaries) {
            t.startX = coordStart; t.startZ = fixed;
            t.endX   = coordEnd;   t.endZ   = fixed;
        } else {
            t.startX = fixed; t.startZ = coordStart;
            t.endX   = fixed; t.endZ   = coordEnd;
        }
        return t;
    }

    // Re-checks endpoints; returns updated confidence (0.0 = compromised, 1.0 = intact).
    public double verifyTunnel(Tunnel tunnel) {
        boolean startOk = isTunnelAir(tunnel.startX, tunnel.startZ);
        boolean endOk   = isTunnelAir(tunnel.endX,   tunnel.endZ);

        if (startOk && endOk) return 1.0;
        if (startOk || endOk) return 0.5;
        return 0.0;
    }
}
