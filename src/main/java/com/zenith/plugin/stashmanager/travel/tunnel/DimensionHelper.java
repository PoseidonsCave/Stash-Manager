package com.zenith.plugin.stashmanager.travel.tunnel;

import com.zenith.feature.player.World;

final class DimensionHelper {

    private DimensionHelper() {}

    static String currentDimName() {
        try {
            return World.getCurrentDimension().name();
        } catch (Exception e) {
            return "";
        }
    }
}