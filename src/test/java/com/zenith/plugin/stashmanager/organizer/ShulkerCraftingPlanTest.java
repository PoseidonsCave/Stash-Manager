package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerCraftingPlanTest {
    @TempDir Path temp;
    @Test
    void placesExactRecipeFromOneShellStack() {
        var plan = ShulkerCraftingPlan.plan(List.of(
                new ShulkerCraftingPlan.Source(10, "shulker_shell", 64),
                new ShulkerCraftingPlan.Source(11, "chest", 8)));

        assertEquals(2, plan.size());
        assertEquals(List.of(2, 8), plan.get(0).gridSlots());
        assertEquals(List.of(5), plan.get(1).gridSlots());
        assertTrue(plan.get(0).returnRemainder());
        assertTrue(plan.get(1).returnRemainder());
    }

    @Test
    void usesTwoSeparateShellStacksWithoutReturningEmptyCursor() {
        var plan = ShulkerCraftingPlan.plan(List.of(
                new ShulkerCraftingPlan.Source(10, "shulker_shell", 1),
                new ShulkerCraftingPlan.Source(11, "chest", 1),
                new ShulkerCraftingPlan.Source(12, "shulker_shell", 1)));

        assertEquals(List.of(2), plan.get(0).gridSlots());
        assertEquals(List.of(5), plan.get(1).gridSlots());
        assertEquals(List.of(8), plan.get(2).gridSlots());
        assertTrue(plan.stream().noneMatch(ShulkerCraftingPlan.Placement::returnRemainder));
    }

    @Test
    void neverProducesPartialRecipe() {
        assertTrue(ShulkerCraftingPlan.plan(List.of(
                new ShulkerCraftingPlan.Source(10, "shulker_shell", 1),
                new ShulkerCraftingPlan.Source(11, "chest", 1))).isEmpty());
    }

    @Test
    void inventoryInsideStoredShulkersIsNotLooseCraftingSupply() {
        var container = new ContainerEntry(1, 2, 3, "minecraft:chest", false,
                Map.of("shulker_shell", 66), 1,
                List.of(new ContainerEntry.ShulkerDetail(0, "purple",
                        Map.of("shulker_shell", 64))), 1L);

        assertEquals(2, StashOrganizer.looseItemCount(container, "shulker_shell"));
    }

    @Test
    void scannedStandaloneChestSuppliesCraftingWithoutAssignmentButBotSupplyDoesNot() throws Exception {
        int[] material = {4, 70, 4};
        var config = new StashManagerConfig();
        config.pos1 = new int[]{0, 60, 0};
        config.pos2 = new int[]{10, 80, 10};
        var index = new ContainerIndex();
        index.put(new ContainerEntry(4, 70, 4, "minecraft:chest", false,
                Map.of("shulker_shell", 3, "chest", 2), 0, List.of(), 1L));
        var organizer = new StashOrganizer(config, index, new OrganizerJournalStore(
                temp.resolve("plan.json"), temp.resolve("checkpoint.json")));
        var count = StashOrganizer.class.getDeclaredMethod("countItemInRegion", String.class);
        count.setAccessible(true);

        assertEquals(3, count.invoke(organizer, "shulker_shell"));
        assertEquals(2, count.invoke(organizer, "chest"));
        config.supplyChests.add(material);
        assertEquals(0, count.invoke(organizer, "shulker_shell"));
    }

    @Test
    void automaticSupplyStaysInsideRegionAndExcludesProtectedChests() throws Exception {
        var config = new StashManagerConfig();
        config.pos1 = new int[]{0, 60, 0};
        config.pos2 = new int[]{10, 80, 10};
        var index = new ContainerIndex();
        index.put(new ContainerEntry(1, 70, 1, "minecraft:chest", false,
                Map.of("shulker_shell", 2), 0, List.of(), 1L));
        index.put(new ContainerEntry(2, 70, 2, "minecraft:chest", false,
                Map.of("shulker_shell", 3), 0, List.of(), 1L));
        index.put(new ContainerEntry(3, 70, 3, "minecraft:chest", false,
                Map.of("shulker_shell", 4), 0, List.of(), 1L));
        index.put(new ContainerEntry(11, 70, 3, "minecraft:chest", false,
                Map.of("shulker_shell", 5), 0, List.of(), 1L));
        index.put(new ContainerEntry(4, 70, 4, "minecraft:barrel", false,
                Map.of("shulker_shell", 6), 0, List.of(), 1L));
        markProtected(index, "blacklistedChests", 2, 70, 2);
        markProtected(index, "exportChests", 3, 70, 3);

        assertEquals(2, countIndexed(organizer(config, index), "shulker_shell"));
        config.craftMaterialChests.add(new int[]{4, 70, 4});
        assertEquals(8, countIndexed(organizer(config, index), "shulker_shell"));
    }

    @Test
    void doubleChestCountsOnceAndEitherProtectedHalfExcludesIt() throws Exception {
        var config = new StashManagerConfig();
        config.pos1 = new int[]{0, 60, 0};
        config.pos2 = new int[]{10, 80, 10};
        var index = new ContainerIndex();
        index.put(doubleChestHalf(4, 7));
        index.put(doubleChestHalf(5, 7));
        assertEquals(7, countIndexed(organizer(config, index), "shulker_shell"));

        markProtected(index, "blacklistedChests", 5, 70, 4);
        assertEquals(0, countIndexed(organizer(config, index), "shulker_shell"));
    }

    @Test
    void placedShulkerSuppliesChestItemsWithoutAssignmentUnlessItIsTheWorksite() throws Exception {
        var config = new StashManagerConfig();
        config.pos1 = new int[]{0, 60, 0};
        config.pos2 = new int[]{10, 80, 10};
        var index = new ContainerIndex();
        index.put(new ContainerEntry(5, 70, 5, "minecraft:shulker_box", false,
                Map.of("chest", 139), 0, List.of(), 1L));
        var organizer = organizer(config, index);

        assertEquals(139, countIndexed(organizer, "chest"));
        Field worksite = StashOrganizer.class.getDeclaredField("reconciliationWorksite");
        worksite.setAccessible(true);
        worksite.set(organizer, new int[]{5, 70, 5});
        assertEquals(0, countIndexed(organizer, "chest"));
        assertEquals(139, countIndexedRaw(organizer, "chest"));

        worksite.set(organizer, null);
        markProtected(index, "blacklistedChests", 5, 70, 5);
        assertEquals(0, countIndexed(organizer, "chest"));
        assertEquals(139, countIndexedRaw(organizer, "chest"));
    }

    @Test
    void sourceLimitStillIncludesBothRecipeIngredients() {
        List<ContainerEntry> sources = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            sources.add(new ContainerEntry(x, 70, 4, "minecraft:chest", false,
                    Map.of("shulker_shell", 2), 0, List.of(), 1L));
        }
        sources.add(new ContainerEntry(8, 70, 4, "minecraft:chest", false,
                Map.of("chest", 1), 0, List.of(), 1L));

        List<ContainerEntry> selected = StashOrganizer.selectCraftMaterialSources(sources, 2, 1);
        assertEquals(8, selected.size());
        assertTrue(selected.stream().anyMatch(entry -> entry.items().containsKey("chest")));
        assertTrue(selected.stream().anyMatch(entry -> entry.items().containsKey("shulker_shell")));
    }

    private StashOrganizer organizer(StashManagerConfig config, ContainerIndex index) {
        return new StashOrganizer(config, index, new OrganizerJournalStore(
                temp.resolve("plan.json"), temp.resolve("checkpoint.json")));
    }

    private static Object countIndexed(StashOrganizer organizer, String itemId) throws Exception {
        var count = StashOrganizer.class.getDeclaredMethod("countItemInRegion", String.class);
        count.setAccessible(true);
        return count.invoke(organizer, itemId);
    }

    private static Object countIndexedRaw(StashOrganizer organizer, String itemId) throws Exception {
        var count = StashOrganizer.class.getDeclaredMethod("countIndexedLooseItemInRegion", String.class);
        count.setAccessible(true);
        return count.invoke(organizer, itemId);
    }

    private static ContainerEntry doubleChestHalf(int x, int shells) {
        return new ContainerEntry(x, 70, 4, "minecraft:chest", true,
                Map.of("shulker_shell", shells), 0, List.of(), 1L,
                null, null, 4, 70, 4, true, "X");
    }

    @SuppressWarnings("unchecked")
    private static void markProtected(ContainerIndex index, String fieldName, int x, int y, int z)
            throws Exception {
        Field field = ContainerIndex.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<Long, int[]>) field.get(index)).put(new ContainerEntry(x, y, z, "minecraft:chest",
                false, Map.of(), 0, List.of(), 0L).posKey(), new int[]{x, y, z});
    }
}
