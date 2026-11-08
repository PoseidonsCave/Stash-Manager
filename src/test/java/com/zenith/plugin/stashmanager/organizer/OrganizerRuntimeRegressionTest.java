package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.*;

class OrganizerRuntimeRegressionTest {
    @TempDir Path temp;
    private int previousOpen;
    private Container previousContainer;
    private Container window;
    private StashOrganizer organizer;
    private final Map<String, Map<String, Object>> events = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        previousOpen = cache.getOpenContainerId();
        window = new Container(73, 90);
        previousContainer = cache.getContainers().put(73, window);
        set(cache, "openContainerId", 73);
        organizer = new StashOrganizer(new StashManagerConfig(), new ContainerIndex(),
                new OrganizerJournalStore(temp.resolve("plan.json"), temp.resolve("checkpoint.json")));
        organizer.setEventCallback(events::put);
        set(organizer, "state", StashOrganizer.State.SHULKER_FETCH_TAKE);
        set(organizer, "openContainerId", 73);
        set(organizer, "packItemId", "diamond_boots");
        window.setItemStack(51, InventoryTransferEvidenceTest.box("diamond_boots"));
        var cls = Class.forName(StashOrganizer.class.getName() + "$PendingQuickMove");
        var constructor = cls.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var pending = constructor.newInstance(73, 51, window.getItemStack(51),
                InventoryTransferEvidence.capture(window, 54, 51), RequestFuture.immediateFuture(true));
        set(organizer, "pendingQuickMove", pending);
    }

    @AfterEach
    void cleanup() throws Exception {
        invoke("clearOwnedAutomation");
        var cache = CACHE.getPlayerCache().getInventoryCache();
        if (previousContainer == null) cache.getContainers().remove(73);
        else cache.getContainers().put(73, previousContainer);
        set(cache, "openContainerId", previousOpen);
    }

    @Test
    void fetchAdvancesExactlyOnceWithRefilledSourceAndConfirmedReceipt() throws Exception {
        window.setItemStack(89, InventoryTransferEvidenceTest.box("diamond_boots"));
        invoke("tickShulkerFetchTake");
        assertEquals(StashOrganizer.State.SHULKER_FETCH_CLOSING, organizer.getState());
        assertNull(get(organizer, "pendingQuickMove"));
        assertEquals(true, get(organizer, "fetchedPackingShulker"));
        assertTrue(events.containsKey("organize_transfer_confirmed"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void waitsForDelayedReceiptEvenIfSourceIsAlreadyEmpty() throws Exception {
        window.setItemStack(51, null);
        invoke("tickShulkerFetchTake");
        assertEquals(StashOrganizer.State.SHULKER_FETCH_TAKE, organizer.getState());
        assertNotNull(get(organizer, "pendingQuickMove"));
        window.setItemStack(89, InventoryTransferEvidenceTest.box("diamond_boots"));
        invoke("tickShulkerFetchTake");
        assertEquals(StashOrganizer.State.SHULKER_FETCH_CLOSING, organizer.getState());
    }

    @Test
    void fetchedPackingBoxReturnsToWorksiteBeforePlacement() throws Exception {
        int[] station = {4, 70, 9};
        set(organizer, "state", StashOrganizer.State.SHULKER_FETCH_CLOSING);
        set(organizer, "fetchedPackingShulker", true);
        set(organizer, "reconciliationStation", station);
        set(organizer, "actionCooldown", 5);

        invoke("tickShulkerFetchClosing");

        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertArrayEquals(station, (int[]) get(organizer, "walkTarget"));
        assertNotSame(station, get(organizer, "walkTarget"));
    }

    @Test
    void noMovementStillExhaustsRetriesAndFailsInsteadOfReportingDone() throws Exception {
        StashManagerConfig config = (StashManagerConfig) get(organizer, "config");
        config.pos1 = new int[]{0, 0, 0};
        config.pos2 = new int[]{10, 100, 10};
        set(organizer, "journalJobId", "failed-fetch");
        set(organizer, "journalPlanDirty", true);
        Object pending = get(organizer, "pendingQuickMove");
        set(pending, "verificationTicks", 100);
        set(organizer, "quickMoveFailureKey", "73:51:SHULKER_FETCH_TAKE");
        set(organizer, "quickMoveFailureAttempts", 2);
        invoke("tickShulkerFetchTake");
        assertEquals(StashOrganizer.State.FAILED, organizer.getState());
        assertFalse(organizer.isActive());
        assertTrue(organizer.isFailed());
        assertEquals("SHULKER_FETCH_TAKE", organizer.getLastFailureState());
        assertEquals("inventory_transfer_unavailable_with_cargo", organizer.getLastFailureReason());
        assertTrue(organizer.getStatus().startsWith("Needs attention:"));
        assertEquals(0, events.get("organize_target_failed").get("receiver_units_now"));
        assertEquals(0, organizer.getCompletedTasks());
        var saved = new OrganizerJournalStore(temp.resolve("plan.json"), temp.resolve("checkpoint.json"))
                .load().orElseThrow().checkpoint();
        assertEquals("SHULKER_FETCH_TAKE", saved.interruptedState());
        assertEquals("inventory_transfer_unavailable_with_cargo", saved.lastFailure().reason());
        assertTrue(organizer.hasDurableCheckpoint());
    }

    @Test
    void hopperDrainDoesNotLeavePhantomDepositCargo() throws Exception {
        CargoTransactionLedger ledger = prepareDeposit(28);
        window.setItemStack(89, null);
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 27));

        invoke("tickDepositing");
        assertEquals(28, ledger.deposited());
        assertEquals(0, ledger.remaining());
        assertEquals("exact_stack_departed", events.get("organize_transfer_confirmed").get("evidence"));
        assertEquals(28, events.get("organize_transfer_confirmed").get("requested_amount"));
        assertEquals(27, events.get("organize_transfer_confirmed").get("receiver_observed_amount"));
        invoke("tickDepositing");
        assertEquals(StashOrganizer.State.CLOSING_DEST, organizer.getState());
        assertFalse(events.containsKey("organize_failed"));
    }

    @Test
    void depositWaitsForPlayerUpdateEvenWhenChestAlreadyGrew() throws Exception {
        CargoTransactionLedger ledger = prepareDeposit(28);
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 27));

        invoke("tickDepositing");
        assertEquals(0, ledger.deposited());
        assertNotNull(get(organizer, "pendingQuickMove"));
        window.setItemStack(89, null);
        invoke("tickDepositing");
        assertEquals(28, ledger.deposited());
    }

    @Test
    void partialDepositAndLaterRemainderAreCountedOnce() throws Exception {
        CargoTransactionLedger ledger = prepareDeposit(64);
        window.setItemStack(89, InventoryTransferEvidenceTest.item("obsidian", 36));
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 27));
        invoke("tickDepositing");
        assertEquals(28, ledger.deposited());
        assertEquals(36, ledger.remaining());

        installPendingMove(89);
        window.setItemStack(89, null);
        window.setItemStack(0, null); // Hoppers have drained the destination completely.
        invoke("tickDepositing");
        assertEquals(64, ledger.deposited());
        assertNull(get(organizer, "pendingQuickMove"));
        invoke("tickDepositing");
        assertEquals(64, ledger.deposited());
        assertEquals(StashOrganizer.State.CLOSING_DEST, organizer.getState());
    }

    @Test
    void movingStackBetweenPlayerSlotsIsNotADeposit() throws Exception {
        CargoTransactionLedger ledger = prepareDeposit(28);
        window.setItemStack(89, null);
        window.setItemStack(54, InventoryTransferEvidenceTest.item("obsidian", 28));
        invoke("tickDepositing");
        assertEquals(0, ledger.deposited());
        assertNotNull(get(organizer, "pendingQuickMove"));
    }

    @Test
    void destinationGrowthAloneCannotBypassDepositRetryLimit() throws Exception {
        CargoTransactionLedger ledger = prepareDeposit(28);
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 27));
        set(get(organizer, "pendingQuickMove"), "verificationTicks", 100);
        set(organizer, "quickMoveFailureKey", "73:89:DEPOSITING");
        set(organizer, "quickMoveFailureAttempts", 2);
        invoke("tickDepositing");
        assertEquals(StashOrganizer.State.FAILED, organizer.getState());
        assertEquals(0, ledger.deposited());
        assertEquals(28, events.get("organize_failed").get("task_cargo_remaining"));
        assertEquals(28, events.get("organize_target_failed").get("player_units_now"));
    }

    private CargoTransactionLedger prepareDeposit(int amount) throws Exception {
        set(organizer, "state", StashOrganizer.State.DEPOSITING);
        set(organizer, "actionSlotIndex", 44);
        ((StashManagerConfig) get(organizer, "config")).organizerClickCooldownTicks = 0;
        set(organizer, "currentTask", new StashOrganizer.MoveTask(
                new int[]{0, 64, 0}, new int[]{2, 64, 0}, "obsidian", null, true));
        window.setItemStack(89, InventoryTransferEvidenceTest.item("obsidian", amount));
        installPendingMove(89);
        var ledger = (CargoTransactionLedger) get(organizer, "taskCargo");
        ledger.reset(amount);
        return ledger;
    }

    private void installPendingMove(int slot) throws Exception {
        var cls = Class.forName(StashOrganizer.class.getName() + "$PendingQuickMove");
        var constructor = cls.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        set(organizer, "pendingQuickMove", constructor.newInstance(73, slot, window.getItemStack(slot),
                InventoryTransferEvidence.capture(window, 54, slot), RequestFuture.immediateFuture(true)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedStagingPartialAndSplitDepositsGenerateOnlyActualPairs() throws Exception {
        prepareDeposit(64);
        set(organizer, "state", StashOrganizer.State.MIXED_STAGE_DEPOSIT);
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        var slots = (Set<Integer>) get(organizer, "mixedCargoSlots");
        slots.add(44);
        int[] a = {1, 64, 0}, b = {2, 64, 0}, lane = {8, 70, 0};
        set(organizer, "packDestination", a);
        ledger.begin("obsidian", a);
        window.setItemStack(89, InventoryTransferEvidenceTest.item("obsidian", 36));
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 27));
        invoke("tickMixedStageDeposit");
        assertEquals(1, ledger.snapshot().confirmed().size());
        assertTrue(slots.contains(44));

        set(organizer, "packDestination", b);
        ledger.begin("obsidian", b);
        installPendingMove(89);
        window.setItemStack(89, null);
        invoke("tickMixedStageDeposit");
        assertTrue(slots.isEmpty());
        assertEquals(2, ledger.snapshot().confirmed().size());
        assertTrue(ledger.snapshot().uncertain().isEmpty());

        set(organizer, "columnAssignment", Map.of("obsidian", new StashOrganizer.Column(1, List.of(lane))));
        var method = StashOrganizer.class.getDeclaredMethod("stagedMixedBatchTasks", java.util.Collection.class, int[].class);
        method.setAccessible(true);
        var tasks = (List<StashOrganizer.MoveTask>) method.invoke(organizer, List.of("obsidian", "end_crystal"), a);
        assertEquals(2, tasks.size());
        assertTrue(tasks.stream().allMatch(task -> task.itemId().equals("obsidian")));
        for (var task : tasks) assertArrayEquals(lane, task.destination());
    }

    @Test
    void shulkerSearchChoosesKnownEmptyBeforeIncompatibleStock() throws Exception {
        ContainerIndex index = (ContainerIndex) get(organizer, "index");
        var full = new ContainerEntry(1, 64, 0, "chest", false, Map.of("shulker_box", 1), 1,
                List.of(new ContainerEntry.ShulkerDetail(0, "white", Map.of("stone", 1728))), 1);
        var empty = new ContainerEntry(20, 64, 0, "chest", false, Map.of("shulker_box", 1), 1,
                List.of(new ContainerEntry.ShulkerDetail(0, "white", Map.of())), 1);
        index.put(full); index.put(empty);
        StashManagerConfig config = (StashManagerConfig) get(organizer, "config");
        config.pos1 = new int[]{0, 0, 0}; config.pos2 = new int[]{30, 100, 10};
        @SuppressWarnings("unchecked")
        var managed = (Set<Long>) get(organizer, "managedSourceContainerKeys");
        managed.add(full.posKey()); managed.add(empty.posKey());
        invoke("startFetchShulker");
        assertArrayEquals(new int[]{20, 64, 0}, (int[]) get(organizer, "walkTarget"));
        assertEquals("known_empty", events.get("organize_shulker_source_selected").get("candidate_kind"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedStagingIntentIsDurableBeforeAnyTransferConfirmation() throws Exception {
        prepareDeposit(28);
        set(organizer, "pendingQuickMove", null);
        set(organizer, "state", StashOrganizer.State.MIXED_STAGE_DEPOSIT);
        set(organizer, "packDestination", new int[]{1, 64, 0});
        ((Set<Integer>) get(organizer, "mixedCargoSlots")).add(44);
        var config = (StashManagerConfig) get(organizer, "config");
        config.pos1 = new int[]{0, 0, 0}; config.pos2 = new int[]{30, 100, 10};
        set(organizer, "journalJobId", "staging-intent");
        set(organizer, "journalPlanDirty", true);
        invoke("tickMixedStageDeposit");
        var snapshot = new OrganizerJournalStore(temp.resolve("plan.json"), temp.resolve("checkpoint.json"))
                .load().orElseThrow().checkpoint().mixedStagingLedger();
        assertTrue(snapshot.confirmed().isEmpty());
        assertEquals(List.of(new MixedStagingLedger.Source("obsidian", 1, 64, 0)), snapshot.uncertain());
    }

    @Test
    void incompatibleLiveStockIsNeverTakenOnTheStrengthOfTheIndex() throws Exception {
        set(organizer, "pendingQuickMove", null);
        set(organizer, "packItemId", "end_crystal");
        invoke("tickShulkerFetchTake");
        assertEquals(StashOrganizer.State.SHULKER_FETCH_CLOSING, organizer.getState());
        assertNull(get(organizer, "pendingQuickMove"));
        assertFalse((boolean) get(organizer, "fetchedPackingShulker"));
        assertTrue(events.containsKey("organize_shulker_source_miss"));
    }

    @Test
    void recordsTheOpenedChestEvenAfterWalkTargetChanges() throws Exception {
        ContainerIndex index = (ContainerIndex) get(organizer, "index");
        index.put(new ContainerEntry(1, 64, 0, "minecraft:chest", true,
                Map.of("stone", 1), 0, List.of(), 1));
        set(organizer, "openContainerId", -1);
        set(organizer, "state", StashOrganizer.State.SHULKER_FETCH_OPEN);
        set(organizer, "walkTarget", new int[]{1, 64, 0});
        organizer.onContainerData(null, new ClientboundContainerSetContentPacket(73, 1, new ItemStack[90], null));
        set(organizer, "walkTarget", new int[]{9, 64, 9});
        invoke("recordLiveContainerObservation");
        assertEquals(1, index.get(1, 64, 0).shulkerCount());
        assertEquals(1, index.get(1, 64, 0).items().get("diamond_boots"));
        assertNull(index.get(9, 64, 9));
    }

    @Test
    void temporaryShulkerWindowNeverOverwritesAnIndexedWorksiteChest() throws Exception {
        ContainerIndex index = (ContainerIndex) get(organizer, "index");
        index.put(new ContainerEntry(1, 64, 0, "minecraft:chest", true,
                Map.of("stone", 1), 0, List.of(), 1));
        set(organizer, "openContainerId", -1);
        set(organizer, "state", StashOrganizer.State.SHULKER_OPENING);
        set(organizer, "walkTarget", new int[]{1, 64, 0});
        organizer.onContainerData(null, new ClientboundContainerSetContentPacket(73, 1, new ItemStack[90], null));
        invoke("recordLiveContainerObservation");
        assertEquals(Map.of("stone", 1), index.get(1, 64, 0).items());
        assertEquals(0, index.getLastInventoryObservation());
    }

    @Test
    void unrelatedWindowCannotOverwriteAnObservation() throws Exception {
        ContainerIndex index = (ContainerIndex) get(organizer, "index");
        var entry = new ContainerEntry(1, 64, 0, "minecraft:chest", true,
                Map.of("stone", 1), 0, List.of(), 1);
        index.put(entry);
        set(organizer, "openIndexedContainer", entry);
        set(organizer, "openContainerId", 74);
        invoke("recordLiveContainerObservation");
        assertEquals(Map.of("stone", 1), index.get(1, 64, 0).items());
        assertEquals(0, index.getLastInventoryObservation());
    }

    private void invoke(String name) throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(organizer);
    }

    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
