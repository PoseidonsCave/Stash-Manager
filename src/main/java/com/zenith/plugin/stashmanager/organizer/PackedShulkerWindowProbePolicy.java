package com.zenith.plugin.stashmanager.organizer;

import java.util.Map;
import java.util.List;

/** Content guard for an explicit, window-verified retry of one packed shulker. */
final class PackedShulkerWindowProbePolicy {
    private PackedShulkerWindowProbePolicy() { }

    record Candidate(int rawSlot, boolean protectedSlot, Map<String, Integer> contents) { }

    static boolean packingHandoff(boolean hasTask, boolean mixedBatch,
                                  boolean fetchedPackingShell) {
        return hasTask && (mixedBatch || fetchedPackingShell);
    }

    static int uniqueMatchingSlot(List<Candidate> boxes, String itemId, int fillMovedUnits,
                                  int compatibleBoxesBeforePlacement, int expectedCount) {
        if (boxes == null || expectedCount <= 0) return -1;
        int match = -1;
        for (Candidate box : boxes) {
            if (!matchesContents(box.contents(), itemId, fillMovedUnits,
                    compatibleBoxesBeforePlacement, expectedCount)) continue;
            if (box.protectedSlot() || match >= 0) return -1;
            match = box.rawSlot();
        }
        return match;
    }

    static boolean matchesContents(Map<String, Integer> contents, String itemId,
                                   int fillMovedUnits, int compatibleBoxesBeforePlacement) {
        return matchesContents(contents, itemId, fillMovedUnits,
                compatibleBoxesBeforePlacement, 0);
    }

    static boolean matchesContents(Map<String, Integer> contents, String itemId,
                                   int fillMovedUnits, int compatibleBoxesBeforePlacement,
                                   int operatorExpectedCount) {
        if (contents == null || itemId == null || fillMovedUnits <= 0
                || contents.size() != 1 || !contents.containsKey(itemId)) return false;
        int total = contents.getOrDefault(itemId, 0);
        if (operatorExpectedCount > 0 && total != operatorExpectedCount) return false;
        return (compatibleBoxesBeforePlacement == 0 && total == fillMovedUnits
                || compatibleBoxesBeforePlacement == 1 && total > fillMovedUnits)
                && total <= 27 * 64;
    }

    static int creditedTaskUnits(int remainingTaskUnits, int fillMovedUnits, int looseTaskUnits) {
        return Math.min(Math.min(Math.max(0, remainingTaskUnits), Math.max(0, fillMovedUnits)),
                Math.max(0, remainingTaskUnits - Math.max(0, looseTaskUnits)));
    }

    static boolean taskCargoAccountedFor(int acquiredTaskUnits, int depositedTaskUnits,
                                         int fillMovedUnits, int looseTaskUnits) {
        int remainingTaskUnits = acquiredTaskUnits - depositedTaskUnits;
        if (acquiredTaskUnits <= 0 || depositedTaskUnits < 0 || remainingTaskUnits <= 0
                || fillMovedUnits <= 0 || looseTaskUnits < 0
                || looseTaskUnits > remainingTaskUnits) return false;
        // A partial box may leave the whole task loose; a full box may contain it all.
        // Any task units no longer loose must fit within the verified fill transaction.
        return remainingTaskUnits - looseTaskUnits <= fillMovedUnits;
    }

    /** Unrelated carried boxes do not invalidate the one collected packing shell. */
    static boolean partialCollectedBoxCountProven(int boxesBeforePlacement,
                                                   int boxesInLiveWindow,
                                                   int matchingPickupItemBoxes) {
        return boxesBeforePlacement >= 1
                && boxesInLiveWindow >= boxesBeforePlacement
                && matchingPickupItemBoxes == 1;
    }

    /** An ordinary first fill may also leave loose cargo when the shell was pre-filled. */
    static boolean partialHandoffTaskEligible(boolean mixedBatch, boolean packingHandoff) {
        return mixedBatch || packingHandoff;
    }
}
