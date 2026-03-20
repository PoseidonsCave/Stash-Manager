package com.zenith.plugin.stashmanager.scanner;

import com.zenith.cache.data.chunk.Chunk;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.zenith.Globals.CACHE;

// Enumerates container block entities from loaded chunks within a defined region.
public class RegionScanner {

    private final Logger logger;
    private final Set<Long> scannedChunks = new HashSet<>();

    public RegionScanner(Logger logger) {
        this.logger = logger;
    }

    public record ContainerLocation(
        int x, int y, int z,
        BlockEntityType type,
        int chunkX, int chunkZ
    ) {
        public long posKey() {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
        }
    }

    // Scan loaded chunks in the pos1-to-pos2 bounding box. Returns containers sorted by distance.
    public List<ContainerLocation> scanRegion(int[] pos1, int[] pos2, int maxContainers) {
        int minX = Math.min(pos1[0], pos2[0]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int minY = Math.min(pos1[1], pos2[1]);
        int maxY = Math.max(pos1[1], pos2[1]);
        int minZ = Math.min(pos1[2], pos2[2]);
        int maxZ = Math.max(pos1[2], pos2[2]);

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        List<ContainerLocation> containers = new ArrayList<>();
        Set<Long> seenPositions = new HashSet<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!CACHE.getChunkCache().isChunkLoaded(cx, cz)) continue;

                long chunkKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (scannedChunks.contains(chunkKey)) continue;
                scannedChunks.add(chunkKey);

                Chunk chunk = CACHE.getChunkCache().get(cx, cz);
                if (chunk == null) continue;

                for (var be : chunk.getBlockEntities()) {
                    if (!isContainerType(be.getType())) continue;

                    int worldX = (cx * 16) + be.getX();
                    int worldY = be.getY();
                    int worldZ = (cz * 16) + be.getZ();

                    if (worldX < minX || worldX > maxX
                        || worldY < minY || worldY > maxY
                        || worldZ < minZ || worldZ > maxZ) continue;

                    long posKey = ((long) worldX & 0x3FFFFFFL) << 38
                        | ((long) worldY & 0xFFFL) << 26
                        | ((long) worldZ & 0x3FFFFFFL);

                    if (!seenPositions.add(posKey)) continue;

                    containers.add(new ContainerLocation(worldX, worldY, worldZ, be.getType(), cx, cz));

                    if (containers.size() >= maxContainers) {
                        logger.warn("Container cap reached: {}", maxContainers);
                        return sortByPlayerDistance(containers);
                    }
                }
            }
        }

        logger.info("Region scan found {} containers in {} chunks",
            containers.size(), scannedChunks.size());
        return sortByPlayerDistance(containers);
    }

    // Get unscanned chunk coordinates within the region.
    public List<int[]> getUnscannedChunks(int[] pos1, int[] pos2) {
        int minChunkX = Math.min(pos1[0], pos2[0]) >> 4;
        int maxChunkX = Math.max(pos1[0], pos2[0]) >> 4;
        int minChunkZ = Math.min(pos1[2], pos2[2]) >> 4;
        int maxChunkZ = Math.max(pos1[2], pos2[2]) >> 4;

        List<int[]> unscanned = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long chunkKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (!scannedChunks.contains(chunkKey)) {
                    unscanned.add(new int[]{cx, cz});
                }
            }
        }
        return unscanned;
    }

    public void reset() {
        scannedChunks.clear();
    }

    public int getScannedChunkCount() {
        return scannedChunks.size();
    }

    private List<ContainerLocation> sortByPlayerDistance(List<ContainerLocation> containers) {
        var playerCache = CACHE.getPlayerCache();
        double px = playerCache.getX();
        double py = playerCache.getY();
        double pz = playerCache.getZ();

        containers.sort(Comparator.comparingDouble(c ->
            Math.pow(c.x() - px, 2) + Math.pow(c.y() - py, 2) + Math.pow(c.z() - pz, 2)
        ));
        return containers;
    }

    private boolean isContainerType(BlockEntityType type) {
        return type == BlockEntityType.CHEST
            || type == BlockEntityType.TRAPPED_CHEST
            || type == BlockEntityType.BARREL
            || type == BlockEntityType.SHULKER_BOX
            || type == BlockEntityType.HOPPER
            || type == BlockEntityType.DISPENSER
            || type == BlockEntityType.DROPPER;
    }
}
