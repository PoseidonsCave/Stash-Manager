package com.zenith.plugin.stashmanager.kit;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import com.zenith.mc.item.ItemRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KitHeldBorrowedBoxPolicyTest {
    @TempDir Path temporary;
    private static KitFulfillmentJournalStore.Snapshot blocked() {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128), Map.of(), 25, 24, "obsidian", 128,
                44, new int[]{3, 130, 4}, null, null, null, Map.of(),
                "kit_retrieved_cargo_unverified:95/128", 1L, null, 0L,
                List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{1, 130, 2}, "black_shulker_box",
                        Map.of("obsidian", 1311))), -1, List.of(), Map.of());
    }

    private static ItemStack obsidian(int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get("obsidian").id(), count);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack sourceBox(int count) {
        List<Optional<ItemStack>> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(Optional.empty());
        int index = 0;
        while (count > 0) {
            int amount = Math.min(64, count);
            slots.set(index++, Optional.of(obsidian(amount)));
            count -= amount;
        }
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get("black_shulker_box").id(),
                1, components);
    }

    private static Map<Integer, ItemStack> combined() {
        return Map.of(37, sourceBox(1439), 44,
                new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1));
    }

    @Test void exactCombinedBoxAndAttestedOldStockProduceOnlyAReadOnlyPlan() {
        var result = KitHeldBorrowedBoxPolicy.inspect(blocked(), combined(), 44, 33);
        assertTrue(result.accepted(), result.blocker());
        var plan = result.plan();
        assertEquals(1311, plan.journaledSourceUnits());
        assertEquals(1439, plan.combinedSourceUnits());
        assertEquals(95, plan.retrievedUnits());
        assertEquals(33, plan.keptUnits());
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.COMBINED,
                KitHeldBorrowedBoxPolicy.observe(plan, combined(), 44));

        Map<Integer, ItemStack> extracted = new HashMap<>(combined());
        extracted.put(37, sourceBox(1311));
        extracted.put(13, obsidian(64));
        extracted.put(14, obsidian(64));
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.EXTRACTED,
                KitHeldBorrowedBoxPolicy.observe(plan, extracted, 44));
        extracted.put(13, obsidian(31));
        extracted.put(35, obsidian(33));
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.SPLIT,
                KitHeldBorrowedBoxPolicy.observe(plan, extracted, 44));
    }

    @Test void changedAttestationBoxOrLooseStockCannotBeCredited() {
        assertFalse(KitHeldBorrowedBoxPolicy.inspect(blocked(), combined(), 44, 32).accepted());
        Map<Integer, ItemStack> changed = new HashMap<>(combined());
        changed.put(37, sourceBox(1438));
        assertFalse(KitHeldBorrowedBoxPolicy.inspect(blocked(), changed, 44, 33).accepted());
        changed = new HashMap<>(combined());
        changed.put(20, obsidian(1));
        assertFalse(KitHeldBorrowedBoxPolicy.inspect(blocked(), changed, 44, 33).accepted());
        changed = new HashMap<>(combined());
        changed.put(20, new ItemStack(ItemRegistry.REGISTRY.get("purple_shulker_box").id(), 1));
        assertFalse(KitHeldBorrowedBoxPolicy.inspect(blocked(), changed, 44, 33).accepted());

        var plan = KitHeldBorrowedBoxPolicy.inspect(blocked(), combined(), 44, 33).plan();
        changed = new HashMap<>(combined());
        changed.put(37, sourceBox(1438));
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.CONFLICT,
                KitHeldBorrowedBoxPolicy.observe(plan, changed, 44));
        changed.remove(37);
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.IN_FLIGHT,
                KitHeldBorrowedBoxPolicy.observe(plan, changed, 44));
    }

    @Test void scheduledWitnessSurvivesRestartAndCannotDisappearBeforeExactPack()
            throws IOException {
        var original = blocked();
        var plan = KitHeldBorrowedBoxPolicy.inspect(original, combined(), 44, 33).plan();
        var held = new KitFulfillmentJournalStore.HeldBoxRebase(
                plan.journaledSourceUnits(), plan.combinedSourceUnits(),
                plan.retrievedUnits(), plan.keptUnits(), plan.sourceItemId(),
                plan.itemId(), plan.variantHash());
        var combinedSource = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 130, 2}, "black_shulker_box", Map.of("obsidian", 1439));
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                original.recipe(), Map.of(), 25, 24, "obsidian", 95, 44,
                original.exportPosition(), null, null, null, Map.of(),
                "resume_held_box_rebase", 2L, "NEED_CARGO", 3L,
                List.of(combinedSource), -1, List.of(), Map.of())
                .withHeldBoxRebase(held);
        var store = new KitFulfillmentJournalStore(temporary.resolve("held.json"));
        store.save(scheduled);
        var loaded = store.load().orElseThrow();
        assertEquals(held, loaded.heldBoxRebase());
        assertTrue(KitHeldBorrowedBoxPolicy.verifiedScheduled(
                loaded, combined(), 44, -1));
        assertThrows(IOException.class, () -> store.save(scheduled.withHeldBoxRebase(null)));

        var returned = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 130, 2}, "black_shulker_box", Map.of("obsidian", 1344));
        var recovered = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "WAIT_CARGO",
                original.recipe(), Map.of(), 25, 24, "obsidian", 95, 44,
                original.exportPosition(), null, null, null, Map.of(), null,
                4L, null, 0L, List.of(returned), -1, List.of(), Map.of())
                .withHeldBoxRebase(held);
        store.save(recovered);
        assertThrows(IOException.class, () -> store.save(recovered.withHeldBoxRebase(null)));
        var packed = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "RETURNING_SOURCE",
                original.recipe(), Map.of("obsidian", 95), 25, 24, "obsidian", 95, 44,
                original.exportPosition(), null, null, null, Map.of(), null,
                5L, null, 0L, List.of(returned), -1, List.of(), Map.of());
        store.save(packed);
        assertEquals(null, store.load().orElseThrow().heldBoxRebase());

        Map<Integer, ItemStack> observed = new HashMap<>(combined());
        observed.put(37, sourceBox(1344));
        observed.put(13, obsidian(64));
        observed.put(14, obsidian(31));
        assertEquals(KitHeldBorrowedBoxPolicy.Phase.TASK_EXTRACTED,
                KitHeldBorrowedBoxPolicy.observe(plan, observed, 44));
    }
}
