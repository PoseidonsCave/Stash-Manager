package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitPartialBorrowedCargoGuardTest {
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
    private static ItemStack sourceBox(int first) {
        List<Optional<ItemStack>> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(Optional.empty());
        slots.set(0, Optional.of(obsidian(first)));
        for (int i = 1; i <= 20; i++) slots.set(i, Optional.of(obsidian(64)));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get("black_shulker_box").id(),
                1, components);
    }

    private static Map<Integer, ItemStack> inventory() {
        return Map.of(13, obsidian(31), 14, obsidian(64),
                35, obsidian(33), 37, sourceBox(31),
                44, new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1));
    }

    private static KitPartialBorrowedCargoGuard.Result inspect(
            Map<Integer, ItemStack> inventory, int kept) {
        return KitPartialBorrowedCargoGuard.inspect(blocked(), inventory,
                44, 37, 35, kept, Map.of(13, 31, 14, 64));
    }

    @Test void exactPartialCargoAndKeptStackHaveDurableWitness() throws IOException {
        var accepted = inspect(inventory(), 33);
        assertTrue(accepted.accepted(), accepted.blocker());
        assertEquals(95, KitPartialBorrowedCargoGuard.observedUnits(
                blocked().blocker(), blocked().currentQuantity()));
        var original = blocked();
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                original.recipe(), Map.of(), 25, 24, "obsidian", 95, 44,
                original.exportPosition(), null, null, null, Map.of(),
                "resume_audited_inventory_cargo", 2L, "WAIT_CARGO", 3L,
                original.sourceBoxesToReturn(), -1, List.of(), Map.of(13, 31, 14, 64))
                .withProtectedLoose(accepted.witness());
        var store = new KitFulfillmentJournalStore(temporary.resolve("split.json"));
        store.save(scheduled);
        var loaded = store.load().orElseThrow();
        assertNotNull(loaded.protectedLoose());
        assertEquals(33, loaded.protectedLoose().amount());
        assertEquals(128, loaded.protectedLoose().originalQuantity());
        assertTrue(KitPartialBorrowedCargoGuard.verifiedScheduled(loaded, inventory()));
        Map<Integer, ItemStack> changed = new HashMap<>(inventory());
        changed.put(35, obsidian(32));
        assertFalse(KitPartialBorrowedCargoGuard.verifiedScheduled(loaded, changed));
        assertThrows(IOException.class, () -> store.save(scheduled.withProtectedLoose(null)));
        var packed = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "RETURNING_SOURCE",
                original.recipe(), Map.of("obsidian", 95), 25, 24, "obsidian", 95, 44,
                original.exportPosition(), null, null, null, Map.of(), null,
                4L, null, 0L, original.sourceBoxesToReturn(), -1, List.of(), Map.of());
        store.save(packed);
        assertEquals(null, store.load().orElseThrow().protectedLoose());
    }

    @Test void wrongSourceOrKeptOrExtraLooseStackRejectsWithoutWitness() {
        assertFalse(inspect(inventory(), 32).accepted());
        Map<Integer, ItemStack> wrongSource = new HashMap<>(inventory());
        wrongSource.put(37, sourceBox(32));
        assertFalse(inspect(wrongSource, 33).accepted());
        Map<Integer, ItemStack> extra = new HashMap<>(inventory());
        extra.put(20, obsidian(1));
        assertFalse(inspect(extra, 33).accepted());
        assertEquals(-1, KitPartialBorrowedCargoGuard.observedUnits(
                "kit_retrieved_cargo_unverified:128/128", 128));
    }
}
