package com.zenith.plugin.stashmanager.organizer;

import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class PackingCapacityRegressionTest {
    @Test
    void fillsOccupiedSlotsThenRejectsTheFinishedBoxForTheNextBatch() {
        List<ItemStack> contents = crystals(27, 64);
        contents.getLast().setAmount(63);
        ItemStack cargo = crystal(64);
        ItemStack box = box(contents);
        assertTrue(StashOrganizer.packingShulkerCanAccept(box, "end_crystal", List.of(cargo)));
        assertEquals(1, ContainerCapacitySnapshot.fromShulker(box).orElseThrow().matchingHeadroom(cargo));

        contents.getLast().setAmount(64);
        assertTrue(ContainerCapacitySnapshot.fromShulker(box).orElseThrow().atMaximumCapacity());
        assertFalse(StashOrganizer.packingShulkerCanAccept(box, "end_crystal", List.of(cargo)));
        assertTrue(StashOrganizer.packingShulkerCanAccept(box(List.of()), "end_crystal", List.of(cargo)));
    }

    @Test
    void fewerThan1728ItemsDoesNotProveCompatibleSpace() {
        List<ItemStack> contents = crystals(27, 1);
        DataComponents different = new DataComponents(new HashMap<>());
        different.put(DataComponentTypes.DAMAGE, 1);
        for (int i = 0; i < contents.size(); i++) {
            contents.set(i, new ItemStack(crystal(1).getId(), 1, different));
        }
        assertFalse(StashOrganizer.packingShulkerCanAccept(box(contents), "end_crystal", List.of(crystal(64))));
        // Do not stop at the first incompatible stack when another stack can merge.
        assertTrue(StashOrganizer.packingShulkerCanAccept(box(contents), "end_crystal",
                List.of(crystal(64), new ItemStack(crystal(1).getId(), 2, different))));
    }

    @Test
    void nullAndEmptyComponentPatchesHaveTheSameMergeCapacity() {
        List<ItemStack> contents = crystals(27, 1);
        ItemStack cargo = new ItemStack(crystal(1).getId(), 64, new DataComponents(new HashMap<>()));
        assertTrue(StashOrganizer.packingShulkerCanAccept(box(contents), "end_crystal", List.of(cargo)));
    }

    @Test
    void respectsToolsAndPerStackLimits() {
        ItemStack tool = new ItemStack(ItemRegistry.REGISTRY.get("diamond_sword").id(), 1);
        var tools = ContainerCapacitySnapshot.read(27, slot -> tool);
        assertTrue(tools.atMaximumCapacity());
        assertFalse(tools.accepts(tool));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.MAX_STACK_SIZE, 16);
        ItemStack limited = new ItemStack(crystal(1).getId(), 16, components);
        var limitedBox = ContainerCapacitySnapshot.read(27, slot -> limited);
        assertTrue(limitedBox.atMaximumCapacity());
        assertFalse(limitedBox.accepts(limited));
    }

    @Test
    void rejectsMixedBoxesAndWrongStorageClasses() {
        ItemStack stone = new ItemStack(ItemRegistry.REGISTRY.get("stone").id(), 1);
        assertFalse(StashOrganizer.packingShulkerCanAccept(box(List.of(stone)), "end_crystal", List.of(crystal(1))));
        assertFalse(StashOrganizer.packingShulkerCanAccept(box(List.of(stone, crystal(1))), "end_crystal", List.of(crystal(1))));
    }

    @Test
    void recentlyWritableImportWinsWithoutDiscardingFullInventories() {
        ImportCapacityCache cache = new ImportCapacityCache();
        var full = ContainerCapacitySnapshot.read(54, slot -> crystal(64));
        for (long key = 1; key <= 120; key++) cache.record(key, full, 1000);
        var partial = ContainerCapacitySnapshot.read(54, slot -> crystal(slot == 0 ? 63 : 64));
        cache.record(121, partial, 1000);
        List<Long> ordered = LongStream.rangeClosed(1, 121).boxed()
                .sorted(Comparator.comparingInt(key -> cache.rank(key, crystal(1), false, 2000)))
                .toList();
        assertEquals(121L, ordered.getFirst());
        assertEquals(121, ordered.size()); // Still recheck every candidate before a capacity stop.
        assertEquals(2, cache.rank(121, null, true, 2000)); // A shulker needs an empty slot.

        cache.record(1, ContainerCapacitySnapshot.read(54, slot -> slot == 0 ? null : crystal(64)), 3000);
        assertEquals(0, cache.rank(1, null, true, 3000)); // A source take frees real space.
        assertEquals(1, cache.rank(2, crystal(1), false, 301000)); // Expired observations are unknown.
        cache.reset();
        assertEquals(1, cache.rank(121, crystal(1), false, 301001));
    }

    @Test
    void routesFinishedBoxToExactLaneAndKeepsCascadeInsideThatLane() {
        int[] intake = {10, 90, 10};
        int[] lower = {10, 89, 11};
        int[] imports = {1, 64, 1};
        var lanes = Map.of("end_crystal", new StashOrganizer.Column(1, List.of(intake, lower)),
                "diamond_pickaxe[fortune]", new StashOrganizer.Column(2, List.of(new int[]{20, 90, 10})),
                "diamond_pickaxe[silk_touch]", new StashOrganizer.Column(3, List.of(new int[]{30, 90, 10})));
        assertArrayEquals(intake, StashOrganizer.packedLaneDestination("end_crystal", lanes, imports));
        assertArrayEquals(lower, StashOrganizer.packedLaneDestination("end_crystal", lanes, lower));
        assertArrayEquals(new int[]{20, 90, 10}, StashOrganizer.packedLaneDestination("diamond_pickaxe[fortune]", lanes, imports));
        assertArrayEquals(new int[]{30, 90, 10}, StashOrganizer.packedLaneDestination("diamond_pickaxe[silk_touch]", lanes, imports));
        assertArrayEquals(imports, StashOrganizer.packedLaneDestination("goat_horn", lanes, imports));
        assertEquals(3, lanes.size());
    }

    @Test
    void legacyCheckpointWithoutFillEvidenceIsNotAZeroMovementFailure() {
        assertFalse(ShulkerFillPolicy.stalled(-1, 128));
        assertTrue(ShulkerFillPolicy.stalled(0, 128));
    }

    @Test
    void confirmedFullBoxHandoffContinuesOnboardCargoWithoutAddingTasks() throws Exception {
        StashOrganizer organizer = packingOrganizer(64);
        Map<String, Map<String, Object>> events = new HashMap<>();
        organizer.setEventCallback(events::put);

        completeStore(organizer, 128);

        assertEquals(11, organizer.getCompletedTasks());
        assertEquals(50, organizer.getTotalTasks());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        Map<String, Object> delivery = events.get("organize_packed_shulker_delivered");
        assertNotNull(delivery);
        assertEquals("assigned_lane", delivery.get("destination_kind"));
        assertEquals(true, delivery.get("at_maximum_capacity"));
        assertEquals(128, delivery.get("remaining_loose_units"));
        // A second output from the same source batch must not count that source twice.
        set(organizer, "shulkerFillMovedUnits", 64);
        completeStore(organizer, 64);
        assertEquals(11, organizer.getCompletedTasks());
        assertEquals(50, organizer.getTotalTasks());
    }

    @Test
    void zeroMovementOutputRetriesAnotherBoxWithoutCompletingTheSource() throws Exception {
        StashOrganizer organizer = packingOrganizer(0);
        Map<String, Map<String, Object>> events = new HashMap<>();
        organizer.setEventCallback(events::put);

        completeStore(organizer, 128);

        assertEquals(10, organizer.getCompletedTasks());
        assertEquals(50, organizer.getTotalTasks());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertTrue(events.containsKey("organize_packing_box_skipped"));
        assertFalse(events.containsKey("organize_packed_shulker_delivered"));
    }

    @Test
    void packedShulkerWindowGetsABoundedRescanBeforeRecovery() {
        assertTrue(StashOrganizer.shouldAwaitPackedShulkerInventorySync(0, 0, 40));
        assertTrue(StashOrganizer.shouldAwaitPackedShulkerInventorySync(0, 39, 40));
        assertFalse(StashOrganizer.shouldAwaitPackedShulkerInventorySync(0, 40, 40));
        assertFalse(StashOrganizer.shouldAwaitPackedShulkerInventorySync(1, 0, 40));
    }

    private static StashOrganizer packingOrganizer(int movedUnits) throws Exception {
        StashOrganizer organizer = new StashOrganizer(new StashManagerConfig(), new ContainerIndex());
        int[] intake = {10, 90, 10};
        set(organizer, "state", StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        set(organizer, "packItemId", "end_crystal");
        set(organizer, "packDestination", intake);
        set(organizer, "columnAssignment", Map.of("end_crystal", new StashOrganizer.Column(1, List.of(intake))));
        set(organizer, "reconciliationStation", new int[]{1, 64, 1});
        set(organizer, "currentTask", new StashOrganizer.MoveTask(new int[]{2, 64, 2}, intake, "end_crystal"));
        set(organizer, "consolidationMode", true);
        set(organizer, "consolidationSourcesInBatch", 1);
        set(organizer, "completedTasks", 10);
        set(organizer, "totalTasks", 50);
        set(organizer, "shulkerFillMovedUnits", movedUnits);
        set(organizer, "packedAtMaximumCapacity", true);
        return organizer;
    }

    private static void set(StashOrganizer organizer, String field, Object value) throws Exception {
        var target = StashOrganizer.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(organizer, value);
    }

    private static void completeStore(StashOrganizer organizer, int remaining) throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("completePackedShulkerStore", int.class);
        method.setAccessible(true);
        method.invoke(organizer, remaining);
    }

    private static ItemStack crystal(int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(), count);
    }

    private static List<ItemStack> crystals(int slots, int count) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < slots; i++) items.add(crystal(count));
        return items;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box(List<ItemStack> contents) {
        DataComponents components = new DataComponents(new HashMap<>());
        // Optional entries are used by newer protocol versions; both forms are accepted.
        components.put(DataComponentTypes.CONTAINER, (List) contents);
        return new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1, components);
    }
}
