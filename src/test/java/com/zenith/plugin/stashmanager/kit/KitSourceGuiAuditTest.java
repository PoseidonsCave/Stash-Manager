package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitSourceGuiAuditTest {
    @Test
    void identifiesOnlyExactSingleBoxInOpenGui() {
        Map<String, Integer> expected = Map.of("diamond_boots", 17,
                "diamond_boots[mending=1]", 2, "diamond_boots[thorns=3]", 1);
        List<KitSourceGuiAudit.Box> boxes = List.of(
                new KitSourceGuiAudit.Box(0, "shulker_box", 1, expected),
                new KitSourceGuiAudit.Box(1, "shulker_box", 1,
                        Map.of("diamond_boots", 18, "diamond_boots[mending=1]", 2,
                                "diamond_boots[thorns=3]", 1)),
                new KitSourceGuiAudit.Box(2, "red_shulker_box", 1, expected),
                new KitSourceGuiAudit.Box(3, "shulker_box", 2, expected),
                new KitSourceGuiAudit.Box(4, "shulker_box", 1, Map.of()));
        assertEquals(List.of(0), KitSourceGuiAudit.exactMatches(
                boxes, "shulker_box", expected));
    }

    @Test
    void reportsNearestContentsWithoutTreatingThemAsIdentity() {
        Map<String, Integer> expected = Map.of("plain_boots", 17, "mending_boots", 2,
                "cursed_boots", 1);
        List<KitSourceGuiAudit.Box> boxes = List.of(
                new KitSourceGuiAudit.Box(0, "shulker_box", 1,
                        Map.of("plain_boots", 18, "mending_boots", 2, "cursed_boots", 1)),
                new KitSourceGuiAudit.Box(1, "shulker_box", 1,
                        Map.of("plain_boots", 17, "mending_boots", 1, "cursed_boots", 1)),
                new KitSourceGuiAudit.Box(2, "red_shulker_box", 1,
                        Map.of("plain_boots", 17, "mending_boots", 2, "cursed_boots", 1)),
                new KitSourceGuiAudit.Box(3, "shulker_box", 1, Map.of("unrelated", 27)));
        assertEquals(List.of(2, 0, 1), KitSourceGuiAudit.nearestCandidates(
                boxes, "shulker_box", expected, 3).stream()
                .map(KitSourceGuiAudit.Candidate::slot).toList());
        assertEquals(false, KitSourceGuiAudit.nearestCandidates(boxes, "shulker_box", expected, 1)
                .getFirst().sameBoxItem());
        assertEquals(List.of(), KitSourceGuiAudit.exactMatches(
                boxes.subList(0, 2), "shulker_box", expected));
    }
}
