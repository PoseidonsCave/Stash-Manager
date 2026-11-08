package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LaneClearanceSchedulerTest {
    private final int[] lime = {0, 70, 0};
    private final int[] cobble = {4, 70, 0};
    private final int[] imports = {8, 70, 0};
    private final Map<String, StashOrganizer.Column> lanes = Map.of(
            "lime_wool", new StashOrganizer.Column(0, List.of(lime)),
            "cobblestone", new StashOrganizer.Column(1, List.of(cobble)));

    @Test
    void allForeignBoxesLeaveBeforeTheFirstIncomingBox() {
        var incoming = box(imports, lime, "lime_wool");
        var first = box(lime, cobble, "cobblestone");
        var second = box(lime, cobble, "cobblestone");
        var pending = new ArrayList<>(List.of(incoming, first, second));
        var scheduler = scheduler(pending);
        assertFalse(scheduler.ready(incoming));
        assertTrue(scheduler.ready(first));
        pending.remove(first);
        assertFalse(scheduler(pending).ready(incoming));
        pending.remove(second);
        assertTrue(scheduler(pending).ready(incoming));
    }

    @Test
    void looseAndMixedClearanceAreDependenciesToo() {
        var incoming = box(imports, lime, "lime_wool");
        var loose = new StashOrganizer.MoveTask(lime, cobble, "cobblestone");
        var mixed = StashOrganizer.MoveTask.mixed(lime, imports, "shulker_box", "mixed", Map.of());
        assertFalse(scheduler(List.of(incoming, loose)).ready(incoming));
        assertFalse(scheduler(List.of(incoming, mixed)).ready(incoming));
        assertTrue(scheduler(List.of(incoming, mixed)).ready(mixed));
    }

    @Test
    void cyclesAreBlockedButIndependentWorkAndHeldCargoRemainAvailable() {
        var toLime = box(cobble, lime, "lime_wool");
        var toCobble = box(lime, cobble, "cobblestone");
        var independent = StashOrganizer.MoveTask.mixed(new int[]{12, 70, 0}, imports,
                "shulker_box", "mixed", Map.of());
        var scheduler = scheduler(List.of(toLime, toCobble, independent));
        assertFalse(scheduler.ready(toLime));
        assertFalse(scheduler.ready(toCobble));
        assertTrue(scheduler.ready(independent));
        assertTrue(scheduler.ready(toLime.markAlreadyInInventory()));
    }

    @Test
    void sameClassStockDoesNotCreateASelfDependency() {
        var incoming = box(imports, lime, "lime_wool");
        var sameClass = box(lime, lime, "lime_wool");
        assertTrue(scheduler(List.of(incoming, sameClass)).ready(incoming));
    }

    @Test
    void doubleChestPartnerBlocksTheSameLaneNotItsNeighbor() {
        ContainerEntry doubleChest = new ContainerEntry(0, 70, 0, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1, null, null, 0, 70, 0, true, "Z");
        var incoming = box(imports, lime, "lime_wool");
        var evacuation = box(new int[]{0, 70, 1}, cobble, "cobblestone");
        var scheduler = new LaneClearanceScheduler(lanes, List.of(doubleChest), List.of(incoming, evacuation));
        assertFalse(scheduler.ready(incoming));
        assertTrue(scheduler.ready(evacuation));
    }

    @Test
    void aliasesCannotAssignTwoClassesToOnePhysicalInventory() {
        int[] partner = {0, 70, 1};
        ContainerEntry doubleChest = new ContainerEntry(0, 70, 0, "minecraft:chest", true,
                Map.of(), 0, List.of(), 1, null, null, 0, 70, 0, true, "Z");
        var conflicting = Map.of("lime_wool", lanes.get("lime_wool"),
                "cobblestone", new StashOrganizer.Column(2, List.of(partner)));
        var scheduler = new LaneClearanceScheduler(conflicting, List.of(doubleChest), List.of());
        assertFalse(scheduler.ready(box(imports, lime, "lime_wool")));
        assertFalse(scheduler.ready(box(imports, partner, "cobblestone")));
    }

    @Test
    void chainedLaneMovesRunFromTheEmptyEndFirst() {
        int[] third = {12, 70, 0};
        var assignments = Map.of("lime_wool", lanes.get("lime_wool"),
                "cobblestone", lanes.get("cobblestone"),
                "obsidian", new StashOrganizer.Column(2, List.of(third)));
        var a = box(imports, lime, "lime_wool");
        var b = box(lime, cobble, "cobblestone");
        var c = box(cobble, third, "obsidian");
        var pending = new ArrayList<>(List.of(a, b, c));
        List<StashOrganizer.MoveTask> completed = new ArrayList<>();
        while (!pending.isEmpty()) {
            var scheduler = new LaneClearanceScheduler(assignments, List.of(), pending);
            var next = pending.stream().filter(scheduler::ready).findFirst().orElseThrow();
            completed.add(next);
            pending.remove(next);
        }
        assertEquals(List.of(c, b, a), completed);
    }

    private LaneClearanceScheduler scheduler(List<StashOrganizer.MoveTask> pending) {
        return new LaneClearanceScheduler(lanes, List.of(), pending);
    }

    private static StashOrganizer.MoveTask box(int[] from, int[] to, String contents) {
        return new StashOrganizer.MoveTask(from, to, "shulker_box", contents);
    }
}
