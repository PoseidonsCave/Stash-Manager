package com.zenith.plugin.stashmanager.orchestration;

/** Pure safety policy for selecting a temporary shulker packing worksite. */
public final class ShulkerWorksitePolicy {
    private ShulkerWorksitePolicy() { }

    public static boolean isSafe(
            int verticalOffsetFromPlayerFeet,
            boolean targetReplaceable,
            boolean headSpaceReplaceable,
            boolean floorIsAir,
            boolean floorIsSolid,
            boolean floorIsInteractable,
            boolean neighborIsInteractable,
            boolean indexedContainerUnderfoot) {
        return verticalOffsetFromPlayerFeet == 0
                && targetReplaceable
                && headSpaceReplaceable
                && !floorIsAir
                && floorIsSolid
                && !floorIsInteractable
                && !neighborIsInteractable
                && !indexedContainerUnderfoot;
    }
}
