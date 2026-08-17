package com.zenith.plugin.stashmanager.util;

import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;

import java.util.Set;

public final class BlockCompat {

    private BlockCompat() {}

    // Right-clicking these opens a GUI/triggers an interaction instead of placing a block
    // against them (Baritone's placeBlock doesn't sneak), so they're unusable as a support face.
    private static final Set<String> INTERACTABLE_SUFFIXES = Set.of(
        "chest", "barrel", "hopper", "dispenser", "dropper", "furnace", "smoker",
        "crafting_table", "anvil", "enchanting_table", "brewing_stand", "lectern",
        "jukebox", "composter", "cauldron", "campfire", "loom", "grindstone",
        "smithing_table", "cartography_table", "fletching_table", "stonecutter",
        "beacon", "respawn_anchor", "note_block", "bell", "door", "trapdoor",
        "fence_gate", "button", "lever", "bed", "shulker_box"
    );

    public static boolean isAir(Block block) {
        if (block == null) return false;
        return switch (block.name()) {
            case "air", "cave_air", "void_air",
                 "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }

    public static boolean canReplace(Block block) {
        return isAir(block);
    }

    public static boolean isInteractable(Block block) {
        String name = block.name();
        for (String suffix : INTERACTABLE_SUFFIXES) {
            if (name.contains(suffix)) return true;
        }
        return false;
    }

    public static boolean isSolid(int x, int y, int z) {
        return World.getBlockState(x, y, z).isSolidBlock();
    }
}
