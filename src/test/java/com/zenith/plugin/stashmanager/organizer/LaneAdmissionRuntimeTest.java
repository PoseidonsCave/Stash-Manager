package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Deque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.*;

class LaneAdmissionRuntimeTest {
    @TempDir Path temp;
    private final int[] lime = {0, 70, 0};
    private final int[] cobble = {4, 70, 0};
    private final int[] imports = {8, 70, 0};
    private StashOrganizer organizer;
    private StashManagerConfig config;
    private OrganizerJournalStore journal;
    private ContainerIndex index;
    private Container previousPlayer;
    private Container previousWindow;
    private int previousOpen;
    private Container window;
    private final Map<String, Map<String, Object>> events = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        previousOpen = cache.getOpenContainerId();
        previousPlayer = cache.getContainers().put(0, new Container(0, 46));
        window = new Container(73, 90);
        previousWindow = cache.getContainers().put(73, window);
        set(cache, "openContainerId", 0);
        config = new StashManagerConfig();
        config.pos1 = new int[]{-100, 0, -100};
        config.pos2 = new int[]{100, 100, 100};
        journal = new OrganizerJournalStore(temp.resolve("plan.json"), temp.resolve("checkpoint.json"));
        index = new ContainerIndex();
        organizer = new StashOrganizer(config, index, journal);
        organizer.setEventCallback(events::put);
        set(organizer, "journalJobId", "lane-admission-test");
        set(organizer, "journalPlanDirty", true);
        set(organizer, "totalTasks", 3);
        set(organizer, "columnAssignment", Map.of(
                "lime_wool", new StashOrganizer.Column(0, List.of(lime)),
                "cobblestone", new StashOrganizer.Column(1, List.of(cobble))));
    }

    @AfterEach
    void cleanup() throws Exception {
        invoke("clearOwnedAutomation");
        var cache = CACHE.getPlayerCache().getInventoryCache();
        if (previousPlayer == null) cache.getContainers().remove(0);
        else cache.getContainers().put(0, previousPlayer);
        if (previousWindow == null) cache.getContainers().remove(73);
        else cache.getContainers().put(73, previousWindow);
        set(cache, "openContainerId", previousOpen);
    }

    @Test
    void selectsClearanceBeforeDeliveryWithoutDroppingEqualPhysicalTasks() throws Exception {
        var incoming = box(imports, lime, "lime_wool");
        var clearA = box(lime, cobble, "cobblestone");
        var clearB = box(lime, cobble, "cobblestone");
        queue().addAll(List.of(incoming, clearA, clearB));
        invoke("advanceToNextTask");
        assertSame(clearA, get("currentTask"));
        assertEquals(List.of(incoming, clearB), List.copyOf(queue()));
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertArrayEquals(cobble, (int[]) get("walkTarget"));
        assertNull(get("pendingQuickMove"));
        assertEquals(0, organizer.getCompletedTasks());
        assertEquals("LANE_PREFLIGHT", journal.load().orElseThrow().checkpoint().currentRole());
    }

    @Test
    void liveSpaceReturnsToSourceBeforeAnyTransferAndReservesOneSlot() throws Exception {
        queue().add(box(imports, lime, "lime_wool"));
        invoke("advanceToNextTask");
        for (int slot = 0; slot < 53; slot++) window.setItemStack(slot, InventoryTransferEvidenceTest.box("lime_wool"));
        openProbe();
        invoke("tickOpening");
        assertEquals("SOURCE", get("currentRole").toString());
        assertArrayEquals(imports, (int[]) get("walkTarget"));
        assertNull(get("pendingQuickMove"));
        assertEquals(0, ((CargoTransactionLedger) get("taskCargo")).acquired());
        assertSame(get("currentTask"), get("laneAdmittedTask"));
        assertEquals(0, ((LaneAdmissionCache) get("laneAdmissionCache")).reserve(key(lime), System.currentTimeMillis()));
    }

    @Test
    void fullLaneLeavesCargoAtSourceAndDoesNotReprobeForEverySameClassBox() throws Exception {
        var first = box(imports, lime, "lime_wool");
        var second = box(imports, lime, "lime_wool");
        var independent = box(imports, cobble, "cobblestone");
        queue().addAll(List.of(first, second, independent));
        invoke("advanceToNextTask");
        for (int slot = 0; slot < 54; slot++) window.setItemStack(slot, InventoryTransferEvidenceTest.box("lime_wool"));
        openProbe();
        invoke("tickOpening");
        assertSame(independent, get("currentTask"));
        assertTrue(queue().contains(first));
        assertTrue(queue().contains(second));
        assertEquals("lane_full_before_take", events.get("organize_lane_admission_deferred").get("reason"));
        assertNull(get("pendingQuickMove"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void checksLowerChestAndKeepsCascadeOffset() throws Exception {
        int[] lower = {0, 69, 1};
        set(organizer, "columnAssignment", Map.of("lime_wool", new StashOrganizer.Column(0, List.of(lime, lower))));
        queue().add(box(imports, lime, "lime_wool"));
        invoke("advanceToNextTask");
        for (int slot = 0; slot < 54; slot++) window.setItemStack(slot, InventoryTransferEvidenceTest.box("lime_wool"));
        openProbe();
        invoke("tickOpening");
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertArrayEquals(lower, (int[]) get("walkTarget"));
        window.setItemStack(0, null);
        openProbe();
        invoke("tickOpening");
        assertArrayEquals(lower, ((StashOrganizer.MoveTask) get("currentTask")).destination());
        invoke("transitionToDestination");
        assertEquals(1, get("depositColumnIndex"));
    }

    @Test
    void staleReservationChecksAgainInsteadOfTaking() throws Exception {
        queue().add(box(imports, lime, "lime_wool"));
        invoke("advanceToNextTask");
        openProbe();
        invoke("tickOpening");
        set(organizer, "laneAdmissionExpiresAt", 1L);
        ((LaneAdmissionCache) get("laneAdmissionCache")).reset();
        window.setItemStack(0, InventoryTransferEvidenceTest.box("lime_wool"));
        openProbe();
        set(organizer, "state", StashOrganizer.State.TAKING);
        set(organizer, "actionCooldown", 0);
        invoke("tickTaking");
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertNotNull(window.getItemStack(0));
        assertNull(get("pendingQuickMove"));
    }

    @Test
    void dependencyCycleStopsWithBothTasksSavedAndNoCargoTaken() throws Exception {
        queue().add(box(cobble, lime, "lime_wool"));
        queue().add(box(lime, cobble, "cobblestone"));
        invoke("advanceToNextTask");
        assertEquals(StashOrganizer.State.FAILED, organizer.getState());
        assertEquals("lane_clearance_blocked", organizer.getLastFailureReason());
        assertEquals(0, organizer.getCompletedTasks());
        var checkpoint = journal.load().orElseThrow().checkpoint();
        assertNotNull(checkpoint.currentTaskId());
        assertEquals(1, checkpoint.taskQueue().size());
        assertEquals(0, checkpoint.taskCargoAcquired());
    }

    @Test
    void dependencyCycleStagesOneBoxOnlyAfterLiveImportSpaceCheck() throws Exception {
        registerImport();
        queue().add(box(cobble, lime, "lime_wool"));
        queue().add(box(lime, cobble, "cobblestone"));
        invoke("advanceToNextTask");
        var stage = (StashOrganizer.MoveTask) get("currentTask");
        assertArrayEquals(cobble, stage.source());
        assertArrayEquals(imports, stage.destination());
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertArrayEquals(imports, (int[]) get("walkTarget"));
        assertEquals(4, get("totalTasks"));
        assertTrue(queue().stream().anyMatch(task ->
                java.util.Arrays.equals(imports, task.source())
                        && java.util.Arrays.equals(lime, task.destination())));
        assertEquals(3, journal.load().orElseThrow().plan().tasks().size());
        openProbe();
        invoke("tickOpening");
        assertEquals("SOURCE", get("currentRole").toString());
        assertArrayEquals(cobble, (int[]) get("walkTarget"));
        assertSame(get("currentTask"), get("laneAdmittedTask"));
        assertEquals(0, ((CargoTransactionLedger) get("taskCargo")).acquired());
    }

    @Test
    void fullImportRestoresOriginalCycleWithoutTakingCargo() throws Exception {
        registerImport();
        queue().add(box(cobble, lime, "lime_wool"));
        queue().add(box(lime, cobble, "cobblestone"));
        invoke("advanceToNextTask");
        for (int slot = 0; slot < 54; slot++)
            window.setItemStack(slot, InventoryTransferEvidenceTest.box("lime_wool"));
        openProbe();
        invoke("tickOpening");
        assertEquals(StashOrganizer.State.FAILED, organizer.getState());
        assertEquals("lane_cycle_staging_unavailable", organizer.getLastFailureReason());
        assertEquals(3, get("totalTasks"));
        assertEquals(2, queue().size());
        assertTrue(queue().stream().anyMatch(task ->
                java.util.Arrays.equals(cobble, task.source())
                        && java.util.Arrays.equals(lime, task.destination())));
        assertEquals(0, ((CargoTransactionLedger) get("taskCargo")).acquired());
    }

    @Test
    void stagedCycleCheckpointRechecksImportBeforePickupAfterRestart() throws Exception {
        registerImport();
        queue().add(box(cobble, lime, "lime_wool"));
        queue().add(box(lime, cobble, "cobblestone"));
        invoke("advanceToNextTask");
        invoke("clearOwnedAutomation");
        organizer = new StashOrganizer(config, index, journal);
        assertNotEquals(StashOrganizer.DurableRestoreResult.INVALID, organizer.restoreDurableCheckpoint());
        invoke("resumeInterruptedCheckpoint", StashOrganizer.State.WALKING);
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertArrayEquals(imports, (int[]) get("walkTarget"));
        assertEquals(0, ((CargoTransactionLedger) get("taskCargo")).acquired());
        assertEquals(1, queue().stream().filter(task ->
                java.util.Arrays.equals(imports, task.source())
                        && java.util.Arrays.equals(lime, task.destination())).count());
    }

    private void registerImport() throws Exception {
        index.put(new ContainerEntry(imports[0], imports[1], imports[2],
                "minecraft:chest", false, Map.of(), 0, List.of(), 1,
                null, null, imports[0], imports[1], imports[2], true, null));
        var method = ContainerIndex.class.getDeclaredMethod(
                "cacheImportChests", Collection.class, boolean.class);
        method.setAccessible(true);
        method.invoke(index, List.of(imports), true);
    }

    @Test
    void heldCargoKeepsPriorityOverLaneDependencies() throws Exception {
        var held = box(imports, lime, "lime_wool").markAlreadyInInventory();
        queue().add(box(lime, cobble, "cobblestone"));
        queue().add(held);
        CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().setItemStack(9,
                InventoryTransferEvidenceTest.box("lime_wool"));
        invoke("advanceToNextTask");
        assertSame(held, get("currentTask"));
        assertEquals("DESTINATION", get("currentRole").toString());
        assertNull(get("laneAdmittedTask"));
    }

    @Test
    void failedClearanceCannotBecomePermissionToFillTheLane() throws Exception {
        var incoming = box(imports, lime, "lime_wool");
        var clearance = box(lime, cobble, "cobblestone");
        queue().add(incoming);
        set(organizer, "currentTask", clearance);
        set(organizer, "state", StashOrganizer.State.WALKING);
        set(organizer, "walkTarget", lime);
        @SuppressWarnings("unchecked")
        var retries = (SourceTaskRetryTracker<StashOrganizer.MoveTask>) get("sourceTaskFailures");
        for (int i = 0; i < 10; i++) retries.recordFailure(clearance);
        invoke("retryUntouchedSourceAtTail", "source_walk_timeout");
        assertEquals(StashOrganizer.State.FAILED, organizer.getState());
        assertEquals("lane_clearance_blocked", organizer.getLastFailureReason());
        assertTrue(queue().stream().anyMatch(task -> task == clearance));
        assertSame(incoming, get("currentTask"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void looseBlockerIsPromotedAheadOfStoredShulkerDelivery() throws Exception {
        var incoming = box(imports, lime, "lime_wool");
        var loose = new StashOrganizer.MoveTask(lime, cobble, "cobblestone");
        queue().add(incoming);
        @SuppressWarnings("unchecked")
        var consolidation = (Deque<StashOrganizer.MoveTask>) get("consolidationQueue");
        consolidation.add(loose);
        invoke("advanceToNextTask");
        assertSame(loose, get("currentTask"));
        assertEquals(true, get("consolidationMode"));
        assertTrue(queue().contains(incoming));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void oldSourceCheckpointIsRescheduledBeforeReturningToSource() throws Exception {
        set(organizer, "currentTask", box(imports, lime, "lime_wool"));
        var clearance = box(lime, cobble, "cobblestone");
        queue().add(clearance);
        invoke("resumeInterruptedCheckpoint", StashOrganizer.State.WALKING);
        assertSame(clearance, get("currentTask"));
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertEquals(1, queue().size());
    }

    @Test
    void preflightCheckpointLoadsAndRepeatsTheReadOnlyCheckAfterRestart() throws Exception {
        queue().add(box(imports, lime, "lime_wool"));
        invoke("advanceToNextTask");
        invoke("clearOwnedAutomation");
        organizer = new StashOrganizer(config, new ContainerIndex(), journal);
        assertNotEquals(StashOrganizer.DurableRestoreResult.INVALID, organizer.restoreDurableCheckpoint());
        invoke("resumeInterruptedCheckpoint", StashOrganizer.State.WALKING);
        assertEquals("LANE_PREFLIGHT", get("currentRole").toString());
        assertNull(get("pendingQuickMove"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    private void openProbe() throws Exception {
        set(CACHE.getPlayerCache().getInventoryCache(), "openContainerId", 73);
        set(organizer, "openContainerId", 73);
        set(organizer, "containerDataReceived", true);
        set(organizer, "state", StashOrganizer.State.OPENING);
    }

    @SuppressWarnings("unchecked")
    private Deque<StashOrganizer.MoveTask> queue() throws Exception { return (Deque<StashOrganizer.MoveTask>) get("taskQueue"); }
    private Object get(String name) throws Exception {
        var field = StashOrganizer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(organizer);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private void invoke(String name, Object... arguments) throws Exception {
        Class<?>[] types = java.util.Arrays.stream(arguments).map(Object::getClass).toArray(Class<?>[]::new);
        var method = StashOrganizer.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(organizer, arguments);
    }
    private static StashOrganizer.MoveTask box(int[] from, int[] to, String contents) {
        return new StashOrganizer.MoveTask(from, to, "shulker_box", contents);
    }
    private static long key(int[] pos) {
        return ((long) pos[0] & 0x3FFFFFFL) << 38 | ((long) pos[1] & 0xFFFL) << 26 | ((long) pos[2] & 0x3FFFFFFL);
    }
}
