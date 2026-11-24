package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PackingSourceSelectorTest {
    private final PackingSourceSelector selector = new PackingSourceSelector();

    @Test
    void knownEmptyComesBeforeDozensOfNearbyFullChests() {
        List<ContainerEntry> entries = new ArrayList<>();
        for (int x = 0; x < 50; x++) entries.add(box(x, Map.of("stone", 1728), 10));
        ContainerEntry empty = box(100, Map.of(), 10);
        entries.add(empty);
        assertEquals(empty, ordered(entries, "purple_wool", 20).getFirst().container());
    }

    @Test
    void matchingPartialComesBeforeEmptyButDifferentWoolDoesNot() {
        var empty = box(1, Map.of(), 10);
        var other = box(2, Map.of("pink_wool", 20), 10);
        var partial = box(100, Map.of("purple_wool", 1600), 10);
        assertEquals(List.of(partial, empty, other), ordered(List.of(other, empty, partial), "purple_wool", 20)
                .stream().map(PackingSourceSelector.Candidate::container).toList());
    }

    @Test
    void toolsUseUnstackableCapacityAndEnchantmentsStaySeparate() {
        var full = box(1, Map.of("diamond_pickaxe[fortune]", 27), 10);
        var silk = box(2, Map.of("diamond_pickaxe[silk_touch]", 1), 10);
        var partial = box(100, Map.of("diamond_pickaxe[fortune]", 26), 10);
        var entries = ordered(List.of(full, silk, partial), "diamond_pickaxe[fortune]", 20);
        assertEquals(partial, entries.getFirst().container());
        assertEquals(3, entries.get(1).rank());
    }

    @Test
    void legacyAndKnownFullStockRemainFallbackCandidates() {
        var legacy = new ContainerEntry(1, 64, 0, "chest", false,
                Map.of("shulker_box", 1), 1,
                List.of(new ContainerEntry.ShulkerDetail("white", Map.of("purple_wool", 10))), 10);
        var full = box(2, Map.of("purple_wool", 1728), 10);
        var entries = ordered(List.of(full, legacy), "purple_wool", 20);
        assertEquals(2, entries.size());
        assertEquals(2, entries.getFirst().rank());
        assertEquals(3, entries.getLast().rank());
    }

    @Test
    void usesFreshestPhysicalDoubleChestOnce() {
        var oldHalf = half(10, Map.of("purple_wool", 1), 10);
        var newHalf = half(11, Map.of("purple_wool", 1728), 20);
        var entries = ordered(List.of(oldHalf, newHalf), "purple_wool", 30);
        assertEquals(1, entries.size());
        assertEquals(newHalf, entries.getFirst().container());
        assertEquals(3, entries.getFirst().rank());
        assertTrue(selector.candidates(List.of(oldHalf, newHalf), "purple_wool",
                Set.of(PackingSourceSelector.inventoryKey(oldHalf)), 0, 64, 0, 30).isEmpty());
    }

    @Test
    void recentMissSurvivesNewBatchButExpiresAndChangedStockInvalidatesIt() {
        var entry = box(1, Map.of("purple_wool", 1700), 10);
        selector.recordMiss(entry, "purple_wool", 100);
        assertEquals(4, ordered(List.of(entry), "purple_wool", 101).getFirst().rank());
        assertEquals(4, ordered(List.of(box(1, Map.of("purple_wool", 1700), 20)), "purple_wool", 102).getFirst().rank());
        assertEquals(1, ordered(List.of(box(1, Map.of(), 20)), "purple_wool", 102).getFirst().rank());
        assertEquals(0, ordered(List.of(entry), "purple_wool", 300_100).getFirst().rank());
        selector.reset();
        assertEquals(0, ordered(List.of(entry), "purple_wool", 101).getFirst().rank());
    }

    @Test
    void missIsClassSpecificAndNeverPermanentlyHidesAChest() {
        var empty = box(1, Map.of(), 10);
        selector.recordMiss(empty, "purple_wool", 100);
        assertEquals(1, ordered(List.of(empty), "pink_wool", 101).getFirst().rank());
        assertEquals(1, ordered(List.of(empty), "purple_wool", 101).size());
    }

    @Test
    void prefersFreshStockThenNearestCandidateWithinSameRank() {
        var stale = box(1, Map.of(), 1);
        var fresh = box(100, Map.of(), 400_000);
        assertEquals(fresh, ordered(List.of(stale, fresh), "purple_wool", 400_010).getFirst().container());
        var near = box(2, Map.of(), 400_000);
        assertEquals(near, ordered(List.of(fresh, near), "purple_wool", 400_010).getFirst().container());
    }

    private List<PackingSourceSelector.Candidate> ordered(List<ContainerEntry> entries, String key, long now) {
        return selector.candidates(entries, key, Set.of(), 0, 64, 0, now);
    }

    private static ContainerEntry box(int x, Map<String, Integer> contents, long time) {
        return new ContainerEntry(x, 64, 0, "chest", false, Map.of("shulker_box", 1), 1,
                List.of(new ContainerEntry.ShulkerDetail(0, "white", contents)), time);
    }

    private static ContainerEntry half(int x, Map<String, Integer> contents, long time) {
        return new ContainerEntry(x, 64, 0, "chest", true, Map.of("shulker_box", 1), 1,
                List.of(new ContainerEntry.ShulkerDetail(0, "white", contents)), time,
                null, null, 10, 64, 0, true, "X");
    }
}
