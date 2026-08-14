package com.zenith.plugin.stashmanager.util;

import java.lang.reflect.Field;

import static com.zenith.Globals.CONFIG;

// placeBlockSneak only exists on some Zenith releases' pathfinder config — reflect so this
// compiles across every Stonecutter target and simply no-ops where the field is absent.
public final class PathfinderCompat {

    private static final Field PLACE_BLOCK_SNEAK = find();

    private PathfinderCompat() {}

    private static Field find() {
        try {
            return CONFIG.client.extra.pathfinder.getClass().getField("placeBlockSneak");
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    public static boolean getPlaceBlockSneak() {
        if (PLACE_BLOCK_SNEAK == null) return false;
        try {
            return PLACE_BLOCK_SNEAK.getBoolean(CONFIG.client.extra.pathfinder);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static void setPlaceBlockSneak(boolean value) {
        if (PLACE_BLOCK_SNEAK == null) return;
        try {
            PLACE_BLOCK_SNEAK.setBoolean(CONFIG.client.extra.pathfinder, value);
        } catch (IllegalAccessException ignored) {}
    }
}
