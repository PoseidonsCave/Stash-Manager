package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/** Chooses one region for lane audits without treating intake or scan-only stock as storage. */
public final class RegionCapacitySelector {
    private RegionCapacitySelector() {}

    public enum Result { SELECTED, NO_REGION, NO_STORAGE_REGION, AMBIGUOUS }

    public record Selection(Result result, @Nullable String name,
                            @Nullable int[] pos1, @Nullable int[] pos2) {
        public boolean selected() { return result == Result.SELECTED; }
    }

    public static Selection select(List<DatabaseManager.SavedRegion> regions,
                                   @Nullable int[] configuredPos1,
                                   @Nullable int[] configuredPos2) {
        if (regions == null || regions.isEmpty()) {
            if (configuredPos1 == null || configuredPos2 == null) {
                return new Selection(Result.NO_REGION, null, null, null);
            }
            return new Selection(Result.SELECTED, null, configuredPos1, configuredPos2);
        }

        List<DatabaseManager.SavedRegion> explicitStorage = regions.stream()
                .filter(region -> region.role() == DatabaseManager.RegionRole.STORAGE)
                .toList();
        if (!explicitStorage.isEmpty()) {
            return choose(explicitStorage, configuredPos1, configuredPos2);
        }

        List<DatabaseManager.SavedRegion> allPurpose = regions.stream()
                .filter(region -> region.role() == DatabaseManager.RegionRole.UNASSIGNED)
                .toList();
        if (allPurpose.isEmpty()) {
            return new Selection(Result.NO_STORAGE_REGION, null, null, null);
        }
        return choose(allPurpose, configuredPos1, configuredPos2);
    }

    private static Selection choose(List<DatabaseManager.SavedRegion> candidates,
                                    @Nullable int[] configuredPos1,
                                    @Nullable int[] configuredPos2) {
        if (candidates.size() == 1) return selected(candidates.getFirst());
        for (var region : candidates) {
            if (sameBounds(region.pos1(), region.pos2(), configuredPos1, configuredPos2)) {
                return selected(region);
            }
        }
        return new Selection(Result.AMBIGUOUS, null, null, null);
    }

    private static Selection selected(DatabaseManager.SavedRegion region) {
        return new Selection(Result.SELECTED, region.name(), region.pos1(), region.pos2());
    }

    private static boolean sameBounds(int[] pos1, int[] pos2,
                                      @Nullable int[] configuredPos1,
                                      @Nullable int[] configuredPos2) {
        if (configuredPos1 == null || configuredPos2 == null) return false;
        int[] min = new int[3];
        int[] max = new int[3];
        int[] configuredMin = new int[3];
        int[] configuredMax = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            min[axis] = Math.min(pos1[axis], pos2[axis]);
            max[axis] = Math.max(pos1[axis], pos2[axis]);
            configuredMin[axis] = Math.min(configuredPos1[axis], configuredPos2[axis]);
            configuredMax[axis] = Math.max(configuredPos1[axis], configuredPos2[axis]);
        }
        return Arrays.equals(min, configuredMin) && Arrays.equals(max, configuredMax);
    }
}
