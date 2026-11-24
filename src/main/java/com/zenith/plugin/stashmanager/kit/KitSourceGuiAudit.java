package com.zenith.plugin.stashmanager.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Read-only identity check for shulker item stacks visible in an open chest GUI. */
public final class KitSourceGuiAudit {
    private KitSourceGuiAudit() { }

    public record Box(int slot, String itemId, int count, Map<String, Integer> contents) { }

    /** Similarity is diagnostic only: it never establishes a recoverable source identity. */
    public record Candidate(int slot, boolean sameBoxItem, int shared, int missing, int extra) { }

    public static List<Integer> exactMatches(List<Box> boxes, String expectedItemId,
                                             Map<String, Integer> expectedContents) {
        List<Integer> matches = new ArrayList<>();
        if (boxes == null || expectedItemId == null || expectedContents == null
                || expectedContents.isEmpty()) return matches;
        for (Box box : boxes) {
            if (box != null && box.count() == 1
                    && KitRecoveryIdentity.misplacedSourceMatches(box.itemId(),
                            expectedItemId, expectedContents, box.contents())) {
                matches.add(box.slot());
            }
        }
        return List.copyOf(matches);
    }

    public static List<Candidate> nearestCandidates(List<Box> boxes, String expectedItemId,
                                                    Map<String, Integer> expectedContents, int limit) {
        if (boxes == null || expectedItemId == null || expectedContents == null
                || expectedContents.isEmpty() || limit <= 0) return List.of();
        List<Candidate> candidates = new ArrayList<>();
        for (Box box : boxes) {
            if (box == null || box.count() != 1 || box.contents() == null
                    || box.contents().isEmpty()) continue;
            int shared = 0;
            int missing = 0;
            int extra = 0;
            for (var item : expectedContents.entrySet()) {
                int expected = Math.max(0, item.getValue());
                int observed = Math.max(0, box.contents().getOrDefault(item.getKey(), 0));
                shared += Math.min(expected, observed);
                missing += Math.max(0, expected - observed);
            }
            if (shared == 0) continue;
            for (var item : box.contents().entrySet()) {
                int expected = Math.max(0, expectedContents.getOrDefault(item.getKey(), 0));
                extra += Math.max(0, item.getValue() - expected);
            }
            candidates.add(new Candidate(box.slot(), expectedItemId.equalsIgnoreCase(box.itemId()),
                    shared, missing, extra));
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) ->
                        candidate.missing() + candidate.extra())
                .thenComparing((Candidate candidate) -> !candidate.sameBoxItem())
                .thenComparing(Comparator.comparingInt(Candidate::shared).reversed())
                .thenComparingInt(Candidate::slot));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }
}
