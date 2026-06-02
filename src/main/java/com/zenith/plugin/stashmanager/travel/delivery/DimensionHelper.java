package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.feature.player.World;

/** Utility for checking the current game dimension. */
public final class DimensionHelper {

    private DimensionHelper() {}

    /** Returns the current dimension name, e.g. "minecraft:the_nether". */
    public static String currentDimName() {
        try {
            return World.getCurrentDimension().name();
        } catch (Exception e) {
            return "";
        }
    }

    /** True if the player is currently in the Nether. */
    public static boolean isNether() {
        return currentDimName().contains("nether");
    }

    /** True if the player is currently in the Overworld. */
    public static boolean isOverworld() {
        return currentDimName().contains("overworld");
    }
}
