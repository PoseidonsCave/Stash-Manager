package com.zenith.plugin.stashmanager.kit;

import java.util.Map;

/** Narrow identity bounds for a shulker left at a journaled kit worksite. */
public final class KitRecoveryIdentity {
    private KitRecoveryIdentity() { }

    public static boolean sourceMatches(String worldBlockId, String expectedItemId,
                                        Map<String, Integer> before,
                                        Map<String, Integer> observed) {
        if (worldBlockId == null || expectedItemId == null || before == null || observed == null
                || !worldBlockId.equalsIgnoreCase(expectedItemId)) return false;
        return bounded(observed, before) || unopenedSignedBookRoundTrip(before, observed);
    }

    /** A placed unopened box may reserialize signed books. Keep every other item exact. */
    public static boolean unopenedSignedBookRoundTrip(Map<String, Integer> before,
                                                       Map<String, Integer> observed) {
        if (before == null || observed == null || before.isEmpty() || observed.isEmpty()) return false;
        int beforeBooks = 0;
        int observedBooks = 0;
        for (var item : before.entrySet()) {
            if (item.getKey() == null || item.getValue() == null || item.getValue() <= 0)
                return false;
            if (item.getKey().startsWith("written_book[book=")) {
                beforeBooks += item.getValue();
            } else if (!item.getValue().equals(observed.get(item.getKey()))) {
                return false;
            }
        }
        for (var item : observed.entrySet()) {
            if (item.getKey() == null || item.getValue() == null || item.getValue() <= 0)
                return false;
            if (item.getKey().startsWith("written_book[book=")) {
                observedBooks += item.getValue();
            } else if (!item.getValue().equals(before.get(item.getKey()))) {
                return false;
            }
        }
        return beforeBooks > 0 && beforeBooks == observedBooks;
    }

    /** The dropped item may restore the original signed-book encoding. */
    public static boolean recoveredSourceItemMatches(Map<String, Integer> before,
                                                     Map<String, Integer> worldContents,
                                                     Map<String, Integer> carriedContents) {
        return worldContents != null && carriedContents != null
                && (worldContents.equals(carriedContents)
                    || unopenedSignedBookRoundTrip(before, worldContents)
                        && before.equals(carriedContents));
    }

    /** A misplaced, already-retrieved source may only be recovered by exact identity. */
    public static boolean misplacedSourceMatches(String worldBlockId, String expectedItemId,
                                                 Map<String, Integer> expectedContents,
                                                 Map<String, Integer> observed) {
        return worldBlockId != null && expectedItemId != null
                && expectedContents != null && observed != null
                && worldBlockId.equalsIgnoreCase(expectedItemId)
                && expectedContents.equals(observed);
    }

    public static boolean kitMatches(Map<String, Integer> recipe,
                                     Map<String, Integer> packedBefore,
                                     Map<String, Integer> observed) {
        if (recipe == null || packedBefore == null || observed == null
                || !bounded(observed, recipe)) return false;
        for (var item : packedBefore.entrySet()) {
            if (observed.getOrDefault(item.getKey(), 0) < item.getValue()) return false;
        }
        return true;
    }

    private static boolean bounded(Map<String, Integer> observed,
                                   Map<String, Integer> maximum) {
        for (var item : observed.entrySet()) {
            if (item.getKey() == null || item.getValue() == null || item.getValue() < 0
                    || item.getValue() > maximum.getOrDefault(item.getKey(), 0)) return false;
        }
        return true;
    }
}
