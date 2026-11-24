package com.zenith.plugin.stashmanager.debug;

import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.properties.ChestType;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.plugin.stashmanager.orchestration.ContainerAccessPlanner;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Failure-time block snapshot; never treats an unloaded section as air. */
public final class WorldGeometrySnapshot {
    private static final int NEARBY_LIMIT = 80;

    private WorldGeometrySnapshot() { }

    public static String capture(int x, int y, int z, @Nullable int[] clicked,
                                 double playerX, double playerY, double playerZ,
                                 float yaw, float pitch) {
        StringBuilder out = new StringBuilder(2048);
        out.append("target=").append(describe(x, y, z));
        out.append("; clicked=").append(clicked == null ? "target"
                : describe(clicked[0], clicked[1], clicked[2]));
        out.append("; player=").append(String.format(Locale.ROOT,
                "%.3f,%.3f,%.3f yaw=%.2f pitch=%.2f",
                playerX, playerY, playerZ, yaw, pitch));
        out.append("; reach=").append(ContainerApproach.isWithinVanillaReach(
                playerX, playerY, playerZ, x, y, z));
        out.append("; visible=").append(ContainerAccessPlanner.visible(
                playerX, playerY, playerZ, x, y, z));
        out.append("; aim_hit=").append(aimHit(playerX, playerY, playerZ, x, y, z));
        if (clicked != null) {
            out.append("; clicked_reach=").append(ContainerApproach.isWithinVanillaReach(
                    playerX, playerY, playerZ, clicked[0], clicked[1], clicked[2]));
            out.append("; clicked_visible=").append(ContainerAccessPlanner.visible(
                    playerX, playerY, playerZ, clicked[0], clicked[1], clicked[2]));
            out.append("; clicked_aim_hit=").append(aimHit(playerX, playerY, playerZ,
                    clicked[0], clicked[1], clicked[2]));
        }

        try {
            if (known(x, y, z)) {
                var targetState = World.getBlockState(x, y, z);
                String targetName = targetState.block().name();
                var type = (targetName.equals("chest") || targetName.equals("trapped_chest"))
                        ? targetState.getProperty(BlockStateProperties.CHEST_TYPE) : null;
                if (type != null && type != ChestType.SINGLE) {
                    var identity = DoubleChestIdentity.resolve(x, y, z, true);
                    out.append("; double_chest_identity=")
                            .append(identity.inventoryX()).append(',')
                            .append(identity.inventoryY()).append(',')
                            .append(identity.inventoryZ())
                            .append(" known=").append(identity.identityKnown());
                    for (int[] half : identity.blocks()) {
                        if (half[0] != x || half[1] != y || half[2] != z) {
                            out.append(" partner=").append(describe(half[0], half[1], half[2]));
                        }
                    }
                }
                out.append("; chest_overhead=").append(chestOverhead(x, y, z));
            }
        } catch (RuntimeException e) {
            out.append("; double_chest_identity=unavailable(")
                    .append(e.getClass().getSimpleName()).append(')');
        }

        out.append("; adjacent=");
        String[] labels = {"up", "down", "north", "south", "east", "west"};
        int[][] offsets = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1},
                {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
        for (int i = 0; i < offsets.length; i++) {
            if (i > 0) out.append(" | ");
            out.append(labels[i]).append(':').append(describe(
                    x + offsets[i][0], y + offsets[i][1], z + offsets[i][2]));
        }

        List<String> nearby = new ArrayList<>();
        int total = 0;
        int unknown = 0;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int bx = x + dx, by = y + dy, bz = z + dz;
                    if (!known(bx, by, bz)) {
                        unknown++;
                        continue;
                    }
                    try {
                        String name = World.getBlockState(bx, by, bz).block().name();
                        if (!isStorageBlock(name)) continue;
                        total++;
                        if (nearby.size() < NEARBY_LIMIT) {
                            nearby.add(describe(bx, by, bz));
                        }
                    } catch (RuntimeException e) {
                        unknown++;
                    }
                }
            }
        }
        out.append("; nearby_containers_hoppers(radius_xz=2,y=-1..2,total=")
                .append(total).append(",unloaded_or_unknown=").append(unknown)
                .append(",omitted=").append(Math.max(0, total - nearby.size()))
                .append(")=").append(String.join(" | ", nearby));
        return out.toString();
    }

    /** Report both lid positions; the indexed double chest may use only one coordinate. */
    public static String chestOverhead(int x, int y, int z) {
        StringBuilder out = new StringBuilder("primary=").append(describe(x, y + 1, z));
        if (!known(x, y, z)) return out.append(", partner=unknown").toString();
        try {
            var identity = DoubleChestIdentity.resolve(x, y, z, true);
            if (!identity.identityKnown() || identity.blocks().size() != 2) {
                return out.append(", partner=unknown").toString();
            }
            for (int[] half : identity.blocks()) {
                if (half[0] != x || half[1] != y || half[2] != z) {
                    return out.append(", partner=")
                            .append(describe(half[0], half[1] + 1, half[2])).toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the primary lid evidence when the partner is unavailable.
        }
        return out.append(", partner=unknown").toString();
    }

    private static boolean isStorageBlock(String name) {
        return name.contains("chest") || name.contains("hopper")
                || name.contains("shulker_box") || name.contains("barrel")
                || name.contains("dispenser") || name.contains("dropper");
    }

    private static String aimHit(double playerX, double playerY, double playerZ,
                                 int x, int y, int z) {
        if (!known(x, y, z)) return "target_unloaded";
        try {
            var center = World.blockInteractionCenter(x, y, z);
            var hit = RaycastHelper.blockRaycast(playerX, playerY + 1.62, playerZ,
                    center.x(), center.y(), center.z(), true);
            return hit.hit() ? describe(hit.x(), hit.y(), hit.z()) : "miss";
        } catch (RuntimeException e) {
            return "unavailable(" + e.getClass().getSimpleName() + ')';
        }
    }

    /** Read-only block-state detail for movement failures, including slab orientation. */
    public static String describeBlock(int x, int y, int z) {
        return describe(x, y, z);
    }

    private static String describe(int x, int y, int z) {
        String pos = x + "," + y + "," + z;
        if (!known(x, y, z)) return pos + "=unloaded_or_out_of_bounds";
        try {
            var state = World.getBlockState(x, y, z);
            String name = state.block().name();
            StringBuilder result = new StringBuilder(pos).append('=').append(name);
            result.append("[solid=").append(state.isSolidBlock());
            var slabType = state.getProperty(BlockStateProperties.SLAB_TYPE);
            if (slabType != null) result.append(",slab_type=").append(slabType);
            if (name.equals("chest") || name.equals("trapped_chest")) {
                var chestType = state.getProperty(BlockStateProperties.CHEST_TYPE);
                result.append(",chest_type=").append(chestType);
                result.append(",facing=")
                        .append(state.getProperty(BlockStateProperties.HORIZONTAL_FACING));
            }
            if (name.equals("hopper")) {
                result.append(",hopper_facing=")
                        .append(state.getProperty(BlockStateProperties.FACING_HOPPER));
            }
            return result.append(']').toString();
        } catch (RuntimeException e) {
            return pos + "=unavailable(" + e.getClass().getSimpleName() + ')';
        }
    }

    private static boolean known(int x, int y, int z) {
        return World.isInWorldBounds(x, y, z)
                && World.isChunkLoadedBlockPos(x, z)
                && World.getChunkSection(x, y, z) != null;
    }
}
