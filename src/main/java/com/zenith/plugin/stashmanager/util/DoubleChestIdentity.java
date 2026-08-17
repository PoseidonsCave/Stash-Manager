package com.zenith.plugin.stashmanager.util;

import com.zenith.feature.player.World;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.ChestType;
import com.zenith.mc.block.properties.api.BlockStateProperties;

import java.util.List;

/** Resolves both blocks of a double chest to one deterministic inventory identity. */
public final class DoubleChestIdentity {
    private DoubleChestIdentity() {}

    public record Resolution(int inventoryX, int inventoryY, int inventoryZ,
                             boolean identityKnown, List<int[]> blocks) {}

    public static boolean isDoubleChest(int x, int y, int z) {
        ChestType chestType = World.getBlockState(x, y, z)
                .getProperty(BlockStateProperties.CHEST_TYPE);
        return chestType != null && chestType != ChestType.SINGLE;
    }

    public static Resolution resolve(int x, int y, int z, boolean expectedDouble) {
        if (!expectedDouble) {
            return new Resolution(x, y, z, true, List.of(new int[]{x, y, z}));
        }

        var state = World.getBlockState(x, y, z);
        ChestType chestType = state.getProperty(BlockStateProperties.CHEST_TYPE);
        Direction facing = state.getProperty(BlockStateProperties.HORIZONTAL_FACING);
        if (chestType == null || chestType == ChestType.SINGLE || facing == null) {
            return new Resolution(x, y, z, false, List.of(new int[]{x, y, z}));
        }

        Direction partnerDirection = partnerDirection(chestType, facing);
        if (partnerDirection == null) {
            return new Resolution(x, y, z, false, List.of(new int[]{x, y, z}));
        }

        int partnerX = x + partnerDirection.x();
        int partnerZ = z + partnerDirection.z();
        var candidate = World.getBlockState(partnerX, y, partnerZ);
        ChestType candidateType = candidate.getProperty(BlockStateProperties.CHEST_TYPE);
        Direction candidateFacing = candidate.getProperty(BlockStateProperties.HORIZONTAL_FACING);
        if (!candidate.block().equals(state.block())
                || candidateType != chestType.getOpposite()
                || candidateFacing != facing
                || partnerDirection(candidateType, candidateFacing) != partnerDirection.invert()) {
            return new Resolution(x, y, z, false, List.of(new int[]{x, y, z}));
        }

        boolean firstIsCanonical = x < partnerX || (x == partnerX && z <= partnerZ);
        int canonicalX = firstIsCanonical ? x : partnerX;
        int canonicalZ = firstIsCanonical ? z : partnerZ;
        return new Resolution(canonicalX, y, canonicalZ, true,
                List.of(new int[]{x, y, z}, new int[]{partnerX, y, partnerZ}));
    }

    /** Mirrors vanilla ChestBlock#getConnectedDirection. */
    static Direction partnerDirection(ChestType chestType, Direction facing) {
        if (chestType == null || chestType == ChestType.SINGLE || facing == null) return null;
        Direction clockwise = switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> null;
        };
        if (clockwise == null) return null;
        return chestType == ChestType.LEFT ? clockwise : clockwise.invert();
    }
}
