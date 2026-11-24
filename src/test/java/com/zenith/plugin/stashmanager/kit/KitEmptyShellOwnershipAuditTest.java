package com.zenith.plugin.stashmanager.kit;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitEmptyShellOwnershipAuditTest {
    private static KitFulfillmentJournalStore.Snapshot checkpoint(
            String blocker, String resumeState,
            List<KitFulfillmentJournalStore.BorrowedBox> sources) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("totem_of_undying", 6), Map.of(), 25, 14,
                "totem_of_undying", 6, 42, new int[]{1, 130, 2},
                null, null, null, Map.of(), blocker, 100L, resumeState,
                resumeState == null ? 0L : 200L, sources, -1, List.of(),
                resumeState == null ? Map.of() : Map.of(13, 1, 14, 1, 26, 1,
                        27, 1, 28, 1, 33, 1));
    }

    private static KitFulfillmentJournalStore.BorrowedBox source(
            Map<String, Integer> contents) {
        return new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 130, 4}, "shulker_box", contents);
    }

    @Test void oneOrTwoPendingSourcesCanBeAuditedOnlyWithOneEmptySource() {
        var pending = List.of(source(Map.of()), source(Map.of("totem_of_undying", 25)));
        assertTrue(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null, pending)));
        assertTrue(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null, List.of(source(Map.of())))));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null,
                List.of(source(Map.of()), source(Map.of())))));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null,
                List.of(source(Map.of("totem_of_undying", 25))))));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", "WAIT_CARGO", pending)));
    }

    @Test void scheduledOwnershipAuditIsEligibleOnlyAtItsCargoBoundary() {
        var pending = List.of(source(Map.of()), source(Map.of("totem_of_undying", 25)));
        String marker = "resume_audited_empty_shell_ownership:42:44:"
                + "a".repeat(64) + ":" + "b".repeat(64);
        var scheduled = checkpoint(marker, "WAIT_CARGO", pending);
        assertTrue(KitEmptyShellOwnershipAudit.isScheduled(marker));
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(scheduled));
        assertFalse(KitEmptyShellOwnershipAudit.isScheduled("kit_owned_shell_identity_ambiguous"));
    }

    @Test void coloredEmptySourceAndOwnedShellHaveDistinctAuditableFingerprints() {
        var gray = new ItemStack(ItemRegistry.REGISTRY.get("gray_shulker_box").id(), 1);
        var orange = new ItemStack(ItemRegistry.REGISTRY.get("orange_shulker_box").id(), 1);
        assertNotNull(KitEmptyShellOwnershipAudit.marker(44, 36, gray, orange));
        assertNotNull(KitEmptyShellOwnershipAudit.marker(44, 36, gray, gray));
    }

    @Test void identicalPlainEmptyPairRequiresDistinctPinnedSlots() {
        var plain = new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1);
        assertNotNull(KitEmptyShellOwnershipAudit.marker(44, 36, plain, plain));
        assertNull(KitEmptyShellOwnershipAudit.marker(36, 36, plain, plain));
    }

    @Test void namedOwnedShellAndPlainBorrowedShellRetainPinnedIdentity() {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("Travel shell"));
        var named = new ItemStack(ItemRegistry.REGISTRY.get("red_shulker_box").id(), 1,
                components);
        var plain = new ItemStack(ItemRegistry.REGISTRY.get("red_shulker_box").id(), 1);
        String marker = KitEmptyShellOwnershipAudit.marker(44, 36, named, plain);
        assertNotNull(marker);
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "BLOCKED",
                Map.of("totem_of_undying", 9), Map.of(), 54, 43,
                "totem_of_undying", 9, 44, new int[]{1, 130, 2},
                null, null, null, Map.of(), marker, 100L, "WAIT_CARGO",
                200L, List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{3, 130, 4}, "red_shulker_box", Map.of())),
                -1, List.of(), Map.of(9, 1, 11, 1));
        var live = new Container(0, 46);
        live.setItemStack(44, named);
        live.setItemStack(36, plain);
        assertTrue(KitEmptyShellOwnershipAudit.verify(scheduled, live));
        live.setItemStack(44, plain);
        live.setItemStack(36, named);
        assertFalse(KitEmptyShellOwnershipAudit.verify(scheduled, live));
    }

    @Test void scheduledSingleSourceAuditRequiresExactlyTwoPinnedEmptyBoxes() {
        var plain = new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1);
        var source = List.of(source(Map.of()));
        String marker = KitEmptyShellOwnershipAudit.marker(44, 36, plain, plain);
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "BLOCKED",
                Map.of("experience_bottle", 576), Map.of(), 54, 36,
                "experience_bottle", 576, 44, new int[]{1, 130, 2},
                null, null, null, Map.of(), marker, 100L, "WAIT_CARGO",
                200L, source, -1, List.of(), Map.of(9, 64, 11, 64));
        var live = new Container(0, 46);
        live.setItemStack(36, plain);
        live.setItemStack(44, plain);
        assertTrue(KitEmptyShellOwnershipAudit.verify(scheduled, live));
        live.setItemStack(40, plain);
        assertFalse(KitEmptyShellOwnershipAudit.verify(scheduled, live));
        live.setItemStack(40, null);
        live.setItemStack(36, null);
        assertFalse(KitEmptyShellOwnershipAudit.verify(scheduled, live));
    }

    @Test void mixedCargoExceptionIsLimitedToTravelConsumables() {
        var pending = List.of(source(Map.of()), source(Map.of("experience_bottle", 1408)));
        var travel = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "BLOCKED",
                Map.of("experience_bottle", 576), Map.of(), 54, 0,
                "experience_bottle", 576, 44, new int[]{1, 130, 2},
                null, null, null, Map.of(), "kit_owned_shell_identity_ambiguous",
                100L, null, 0L, pending, -1, List.of(), Map.of());
        assertTrue(KitEmptyShellOwnershipAudit.acceptsMixedCargoVariants(travel));
        var travelTotem = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "BLOCKED",
                Map.of("totem_of_undying", 9), Map.of(), 54, 43,
                "totem_of_undying", 9, 36, new int[]{1, 130, 2},
                null, null, null, Map.of(), "kit_owned_shell_identity_ambiguous",
                100L, null, 0L,
                List.of(source(Map.of()), source(Map.of("totem_of_undying", 18))),
                -1, List.of(), Map.of());
        assertTrue(KitEmptyShellOwnershipAudit.acceptsMixedCargoVariants(travelTotem));
        assertFalse(KitEmptyShellOwnershipAudit.acceptsMixedCargoVariants(
                checkpoint("kit_owned_shell_identity_ambiguous", null, pending)));
    }
}
