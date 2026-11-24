package com.zenith.plugin.stashmanager.retriever;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceRoutePlannerTest {
    @Test void nearbyUsefulSourcePrecedesAFullButDistantSource() {
        var nearby = entry(10, 16);
        var distant = entry(200, 128);
        var result = SourceRoutePlanner.order(List.of(distant, nearby), 0, 0, 0,
                e -> e.items().get("obsidian"), e -> 0, Set.of(), Set.of());
        assertEquals(List.of(nearby, distant), result);
    }

    @Test void largeSourceCanWinWhenItAvoidsManySmallTrips() {
        var tiny = entry(20, 1);
        var full = entry(80, 128);
        var result = SourceRoutePlanner.order(List.of(tiny, full), 0, 0, 0,
                e -> e.items().get("obsidian"), e -> 0, Set.of(), Set.of());
        assertEquals(List.of(full, tiny), result);
    }

    @Test void previouslyEmptySourceFallsBackAfterUntriedSource() {
        var emptyLastTime = entry(5, 64);
        var other = entry(30, 64);
        var result = SourceRoutePlanner.order(List.of(emptyLastTime, other), 0, 0, 0,
                e -> e.items().get("obsidian"), e -> 0, Set.of(),
                Set.of(emptyLastTime.posKey()));
        assertEquals(List.of(other, emptyLastTime), result);
    }

    private static ContainerEntry entry(int x, int units) {
        return new ContainerEntry(x, 0, 0, "minecraft:chest", false,
                Map.of("obsidian", units), 0, List.of(), 0);
    }
}
