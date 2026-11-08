package com.zenith.plugin.stashmanager.organizer;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Chooses the safe next transaction when the intended destination rejects cargo. */
final class CargoDestinationPolicy {
    enum FullDestinationAction {
        TRY_ALTERNATE_IMPORT,
        STAGE_SHULKER_IN_IMPORT,
        PACK_LOOSE_INTO_IMPORT
    }

    private CargoDestinationPolicy() {}

    static FullDestinationAction afterPermanentCascadeExhausted(
            boolean destinationIsImport,
            boolean cargoIsShulker) {
        if (destinationIsImport) return FullDestinationAction.TRY_ALTERNATE_IMPORT;
        return cargoIsShulker
                ? FullDestinationAction.STAGE_SHULKER_IN_IMPORT
                : FullDestinationAction.PACK_LOOSE_INTO_IMPORT;
    }

    /**
     * An unreachable chest may be bypassed only within its already-assigned storage lane.
     * Progress is monotonic, so a restored checkpoint never retries an earlier chest forever.
     */
    static int nextLaneChest(List<int[]> lane, int[] current, int[] source,
                             ToLongFunction<int[]> inventoryKey,
                             Predicate<int[]> allowed) {
        if (lane == null || current == null || current.length != 3 || inventoryKey == null
                || allowed == null) return -1;
        int currentIndex = -1;
        for (int i = 0; i < lane.size(); i++) {
            if (Arrays.equals(lane.get(i), current)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) return -1;
        long currentKey = inventoryKey.applyAsLong(current);
        long sourceKey = source != null && source.length == 3
                ? inventoryKey.applyAsLong(source) : Long.MIN_VALUE;
        for (int i = currentIndex + 1; i < lane.size(); i++) {
            int[] candidate = lane.get(i);
            if (candidate == null || candidate.length != 3 || !allowed.test(candidate)) continue;
            long key = inventoryKey.applyAsLong(candidate);
            if (key != currentKey && key != sourceKey) return i;
        }
        return -1;
    }
}
