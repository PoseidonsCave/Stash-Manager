package com.zenith.plugin.stashmanager.util;

import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;

public final class BlockCompat {

    private BlockCompat() {}

    public static boolean isAir(Block block) {
        return switch (block.name()) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }

    public static boolean canReplace(Block block) {
        return isAir(block);
    }

    public static boolean isSolid(int x, int y, int z) {
        return World.getBlockState(x, y, z).isSolidBlock();
    }
}