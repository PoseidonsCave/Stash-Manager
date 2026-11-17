package com.zenith.plugin.stashmanager.organizer;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.kit.KitCargoAudit;
import com.zenith.plugin.stashmanager.kit.KitPartialBorrowedCargoGuard;
import com.zenith.plugin.stashmanager.kit.KitHeldBorrowedBoxPolicy;
import com.zenith.plugin.stashmanager.kit.KitAppleExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitItemSourcePolicy;
import com.zenith.plugin.stashmanager.kit.KitInventoryAppleOwnership;
import com.zenith.plugin.stashmanager.kit.KitInventoryBaseline;
import com.zenith.plugin.stashmanager.kit.KitEmptyShellOwnershipAudit;
import com.zenith.plugin.stashmanager.kit.KitBaselineMismatchDiagnostic;
import com.zenith.plugin.stashmanager.kit.KitCapacity;
import com.zenith.plugin.stashmanager.kit.KitPhysicalSlotBudget;
import com.zenith.plugin.stashmanager.kit.KitPackedStackCapacityRepair;
import com.zenith.plugin.stashmanager.kit.KitFulfillmentPlan;
import com.zenith.plugin.stashmanager.kit.KitFulfillmentJournalStore;
import com.zenith.plugin.stashmanager.kit.KitProvenanceJournal;
import com.zenith.plugin.stashmanager.kit.KitResumePolicy;
import com.zenith.plugin.stashmanager.kit.KitRetainedShell;
import com.zenith.plugin.stashmanager.kit.KitShulkerLedger;
import com.zenith.plugin.stashmanager.kit.KitSourceReturnPolicy;
import com.zenith.plugin.stashmanager.kit.KitVariantExchangePlan;
import com.zenith.plugin.stashmanager.kit.KitCrystalNormalizationPlan;
import com.zenith.plugin.stashmanager.kit.KitVariantFingerprint;
import com.zenith.plugin.stashmanager.kit.KitAppleBudgetVariantEvidence;
import com.zenith.plugin.stashmanager.orchestration.ContainerAccessPlanner;
import com.zenith.plugin.stashmanager.retriever.StashRetriever;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.feature.player.World;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.io.IOException;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/** One-box-at-a-time kit transaction. It stops on uncertainty instead of replaying a move. */
public final class KitFulfillmentCoordinator {
    // A split deposit is verified in the open server window. Zenith's separate
    // player-inventory view can lag after that window closes, especially in a
    // busy stash. Never pack until that view has agreed for several ticks.
    private static final int RETRIEVAL_SETTLE_TICKS = 400;
    private static final int RETRIEVAL_STABLE_TICKS = 6;
    private static final int RETRIEVAL_CLOSE_TIMEOUT_TICKS = 200;
    private static final String ENCHANTED_APPLE = "enchanted_golden_apple";
    public enum State {
        IDLE, NEED_SHELL, WAIT_SHELL, NEED_CARGO, WAIT_CARGO, PACKING,
        VARIANT_NEED_SOURCE, VARIANT_WAIT_SOURCE, VARIANT_PACKING,
        VARIANT_STAGING, RETURNING_SOURCE, EXPORTING, COMPLETE, BLOCKED,
        VERIFY_RETURN, VERIFY_FAILED_RETURN
    }

    private record SlotSnapshot(String itemId, int amount) { }

    private final StashRetriever retriever;
    private final KitPacker packer;
    private final KitExporter exporter;
    private final KitFulfillmentJournalStore journal;
    private final KitProvenanceJournal provenance;
    private State lastTracedState;
    private String lastTracedItem;
    private int lastTracedBox;
    private boolean provenanceFailureReported;
    private State state = State.IDLE;
    private String kitName;
    private String retrievalRequest;
    private String blocker;
    private Map<String, Integer> recipe = Map.of();
    private final Map<String, Integer> packedContents = new LinkedHashMap<>();
    private final List<KitFulfillmentJournalStore.BorrowedBox> sourceBoxesToReturn = new ArrayList<>();
    private final List<KitFulfillmentJournalStore.BorrowedBox> writtenOffSources = new ArrayList<>();
    private List<int[]> registeredReturnChests = List.of();
    private boolean sourceReturnStarted;
    private boolean sourceReturnOriginalAudit;
    private int sourceReturnCloseWaitTicks;
    private List<ContainerEntry> candidates = List.of();
    private Set<Long> excludedPositions = Set.of();
    private int[] regionPos1;
    private int[] regionPos2;
    private int[] exportPosition;
    private int requestedBoxes;
    private int completedBoxes;
    private int incompleteBoxes;
    private final Map<String, Integer> missingTotals = new LinkedHashMap<>();
    private final Map<String, Integer> currentBoxMissing = new LinkedHashMap<>();
    private int ownedShellSlot = -1;
    private KitFulfillmentJournalStore.RetainedShell retainedShell;
    private String currentItem;
    private int currentQuantity;
    private int auditedCargoSlot = -1;
    private Map<Integer, Integer> auditedCargoSlots = Map.of();
    private KitFulfillmentJournalStore.ProtectedLoose protectedLoose;
    private KitFulfillmentJournalStore.HeldBoxRebase heldBoxRebase;
    private Map<Integer, SlotSnapshot> baselineSlots = Map.of();
    private volatile String lastBaselineMismatch;
    private Set<Integer> emptyShellSlotsBefore = Set.of();
    private Boolean retrievalSucceeded;
    private int retrievalSettleTicks;
    private int retrievalVerifiedTicks;
    private int retrievalCloseTicks;
    private int[] borrowedSourceWorksite;
    private String borrowedSourceItemId;
    private Map<String, Integer> borrowedSourceContents = Map.of();
    private int[] kitWorksite;
    private BiConsumer<String, Map<String, Object>> events;
    private String resumeState;
    private long resumeNotBeforeEpochMilli;
    private String recoveredSourceItemId;
    private RequestFuture hotbarEvacuation;
    private int hotbarEvacuationFrom = -1;
    private int hotbarEvacuationTo = -1;
    private int hotbarEvacuationTicks;
    private int recoveryInventorySourceSlot = -1;
    private String recoveryExpectedItem;
    private String recoveryInventorySourceId;
    private Map<String, Integer> recoveryInventorySourceContents = Map.of();
    private Map<KitShulkerLedger.Identity, Integer> recoveryOtherShulkers = Map.of();
    private KitShulkerLedger.Identity recoverySourceAfter;
    private Map<String, Integer> recoveredSourceContents = Map.of();
    private Map<KitShulkerLedger.Identity, Integer> auditedShulkers = Map.of();
    private Map<KitShulkerLedger.Identity, Integer> recoveredShulkersDuringCurrentRetrieval;
    private boolean sourceRecoveredDuringCurrentRetrieval;
    private ItemStack appleCargoExemplar;
    private boolean auditedKeptAppleCargo;
    private boolean returnAuditedEmptySource;
    private int pendingAppleExchangeRemoval;
    private final Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots =
            new LinkedHashMap<>();
    private boolean tryUnnamedApples;
    private boolean appleRetrievalHadTargetFailure;
    private boolean appleNamedInaccessibleTargetSkipped;
    private boolean appleStockExhaustedForBox;
    private Map<String, Integer> inventoryKeepRules;
    private KitFulfillmentJournalStore.VariantRepair variantRepair;
    private boolean variantImportStarted;

    public KitFulfillmentCoordinator(StashRetriever retriever, KitPacker packer,
                                     KitExporter exporter) {
        this(retriever, packer, exporter, KitFulfillmentJournalStore.defaultStore());
    }

    public KitFulfillmentCoordinator(StashRetriever retriever, KitPacker packer,
                                     KitExporter exporter, KitFulfillmentJournalStore journal) {
        this(retriever, packer, exporter, journal, KitProvenanceJournal.defaultStore());
    }

    public KitFulfillmentCoordinator(StashRetriever retriever, KitPacker packer,
                                     KitExporter exporter, KitFulfillmentJournalStore journal,
                                     KitProvenanceJournal provenance) {
        this.retriever = retriever;
        this.packer = packer;
        this.exporter = exporter;
        this.journal = journal;
        this.provenance = provenance;
    }

    public void setEventCallback(BiConsumer<String, Map<String, Object>> events) {
        this.events = events;
    }

    public void setInventoryKeepRules(Map<String, Integer> rules) {
        // A null value means an unlimited keep rule; Map.copyOf would reject it.
        inventoryKeepRules = rules == null ? null : new LinkedHashMap<>(rules);
    }

    public State state() { return state; }
    public boolean isActive() { return state != State.IDLE && state != State.COMPLETE && state != State.BLOCKED; }

    public void onContainerData(int containerId) {
        if (packer.isActive()) packer.onContainerData(containerId);
        if (exporter.isActive()) exporter.onContainerData(containerId);
    }
    public String blocker() { return blocker; }
    /** Present only for a mismatch observed since this plugin process started. */
    public String lastBaselineMismatch() { return lastBaselineMismatch; }
    public int completedBoxes() { return completedBoxes; }
    public int incompleteBoxes() { return incompleteBoxes; }
    public int deliveredBoxes() { return completedBoxes + incompleteBoxes; }
    public int requestedBoxes() { return requestedBoxes; }
    public String kitName() { return kitName; }
    public int[] worksite() { return packer.worksite(); }
    public boolean hasPendingCheckpoint() { return journal.exists(); }

    public void interruptForRecovery(String reason) {
        interruptForRecovery(reason, 0);
    }

    public void interruptForRecovery(String reason, int cooldownSeconds) {
        if (!isActive()) return;
        String interruptedPhase = state.name();
        state = State.BLOCKED;
        if (retriever.isActive()) retriever.stop();
        packer.abortForRecovery(reason);
        exporter.abortForRecovery(reason);
        resumeState = interruptedPhase;
        resumeNotBeforeEpochMilli = cooldownSeconds > 0
                ? System.currentTimeMillis() + Math.max(1L, cooldownSeconds) * 1000L : 0;
        block("interrupted:" + reason, true);
    }

    /** Rehydrate only an unambiguous boundary; never replay a submitted click. */
    public boolean resumeFromCheckpoint(KitFulfillmentJournalStore.Snapshot snapshot,
                                        List<ContainerEntry> candidates, int[] regionPos1,
                                        int[] regionPos2, Set<Long> excludedPositions) {
        return resumeFromCheckpoint(snapshot, candidates, regionPos1, regionPos2,
                excludedPositions, List.of());
    }

    public boolean resumeFromCheckpoint(KitFulfillmentJournalStore.Snapshot snapshot,
                                        List<ContainerEntry> candidates, int[] regionPos1,
                                        int[] regionPos2, Set<Long> excludedPositions,
                                        List<int[]> registeredReturnChests) {
        if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                || KitResumePolicy.blocker(snapshot) != null || isActive()
                || retriever.isActive() || packer.isActive() || exporter.isActive()) return false;
        if (!verifiedDisplacedAppleSlots(snapshot.displacedAppleSlots())) return false;
        if (snapshot.retainedShell() != null && uniqueRetainedShellSlot(snapshot) < 0)
            return false;
        boolean auditedKeptApples = "resume_audited_kept_apple_cargo".equals(snapshot.blocker());
        boolean auditedEmptyShell = KitEmptyShellOwnershipAudit.isScheduled(snapshot.blocker());
        boolean variantStart = "resume_variant_exchange".equals(snapshot.blocker());
        boolean variantReady = "resume_variant_source_cargo".equals(snapshot.blocker());
        boolean variantStaging = "resume_variant_staging".equals(snapshot.blocker());
        boolean auditedCargo = "resume_audited_inventory_cargo".equals(snapshot.blocker())
                || auditedKeptApples || auditedEmptyShell;
        boolean borrowedInventorySource =
                "resume_borrowed_inventory_source".equals(snapshot.blocker())
                || "resume_held_box_rebase".equals(snapshot.blocker());
        if ("resume_held_box_rebase".equals(snapshot.blocker())) {
            Container player = playerInventory();
            Map<Integer, ItemStack> live = new HashMap<>();
            if (player != null) for (int raw = 9; raw <= 44; raw++)
                live.put(raw, player.getItemStack(raw));
            int retained = snapshot.retainedShell() == null ? -1
                    : uniqueRetainedShellSlot(snapshot);
            if (!KitHeldBorrowedBoxPolicy.verifiedScheduled(snapshot, live,
                    uniqueOwnedShellSlot(snapshot), retained)) return false;
        }
        boolean originalUnopenedReturn = "resume_unopened_source_to_origin"
                .equals(snapshot.blocker());
        boolean returningSource = "resume_source_return".equals(snapshot.blocker())
                || originalUnopenedReturn;
        boolean exactCapacityImportReturn = "resume_source_return_exact_import"
                .equals(snapshot.blocker());
        boolean verifyCompletedReturn = "resume_audit_completed_source_return"
                .equals(snapshot.blocker());
        boolean verifyFailedReturn = "resume_audit_failed_source_return"
                .equals(snapshot.blocker());
        returningSource |= exactCapacityImportReturn;
        if (auditedCargo && !verifiedAuditedCargo(snapshot)) return false;
        if (auditedEmptyShell
                && !KitEmptyShellOwnershipAudit.verify(snapshot, playerInventory())) return false;
        if (auditedCargo && !auditedEmptyShell && !snapshot.sourceBoxesToReturn().isEmpty()) {
            if (snapshot.sourceBoxesToReturn().size() == 1) {
                if (uniqueSourceReturnSlot(snapshot) < 0) return false;
            } else if (!verifiedPendingSourceInventory(snapshot)) return false;
        }
        if (variantStart && (!verifiedVariantRepairBoundary(snapshot)
                || !verifiedVariantPendingSources(snapshot))) return false;
        if (variantReady && !verifiedVariantReadyBoundary(snapshot)) return false;
        if (variantStaging && !verifiedVariantStagingBoundary(snapshot)) return false;
        int verifiedSingleCargoSlot = auditedCargo && snapshot.auditedCargoSlots().isEmpty()
                ? uniqueAuditedCargoSlot(snapshot) : -1;
        if (auditedCargo && snapshot.auditedCargoSlots().isEmpty()
                && verifiedSingleCargoSlot < 0) return false;
        ItemStack auditedApple = auditedCargo && ENCHANTED_APPLE.equals(snapshot.currentItem())
                ? auditedAppleExemplar(snapshot, verifiedSingleCargoSlot, auditedKeptApples) : null;
        if (auditedCargo && ENCHANTED_APPLE.equals(snapshot.currentItem())
                && auditedApple == null) return false;
        if (auditedKeptApples && !auditedAppleFitsSlots(snapshot)) return false;
        if ("NEED_CARGO".equals(snapshot.resumeState()) || auditedCargo
                || returningSource || verifyCompletedReturn || verifyFailedReturn || variantReady) {
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = inventoryCache.getMouseStack();
            if (inventoryCache.getOpenContainerId() > 0
                    || (cursor != null && cursor.getAmount() > 0)) return false;
            if (!hasVerifiedOwnedShell(snapshot)) return false;
        }
        int inventorySourceSlot = borrowedInventorySource
                ? uniqueBorrowedInventorySourceSlot(snapshot) : -1;
        if (borrowedInventorySource && inventorySourceSlot < 0) return false;
        if (returningSource && uniqueSourceReturnSlot(snapshot) < 0) return false;
        if (originalUnopenedReturn && (snapshot.sourceBoxesToReturn().size() != 1
                || snapshot.sourceBoxesToReturn().getFirst().sourcePosition() == null
                || !java.util.Arrays.equals(
                        snapshot.sourceBoxesToReturn().getFirst().sourcePosition(),
                        snapshot.sourceBoxesToReturn().getFirst().returnPosition())))
            return false;
        if (verifyCompletedReturn && (snapshot.sourceBoxesToReturn().size() != 1
                || uniqueSourceReturnSlot(snapshot) >= 0)) return false;
        if (verifyFailedReturn && (snapshot.sourceBoxesToReturn().size() != 1
                || uniqueSourceReturnSlot(snapshot) < 0)) return false;
        this.kitName = snapshot.kitName();
        this.lastBaselineMismatch = null;
        this.recipe = new LinkedHashMap<>(snapshot.recipe());
        this.packedContents.clear();
        this.packedContents.putAll(snapshot.packedContents());
        this.sourceBoxesToReturn.clear();
        this.sourceBoxesToReturn.addAll(snapshot.sourceBoxesToReturn());
        this.writtenOffSources.clear();
        this.writtenOffSources.addAll(snapshot.writtenOffSources());
        this.sourceReturnStarted = false;
        this.sourceReturnOriginalAudit = originalUnopenedReturn;
        this.sourceReturnCloseWaitTicks = 0;
        this.requestedBoxes = snapshot.requestedBoxes();
        this.completedBoxes = snapshot.completedBoxes();
        this.incompleteBoxes = snapshot.incompleteBoxes();
        this.missingTotals.clear();
        this.missingTotals.putAll(snapshot.missingTotals());
        this.currentBoxMissing.clear();
        this.currentBoxMissing.putAll(snapshot.currentBoxMissing());
        this.exportPosition = snapshot.exportPosition();
        this.ownedShellSlot = snapshot.ownedShellSlot();
        this.retainedShell = snapshot.retainedShell();
        this.currentItem = auditedCargo || borrowedInventorySource || variantStart || variantReady
                || variantStaging
                || verifyCompletedReturn
                ? snapshot.currentItem() : null;
        this.currentQuantity = auditedCargo || borrowedInventorySource || variantStart || variantReady
                || variantStaging
                || verifyCompletedReturn
                ? snapshot.currentQuantity() : 0;
        this.auditedCargoSlot = auditedCargo && verifiedSingleCargoSlot >= 0
                ? verifiedSingleCargoSlot : auditedCargo ? snapshot.auditedCargoSlot() : -1;
        this.auditedCargoSlots = auditedCargo || variantStart || variantReady || variantStaging
                ? snapshot.auditedCargoSlots() : Map.of();
        this.protectedLoose = auditedCargo ? snapshot.protectedLoose() : null;
        this.heldBoxRebase = borrowedInventorySource ? snapshot.heldBoxRebase() : null;
        this.candidates = List.copyOf(candidates);
        this.registeredReturnChests = copyPositions(registeredReturnChests);
        this.regionPos1 = regionPos1 == null ? null : regionPos1.clone();
        this.regionPos2 = regionPos2 == null ? null : regionPos2.clone();
        this.excludedPositions = Set.copyOf(excludedPositions);
        this.borrowedSourceWorksite = null;
        this.borrowedSourceItemId = null;
        this.borrowedSourceContents = Map.of();
        this.recoveredSourceItemId = null;
        this.recoveredSourceContents = Map.of();
        this.auditedShulkers = auditedCargo || variantReady
                ? inventoryShulkerIdentities() : Map.of();
        this.sourceRecoveredDuringCurrentRetrieval = false;
        this.recoveredShulkersDuringCurrentRetrieval = new HashMap<>();
        this.appleCargoExemplar = auditedApple;
        this.auditedKeptAppleCargo = auditedKeptApples;
        this.returnAuditedEmptySource = auditedEmptyShell;
        this.pendingAppleExchangeRemoval = 0;
        this.displacedAppleSlots.clear();
        this.displacedAppleSlots.putAll(snapshot.displacedAppleSlots());
        this.tryUnnamedApples = false;
        this.appleRetrievalHadTargetFailure = false;
        this.appleNamedInaccessibleTargetSkipped = false;
        this.appleStockExhaustedForBox = false;
        this.variantRepair = snapshot.variantRepair();
        this.variantImportStarted = false;
        if (variantReady) {
            Map<Integer, SlotSnapshot> occupied = occupiedSlots();
            occupied.remove(variantRepair.auditedIncomingRawSlot());
            this.baselineSlots = Map.copyOf(occupied);
        }
        if (auditedCargo) {
            Map<Integer, SlotSnapshot> occupied = occupiedSlots();
            auditedCargoExclusions(auditedCargoSlot, auditedCargoSlots).forEach(occupied::remove);
            this.baselineSlots = Map.copyOf(occupied);
            int sourceSlot = uniqueInventoryShulkerContaining(currentItem, ownedShellSlot);
            if (sourceSlot >= 0) {
                ItemStack source = playerInventory().getItemStack(sourceSlot);
                this.recoveredSourceItemId = ItemIdentifier.baseItemId(
                        ItemIdentifier.getItemId(source));
                this.recoveredSourceContents = Map.copyOf(
                        ItemIdentifier.readShulkerContents(source));
            }
        }
        this.recoveryInventorySourceSlot = inventorySourceSlot;
        this.recoveryExpectedItem = inventorySourceSlot < 0 ? null : snapshot.currentItem();
        ItemStack recoverySource = inventorySourceSlot < 0 ? null
                : playerInventory().getItemStack(inventorySourceSlot);
        this.recoveryInventorySourceId = recoverySource == null ? null
                : ItemIdentifier.getItemId(recoverySource);
        this.recoveryInventorySourceContents = recoverySource == null ? Map.of()
                : Map.copyOf(ItemIdentifier.readShulkerContents(recoverySource));
        this.recoveryOtherShulkers = Map.of();
        this.recoverySourceAfter = null;
        this.hotbarEvacuation = null;
        this.kitWorksite = null;
        this.blocker = null;
        this.retrievalRequest = null;
        this.retrievalSucceeded = auditedCargo || variantReady ? Boolean.TRUE : null;
        this.retrievalSettleTicks = 0;
        this.retrievalVerifiedTicks = 0;
        this.retrievalCloseTicks = 0;
        this.resumeState = null;
        this.resumeNotBeforeEpochMilli = 0;
        this.state = State.valueOf(snapshot.resumeState());
        if (exactCapacityImportReturn && (sourceBoxesToReturn.size() != 1
                || sourceBoxesToReturn.getFirst().returnPosition() == null
                || !KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(candidates,
                        registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                        sourceBoxesToReturn.getFirst().sourcePosition(), exportPosition,
                        sourceBoxesToReturn.getFirst().returnPosition()))) {
            block("kit_source_return_exact_import_unverified");
            return false;
        }
        if (verifyCompletedReturn && (sourceBoxesToReturn.size() != 1
                || sourceBoxesToReturn.getFirst().returnPosition() == null
                || !KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(candidates,
                        registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                        sourceBoxesToReturn.getFirst().sourcePosition(), exportPosition,
                        sourceBoxesToReturn.getFirst().returnPosition()))) {
            block("kit_completed_return_target_unverified");
            return false;
        }
        if (verifyFailedReturn && (sourceBoxesToReturn.size() != 1
                || sourceBoxesToReturn.getFirst().sourcePosition() == null
                || sourceBoxesToReturn.getFirst().returnPosition() == null
                || !KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(candidates,
                        registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                        sourceBoxesToReturn.getFirst().sourcePosition(), exportPosition,
                        sourceBoxesToReturn.getFirst().returnPosition()))) {
            block("kit_failed_return_target_unverified");
            return false;
        }
        if (returningSource && !exactCapacityImportReturn && !originalUnopenedReturn
                && !selectNextReturnTarget("checkpoint_" + snapshot.blocker())) {
            block("kit_source_return_no_capacity_import");
            return false;
        }
        if (!persist()) {
            state = State.BLOCKED;
            return false;
        }
        emit("kit_fulfillment_resumed", Map.of("kit", kitName,
                "completed_boxes", completedBoxes, "phase", state.name()));
        if (auditedCargo) emit("kit_cargo_audit_accepted", Map.of(
                "kit", kitName, "item", currentItem, "quantity", currentQuantity,
                "inventory_slots", auditedCargoSlots.isEmpty()
                        ? String.valueOf(auditedCargoSlot) : auditedCargoSlots.toString()));
        return true;
    }

    public KitFulfillmentJournalStore.Snapshot pendingCheckpoint() throws IOException {
        return journal.load().orElse(null);
    }

    public static boolean hasVerifiedOwnedShell(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.ownedShellSlot() < 9
                || snapshot.ownedShellSlot() > 44) return false;
        Container inventory = playerInventory();
        ItemStack shell = inventory == null ? null
                : inventory.getItemStack(snapshot.ownedShellSlot());
        return isShulker(shell) && shell.getAmount() == 1
                && snapshot.packedContents().equals(ItemIdentifier.readShulkerContents(shell));
    }

    /** Schedule-only boundary: no source item or inventory click may be assumed. */
    public static boolean verifiedVariantRepairBoundary(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        return variantRepairBoundaryFailure(snapshot) == null;
    }

    /** Read-only reason for a rejected legacy variant boundary; never re-journals hashes. */
    public static String variantRepairBoundaryFailure(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        boolean appleBudget = appleBudgetVariantRepair(snapshot);
        boolean packedStackRepair = KitPackedStackCapacityRepair.repairCheckpoint(snapshot);
        if (snapshot == null || snapshot.variantRepair() == null
                || !("BLOCKED".equals(snapshot.state())
                    || "VARIANT_NEED_SOURCE".equals(snapshot.state()))
                || snapshot.currentItem() == null
                || (!appleBudget && !packedStackRepair && snapshot.currentQuantity() != 1)
                || snapshot.kitWorksite() != null || snapshot.borrowedSourceWorksite() != null)
            return "checkpoint_phase_or_worksite";
        if (!appleBudget && !packedStackRepair && uniqueCurrentLooseCargoSlot(snapshot)
                != snapshot.variantRepair().cargoRawSlot()) return "loose_task_cargo_slot";
        Container player = playerInventory();
        if (player == null) return "player_inventory_unavailable";
        ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
        if (!hasVerifiedOwnedShell(snapshot) || shell == null) return "owned_shell_contents";
        Map<Integer, ItemStack> slots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
        var repair = snapshot.variantRepair();
        if (appleBudget) {
            Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack != null && stack.getAmount() > 0)
                    stacks.put(raw, new KitCargoAudit.Stack(
                            ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
            if (!KitCargoAudit.verifiedBatch(stacks, snapshot.currentItem(),
                    snapshot.currentQuantity(), snapshot.ownedShellSlot(),
                    snapshot.auditedCargoSlots(), true)) return "audited_apple_batch_changed";
            for (int raw : snapshot.auditedCargoSlots().keySet()) {
                ItemStack cargo = player.getItemStack(raw);
                if (!repair.cargoHash().equals(KitVariantFingerprint.variant(
                        cargo, repair.fingerprintVersion())))
                    return "audited_apple_variant_changed";
            }
        } else if (!packedStackRepair) {
            ItemStack cargo = player.getItemStack(repair.cargoRawSlot());
            if (cargo == null || cargo.getAmount() != 1
                    || !snapshot.currentItem().equals(ItemIdentifier.getItemId(cargo)))
                return "loose_task_cargo_identity";
            if (!repair.cargoHash().equals(KitVariantFingerprint.variant(
                    cargo, repair.fingerprintVersion())))
                return "legacy_cargo_fingerprint";
        }
        if (slots.size() != (packedStackRepair
                ? KitPackedStackCapacityRepair.profile(repair.itemId()).physicalSlots()
                : appleBudget ? KitCapacity.MAX_SLOTS - 1 : KitCapacity.MAX_SLOTS))
            return "owned_shell_physical_capacity";
        if (!repair.shellHash().equals(KitVariantFingerprint.shell(
                slots, repair.fingerprintVersion())))
            return "legacy_shell_fingerprint";
        List<KitVariantExchangePlan.SlotStack> physical = slots.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        if (packedStackRepair) {
            var opportunity = KitPackedStackCapacityRepair.opportunity(slots,
                    repair.itemId());
            if (opportunity == null || opportunity.amount() != repair.amount()
                    || opportunity.outgoingSlot() != repair.outgoingSlot()
                    || opportunity.mergeTargetSlot() != repair.mergeTargetSlot())
                return "packed_stack_replacement_opportunity";
            if (!repair.sourceHash().equals(KitVariantFingerprint.variant(
                    opportunity.mergeTargetStack(), repair.fingerprintVersion()))
                    || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                    opportunity.outgoingStack(), repair.fingerprintVersion())))
                return "packed_stack_variant_fingerprint";
        } else if (repair.replacementOnly()) {
            var step = KitCrystalNormalizationPlan.next(slots);
            if (step == null || step.amount() != repair.amount()
                    || step.outgoingSlot() != repair.outgoingSlot()
                    || step.anchorSlot() != repair.mergeTargetSlot())
                return "shell_replacement_opportunity";
            if (!repair.sourceHash().equals(KitVariantFingerprint.variant(
                    step.anchor(), repair.fingerprintVersion())))
                return "replacement_source_fingerprint";
            if (!repair.outgoingHash().equals(KitVariantFingerprint.variant(
                    step.outgoing(), repair.fingerprintVersion())))
                return "replacement_outgoing_fingerprint";
        } else {
            var opportunity = KitVariantExchangePlan.shellOpportunity(physical, repair.itemId(),
                    snapshot.packedContents().getOrDefault(repair.itemId(), 0),
                    KitCapacity.MAX_SLOTS - (appleBudget ? 2 : 1));
            if (opportunity == null || opportunity.amount() != repair.amount()
                    || opportunity.outgoingSlot() != repair.outgoingSlot()
                    || opportunity.mergeTargetSlot() != repair.mergeTargetSlot())
                return "shell_exchange_opportunity";
            if (!repair.sourceHash().equals(KitVariantFingerprint.variant(
                    opportunity.mergeTargetStack(), repair.fingerprintVersion())))
                return "merge_target_fingerprint";
            if (!repair.outgoingHash().equals(KitVariantFingerprint.variant(
                    opportunity.outgoingStack(), repair.fingerprintVersion())))
                return "outgoing_fingerprint";
        }
        return null;
    }

    public static boolean appleBudgetVariantRepair(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.variantRepair() == null) return false;
        var repair = snapshot.variantRepair();
        return !repair.replacementOnly() && repair.cargoRawSlot() == -1
                && "enchanted_golden_apple".equals(snapshot.currentItem())
                && snapshot.currentQuantity() == 128
                && snapshot.auditedCargoSlots().size() == 2
                && snapshot.auditedCargoSlots().values().stream()
                    .allMatch(count -> count != null && count == 64)
                && snapshot.packedContents().getOrDefault("end_crystal", 0) == 256
                && "end_crystal".equals(repair.itemId())
                && repair.amount() == 10;
    }

    /** A post-retrieval stop may resume only the exact already-held variant stack.
     * No second retrieval is allowed from this boundary. */
    public static boolean verifiedVariantReadyBoundary(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        return variantReadyBoundaryFailure(snapshot) == null;
    }

    /** An already-held replacement shell may continue only to import its
     * uniquely identified displaced stack. */
    public static boolean verifiedVariantStagingBoundary(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        boolean appleBudget = KitResumePolicy.stagedAppleBudgetVariant(snapshot);
        if (KitPackedStackCapacityRepair.repairCheckpoint(snapshot)) {
            var repair = snapshot.variantRepair();
            if (repair.resultShellHash() == null || snapshot.kitWorksite() != null
                    || snapshot.borrowedSourceWorksite() != null
                    || snapshot.sourceBoxesToReturn().size() > 1
                    || !hasVerifiedOwnedShell(snapshot)
                    || variantPendingSourceSlots(snapshot).size()
                            != snapshot.sourceBoxesToReturn().size()
                    || repair.displacedRawSlot() < 9 || repair.displacedRawSlot() > 44)
                return false;
            Container player = playerInventory();
            ItemStack shell = player == null ? null
                    : player.getItemStack(snapshot.ownedShellSlot());
            ItemStack displaced = player == null ? null
                    : player.getItemStack(repair.displacedRawSlot());
            if (shell == null || displaced == null
                    || displaced.getAmount() != repair.amount()
                    || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                            displaced, repair.fingerprintVersion()))) return false;
            var slots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
            if (slots.size() != KitPackedStackCapacityRepair.profile(
                    repair.itemId()).physicalSlots()
                    || !repair.resultShellHash().equals(
                    KitVariantFingerprint.shell(slots, repair.fingerprintVersion()))
                    || !KitPhysicalSlotBudget.fitsAfterCompaction(slots.size(),
                            List.copyOf(slots.values()), snapshot.recipe(),
                            snapshot.packedContents())) return false;
            var before = new HashMap<>(slots);
            before.put(repair.outgoingSlot(), displaced);
            return repair.shellHash().equals(KitVariantFingerprint.shell(
                    before, repair.fingerprintVersion()));
        }
        if (snapshot == null || snapshot.variantRepair() == null
                || !snapshot.variantRepair().replacementOnly() && !appleBudget
                || snapshot.variantRepair().resultShellHash() == null
                || snapshot.kitWorksite() != null
                || snapshot.borrowedSourceWorksite() != null
                || snapshot.sourceBoxesToReturn().size() != 2
                || !hasVerifiedOwnedShell(snapshot)
                || verifiedPendingSourceSlots(snapshot).size() != 2
                || !appleBudget && uniqueCurrentLooseCargoSlot(snapshot)
                        != snapshot.variantRepair().cargoRawSlot()) return false;
        var repair = snapshot.variantRepair();
        Container player = playerInventory();
        if (player == null || repair.displacedRawSlot() < 9
                || repair.displacedRawSlot() > 35) return false;
        if (appleBudget) {
            Map<Integer, ItemStack> observed = new HashMap<>();
            for (int raw = 9; raw <= 44; raw++)
                observed.put(raw, player.getItemStack(raw));
            var proof = KitAppleBudgetVariantEvidence.prove(snapshot, observed);
            return proof != null && proof.shellSlot() == snapshot.ownedShellSlot()
                    && proof.displacedSlot() == repair.displacedRawSlot()
                    && proof.resultShellHash().equals(repair.resultShellHash());
        }
        ItemStack shell = player.getItemStack(snapshot.ownedShellSlot());
        ItemStack displaced = player.getItemStack(repair.displacedRawSlot());
        ItemStack cargo = player.getItemStack(repair.cargoRawSlot());
        if (shell == null || displaced == null || cargo == null
                || displaced.getAmount() != repair.amount()
                || cargo.getAmount() != 1
                || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                        displaced, repair.fingerprintVersion()))
                || !repair.cargoHash().equals(KitVariantFingerprint.variant(
                        cargo, repair.fingerprintVersion()))) return false;
        var slots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
        var next = KitCrystalNormalizationPlan.next(slots);
        if (next == null || next.number() != 2 || repair.amount() != 18
                || !repair.resultShellHash().equals(KitVariantFingerprint.shell(
                        slots, repair.fingerprintVersion()))) return false;
        var before = new HashMap<>(slots);
        before.put(repair.outgoingSlot(), displaced);
        return repair.shellHash().equals(KitVariantFingerprint.shell(
                before, repair.fingerprintVersion()));
    }

    /** The first exact failed check, for a read-only audit preview. */
    public static String variantReadyBoundaryFailure(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.variantRepair() == null)
            return "variant_repair_missing";
        if (snapshot.variantRepair().replacementOnly()
                ? snapshot.sourceBoxesToReturn().isEmpty()
                    || snapshot.sourceBoxesToReturn().size() > 2
                : snapshot.sourceBoxesToReturn().size() != 2)
            return "borrowed_source_box_count";
        String repairFailure = variantRepairBoundaryFailure(snapshot);
        if (repairFailure != null) return repairFailure;
        if (!verifiedVariantPendingSources(snapshot)) return "borrowed_source_box_ledger";
        var repair = snapshot.variantRepair();
        int incomingRaw = repair.auditedIncomingRawSlot();
        if (incomingRaw < 9 || incomingRaw > 44
                || incomingRaw > 35
                    && !KitPackedStackCapacityRepair.repairCheckpoint(snapshot))
            return "incoming_slot_invalid";
        Container player = playerInventory();
        if (player == null) return "player_inventory_unavailable";
        int total = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0
                    || !repair.itemId().equals(ItemIdentifier.getItemId(stack))) continue;
            if (raw != incomingRaw) return "additional_loose_repair_item";
            if (stack.getAmount() != repair.amount()) return "incoming_quantity";
            if (!repair.sourceHash().equals(KitVariantFingerprint.variant(
                    stack, repair.fingerprintVersion())))
                return "legacy_incoming_fingerprint";
            total += stack.getAmount();
        }
        return total == repair.amount() ? null : "incoming_stack_missing";
    }

    /** A recorded slot may move when Zenith swaps same-color shulkers into the hotbar. */
    public static int uniqueOwnedShellSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null) return -1;
        KitShulkerLedger.Identity recovered = null;
        if (snapshot.sourceBoxesToReturn() != null && !snapshot.sourceBoxesToReturn().isEmpty()) {
            var source = snapshot.sourceBoxesToReturn().getFirst();
            recovered = new KitShulkerLedger.Identity(source.itemId(), source.contents());
        }
        return KitShulkerLedger.ownedSlot(inventoryShulkerSlots(), snapshot.ownedShellSlot(),
                snapshot.packedContents(), recovered);
    }

    /** Return only a unique, still-full borrowed box with no loose cargo to replay. */
    public static int uniqueBorrowedInventorySourceSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.currentItem() == null || snapshot.currentQuantity() <= 0)
            return -1;
        Container player = playerInventory();
        if (player == null) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0 || raw == snapshot.ownedShellSlot()) continue;
            if (!isShulker(stack)) {
                if (snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) return -1;
                continue;
            }
            if (ItemIdentifier.readShulkerContents(stack)
                    .getOrDefault(snapshot.currentItem(), 0) < snapshot.currentQuantity()) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    /** A clean retrieval retry must not mistake already-loose or borrowed cargo for fresh stock. */
    public static boolean hasNoUnclaimedCurrentCargo(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.currentItem() == null) return false;
        Container player = playerInventory();
        if (player == null) return false;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == snapshot.ownedShellSlot()) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            if (snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) return false;
            if (isShulker(stack) && ItemIdentifier.readShulkerContents(stack)
                    .getOrDefault(snapshot.currentItem(), 0) > 0) return false;
        }
        return true;
    }

    /** Verify the exact borrowed box is still in inventory before retrying a full return chest. */
    public static int uniqueSourceReturnSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().isEmpty()) return -1;
        Container player = playerInventory();
        if (player == null) return -1;
        var source = snapshot.sourceBoxesToReturn().getFirst();
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == snapshot.ownedShellSlot()) continue;
            ItemStack stack = player.getItemStack(raw);
            if (!isShulker(stack) || stack.getAmount() != 1
                    || !source.itemId().equals(ItemIdentifier.getItemId(stack))
                    || !source.contents().equals(ItemIdentifier.readShulkerContents(stack))) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    /** At a manually audited multi-source boundary, allow no unidentified shulker in inventory. */
    public static boolean verifiedPendingSourceInventory(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().isEmpty()) return false;
        int shellSlot = uniqueOwnedShellSlot(snapshot);
        KitShulkerLedger.Identity shell = inventoryShulkerSlots().get(shellSlot);
        if (shell == null || !shell.contents().equals(snapshot.packedContents())) return false;
        List<KitShulkerLedger.Identity> pending = new ArrayList<>();
        for (var source : snapshot.sourceBoxesToReturn()) {
            if (source == null || source.itemId() == null || source.contents() == null) return false;
            pending.add(new KitShulkerLedger.Identity(source.itemId(), source.contents()));
        }
        return KitShulkerLedger.matchesPendingReturns(
                inventoryShulkerIdentities(), shell, pending);
    }

    /** Return only source slots proven by the complete live shulker multiset. */
    public static Set<Integer> verifiedPendingSourceSlots(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().isEmpty()) return Set.of();
        int shellSlot = uniqueOwnedShellSlot(snapshot);
        Map<Integer, KitShulkerLedger.Identity> slots = inventoryShulkerSlots();
        KitShulkerLedger.Identity shell = slots.get(shellSlot);
        List<KitShulkerLedger.Identity> pending = new ArrayList<>();
        for (var source : snapshot.sourceBoxesToReturn()) {
            if (source == null || source.itemId() == null || source.contents() == null)
                return Set.of();
            pending.add(new KitShulkerLedger.Identity(source.itemId(), source.contents()));
        }
        return KitShulkerLedger.pendingReturnSlots(slots, shellSlot, shell, pending);
    }

    /** A reserved empty next-kit shell is owned but is not a borrowed source return. */
    public static boolean verifiedVariantPendingSources(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (KitPackedStackCapacityRepair.repairCheckpoint(snapshot)
                && snapshot.sourceBoxesToReturn().isEmpty()) {
            return hasVerifiedOwnedShell(snapshot) && hasNoUnclaimedCurrentCargo(snapshot);
        }
        if (snapshot != null && snapshot.variantRepair() != null
                && snapshot.variantRepair().replacementOnly()
                && snapshot.sourceBoxesToReturn().isEmpty()
                && hasVerifiedOwnedShell(snapshot)
                && uniqueCurrentLooseCargoSlot(snapshot)
                    == snapshot.variantRepair().cargoRawSlot()) {
            Container player = playerInventory();
            ItemStack shell = player == null ? null
                    : player.getItemStack(snapshot.ownedShellSlot());
            var next = KitCrystalNormalizationPlan.next(
                    ItemIdentifier.readShulkerPhysicalSlotStacks(shell));
            return next != null && next.number() == 2;
        }
        return snapshot != null && snapshot.sourceBoxesToReturn() != null
                && !snapshot.sourceBoxesToReturn().isEmpty()
                && variantPendingSourceSlots(snapshot).size()
                == snapshot.sourceBoxesToReturn().size();
    }

    public static Set<Integer> variantPendingSourceSlots(
            KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().isEmpty()) return Set.of();
        int shellSlot = uniqueOwnedShellSlot(snapshot);
        Map<Integer, KitShulkerLedger.Identity> slots =
                new HashMap<>(inventoryShulkerSlots());
        KitShulkerLedger.Identity shell = slots.get(shellSlot);
        if (shell == null || !shell.contents().equals(snapshot.packedContents()))
            return Set.of();
        if (snapshot.retainedShell() != null) {
            int reservedSlot = uniqueRetainedShellSlot(snapshot);
            if (reservedSlot < 9 || reservedSlot == shellSlot
                    || slots.remove(reservedSlot) == null) return Set.of();
        }
        List<KitShulkerLedger.Identity> pending = new ArrayList<>();
        for (var source : snapshot.sourceBoxesToReturn()) {
            if (source == null || source.itemId() == null || source.contents() == null)
                return Set.of();
            pending.add(new KitShulkerLedger.Identity(source.itemId(), source.contents()));
        }
        return KitShulkerLedger.pendingReturnSlots(slots, shellSlot, shell, pending);
    }

    /** Exact loose cargo is the only safe post-recovery batch to pack without retrieval. */
    public static int uniqueAuditedCargoSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.currentItem() == null
                || snapshot.currentQuantity() <= 0) return -1;
        Container player = playerInventory();
        if (player == null) return -1;
        Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                stacks.put(raw, new KitCargoAudit.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        if (snapshot.auditedCargoSlot() >= 9) {
            return KitCargoAudit.verifiedSlot(stacks, snapshot.currentItem(),
                    snapshot.currentQuantity(), snapshot.ownedShellSlot(),
                    snapshot.auditedCargoSlot());
        }
        return KitCargoAudit.uniqueSlot(stacks, snapshot.currentItem(),
                snapshot.currentQuantity(), snapshot.ownedShellSlot());
    }

    public static boolean verifiedAuditedCargo(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.auditedCargoSlots().isEmpty()) return uniqueAuditedCargoSlot(snapshot) >= 0;
        Container player = playerInventory();
        if (player == null) return false;
        Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
        Map<Integer, ItemStack> physical = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                physical.put(raw, stack);
                stacks.put(raw, new KitCargoAudit.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        if (snapshot.protectedLoose() != null
                && !KitPartialBorrowedCargoGuard.verifiedScheduled(snapshot, physical))
            return false;
        return KitCargoAudit.verifiedBatch(stacks, snapshot.currentItem(),
                snapshot.currentQuantity(), snapshot.ownedShellSlot(), snapshot.auditedCargoSlots(),
                "resume_audited_kept_apple_cargo".equals(snapshot.blocker())
                        || KitEmptyShellOwnershipAudit.isScheduled(snapshot.blocker())
                        || snapshot.protectedLoose() != null);
    }

    static Set<Integer> auditedCargoExclusions(int verifiedSingleSlot,
                                               Map<Integer, Integer> verifiedBatchSlots) {
        return verifiedBatchSlots.isEmpty()
                ? verifiedSingleSlot >= 9 ? Set.of(verifiedSingleSlot) : Set.of()
                : Set.copyOf(verifiedBatchSlots.keySet());
    }

    /** Stricter than a pinned cargo audit: rejects any other loose stack of this item. */
    public static int uniqueCurrentLooseCargoSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.currentItem() == null
                || snapshot.currentQuantity() <= 0) return -1;
        Container player = playerInventory();
        if (player == null) return -1;
        Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                stacks.put(raw, new KitCargoAudit.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        return KitCargoAudit.uniqueSlot(stacks, snapshot.currentItem(),
                snapshot.currentQuantity(), snapshot.ownedShellSlot());
    }

    public boolean start(String kitName, Map<String, Integer> recipe, int boxes,
                         int[] exportPosition, List<ContainerEntry> candidates,
                         int[] regionPos1, int[] regionPos2, Set<Long> excludedPositions) {
        return start(kitName, recipe, boxes, exportPosition, candidates,
                regionPos1, regionPos2, excludedPositions, List.of());
    }

    public boolean start(String kitName, Map<String, Integer> recipe, int boxes,
                         int[] exportPosition, List<ContainerEntry> candidates,
                         int[] regionPos1, int[] regionPos2, Set<Long> excludedPositions,
                         List<int[]> registeredReturnChests) {
        if (journal.exists() || isActive() || retriever.isActive() || packer.isActive() || exporter.isActive()
                || kitName == null || kitName.isBlank() || boxes < 1
                || recipe == null || recipe.isEmpty() || exportPosition == null
                || exportPosition.length != 3 || candidates == null
                || recipe.keySet().stream().anyMatch(id -> id == null
                    || ItemIdentifier.baseItemId(id).contains("shulker_box"))
                || KitCapacity.requiredSlots(recipe) > KitCapacity.MAX_SLOTS
                || !hotbarSlotsReady()) return false;
        this.kitName = kitName;
        this.lastBaselineMismatch = null;
        this.recipe = new LinkedHashMap<>(recipe);
        this.requestedBoxes = boxes;
        this.completedBoxes = 0;
        this.incompleteBoxes = 0;
        this.missingTotals.clear();
        this.currentBoxMissing.clear();
        this.exportPosition = exportPosition.clone();
        this.candidates = List.copyOf(candidates);
        this.registeredReturnChests = copyPositions(registeredReturnChests);
        this.regionPos1 = regionPos1 == null ? null : regionPos1.clone();
        this.regionPos2 = regionPos2 == null ? null : regionPos2.clone();
        this.excludedPositions = excludedPositions == null ? Set.of() : Set.copyOf(excludedPositions);
        this.blocker = null;
        this.ownedShellSlot = -1;
        this.retainedShell = null;
        this.currentItem = null;
        this.auditedCargoSlot = -1;
        this.auditedCargoSlots = Map.of();
        this.protectedLoose = null;
        this.heldBoxRebase = null;
        this.packedContents.clear();
        this.sourceBoxesToReturn.clear();
        this.writtenOffSources.clear();
        this.sourceReturnStarted = false;
        this.sourceReturnCloseWaitTicks = 0;
        this.borrowedSourceWorksite = null;
        this.borrowedSourceItemId = null;
        this.borrowedSourceContents = Map.of();
        this.recoveredSourceItemId = null;
        this.recoveredSourceContents = Map.of();
        this.auditedShulkers = Map.of();
        this.sourceRecoveredDuringCurrentRetrieval = false;
        this.recoveredShulkersDuringCurrentRetrieval = new HashMap<>();
        this.appleCargoExemplar = null;
        this.auditedKeptAppleCargo = false;
        this.pendingAppleExchangeRemoval = 0;
        this.displacedAppleSlots.clear();
        this.tryUnnamedApples = false;
        this.appleRetrievalHadTargetFailure = false;
        this.appleStockExhaustedForBox = false;
        this.variantRepair = null;
        this.variantImportStarted = false;
        this.recoveryInventorySourceSlot = -1;
        this.recoveryExpectedItem = null;
        this.recoveryInventorySourceId = null;
        this.recoveryInventorySourceContents = Map.of();
        this.recoveryOtherShulkers = Map.of();
        this.recoverySourceAfter = null;
        this.hotbarEvacuation = null;
        this.kitWorksite = null;
        this.resumeState = null;
        this.resumeNotBeforeEpochMilli = 0;
        this.state = State.NEED_SHELL;
        if (!persist()) {
            this.state = State.IDLE;
            return false;
        }
        emit("kit_fulfillment_started", Map.of("kit", kitName, "requested_boxes", boxes));
        return true;
    }

    public void onRetrievalEvent(String event, Map<String, Object> payload) {
        if (state != State.WAIT_SHELL && state != State.WAIT_CARGO
                && state != State.VARIANT_WAIT_SOURCE) return;
        if (retrievalRequest == null || !retrievalRequest.equals(payload.get("request_name"))) return;
        trace(event, payload);
        if ("retrieve_borrowed_shell_placement_planned".equals(event)) {
            int[] planned = parsePosition(payload.get("placed_position"));
            String sourceId = payload.get("source_item_id") instanceof String id ? id : null;
            Map<String, Integer> sourceContents = parseContents(payload.get("source_contents"));
            if (planned == null || sourceId == null || sourceContents == null) {
                retriever.stop();
                block("borrowed_source_identity_unknown");
                return;
            }
            borrowedSourceWorksite = planned;
            borrowedSourceItemId = sourceId;
            borrowedSourceContents = sourceContents;
            if (!persist()) {
                retriever.stop();
                block("kit_checkpoint_write_failed");
            }
            return;
        }
        if ("retrieve_borrowed_shell_recovered".equals(event)) {
            int[] recovered = parsePosition(payload.get("placed_position"));
            if (recovered == null || !java.util.Arrays.equals(recovered, borrowedSourceWorksite)) {
                retriever.stop();
                block("borrowed_source_recovery_mismatch");
                return;
            }
            String sourceId = payload.get("source_item_id") instanceof String id ? id : null;
            Map<String, Integer> sourceAfter = parseContents(payload.get("source_contents_after"));
            int[] origin = parsePosition(payload.get("source_position"));
            if (sourceId == null || !sourceId.equals(borrowedSourceItemId)
                    || sourceAfter == null) {
                retriever.stop();
                block("borrowed_source_contents_unverified");
                return;
            }
            recoveredSourceItemId = sourceId;
            recoveredSourceContents = sourceAfter;
            sourceRecoveredDuringCurrentRetrieval = true;
            recoveredShulkersDuringCurrentRetrieval.merge(
                    new KitShulkerLedger.Identity(sourceId, sourceAfter), 1, Integer::sum);
            if (heldBoxRebase != null) {
                if (sourceBoxesToReturn.size() != 1
                        || !heldBoxRebase.sourceItemId().equals(sourceId)
                        || !Map.of(heldBoxRebase.itemId(),
                                heldBoxRebase.combinedSourceUnits()).equals(
                                sourceBoxesToReturn.getFirst().contents())
                        || !Map.of(heldBoxRebase.itemId(),
                                heldBoxRebase.combinedSourceUnits()
                                    - heldBoxRebase.retrievedUnits()).equals(sourceAfter)) {
                    retriever.stop();
                    block("held_box_recovered_balance_mismatch");
                    return;
                }
                var previous = sourceBoxesToReturn.getFirst();
                sourceBoxesToReturn.set(0, new KitFulfillmentJournalStore.BorrowedBox(
                        previous.sourcePosition(), sourceId, sourceAfter,
                        previous.returnPosition()));
            } else if (origin != null) {
                sourceBoxesToReturn.add(new KitFulfillmentJournalStore.BorrowedBox(
                        origin, sourceId, sourceAfter));
            } else {
                List<int[]> imports = KitSourceReturnPolicy.capacityImports(candidates,
                        registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                        null, exportPosition);
                int[] target = imports.isEmpty() ? null : imports.getFirst();
                // Inventory-borrowed boxes have no chest of origin. Journal their
                // exact post-extraction identity even when no import is available,
                // so the next admission cannot mistake them for foreign cargo.
                sourceBoxesToReturn.add(new KitFulfillmentJournalStore.BorrowedBox(
                        null, sourceId, sourceAfter, target));
                emit("kit_source_return_deferred", Map.of("reason", "inventory_source_origin_unknown",
                        "item_id", sourceId, "import_candidate", target == null
                                ? "none" : java.util.Arrays.toString(target)));
            }
            borrowedSourceWorksite = null;
            borrowedSourceItemId = null;
            borrowedSourceContents = Map.of();
            if (!persist()) {
                retriever.stop();
                block("kit_checkpoint_write_failed");
            }
            return;
        }
        switch (event) {
            case "retrieve_target_failed" -> {
                if (maySkipInaccessibleNamedAppleTarget(payload)) {
                    appleNamedInaccessibleTargetSkipped = true;
                    emit("kit_apple_named_inaccessible_source_skipped", Map.of(
                            "target_position", payload.get("target_position"),
                            "failure_reason", payload.get("failure_reason")));
                } else {
                    appleRetrievalHadTargetFailure = true;
                }
            }
            case "retrieve_completed" -> retrievalSucceeded = true;
            case "retrieve_incomplete", "retrieve_no_targets", "retrieve_stopped" -> {
                retrievalSucceeded = false;
                blocker = event + ":" + payload.getOrDefault("reason", "unknown");
            }
            default -> { }
        }
    }

    private boolean maySkipInaccessibleNamedAppleTarget(Map<String, Object> payload) {
        if (!ENCHANTED_APPLE.equals(currentItem) || tryUnnamedApples) return false;
        int[] target = parsePosition(payload.get("target_position"));
        if (target == null) return false;
        var block = World.getBlock(target[0], target[1], target[2]);
        var player = CACHE.getPlayerCache();
        boolean visible = ContainerAccessPlanner.visible(player.getX(), player.getY(),
                player.getZ(), target[0], target[1], target[2]);
        int moved = payload.get("target_moved_stacks") instanceof Number n ? n.intValue() : -1;
        boolean openAttempted = payload.get("target_open_attempted") instanceof Boolean value
                ? value : true;
        return KitItemSourcePolicy.NAMED_APPLES.maySkipInaccessibleSource(
                payload.get("failure_reason") instanceof String reason ? reason : null,
                block == null ? null : block.name(),
                BlockCompat.isSolid(target[0], target[1] + 1, target[2]),
                visible, moved, openAttempted);
    }

    public void tick() {
        if (state == State.VERIFY_RETURN) {
            tickVerifyCompletedReturn();
            return;
        }
        if (state == State.VERIFY_FAILED_RETURN) {
            tickVerifyFailedReturn();
            return;
        }
        switch (state) {
            case NEED_SHELL -> tickNeedShell();
            case WAIT_SHELL -> tickWaitShell();
            case NEED_CARGO -> tickNeedCargo();
            case WAIT_CARGO -> tickWaitCargo();
            case PACKING -> tickPacking();
            case VARIANT_NEED_SOURCE -> tickVariantNeedSource();
            case VARIANT_WAIT_SOURCE -> tickVariantWaitSource();
            case VARIANT_PACKING -> tickVariantPacking();
            case VARIANT_STAGING -> tickVariantStaging();
            case RETURNING_SOURCE -> tickReturningSource();
            case EXPORTING -> tickExporting();
            default -> { }
        }
    }

    private void tickVariantNeedSource() {
        KitFulfillmentJournalStore.Snapshot snapshot;
        try {
            snapshot = journal.load().orElse(null);
        } catch (IOException e) {
            block("kit_variant_checkpoint_unreadable"); return;
        }
        if (variantRepair == null || !verifiedVariantRepairBoundary(snapshot)
                || (!verifiedVariantPendingSources(snapshot)
                    && !(variantRepair.replacementOnly()
                        && snapshot.sourceBoxesToReturn().isEmpty()))) {
            block("kit_variant_boundary_changed"); return;
        }
        if (freeMainSlots() < 2 || !hotbarSlotsReady()) {
            block("kit_variant_inventory_headroom_missing"); return;
        }
        var cache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = cache.getMouseStack();
        if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)) {
            block("kit_variant_inventory_window_open"); return;
        }
        baselineSlots = occupiedSlots();
        lastBaselineMismatch = null;
        auditedShulkers = inventoryShulkerIdentities();
        sourceRecoveredDuringCurrentRetrieval = false;
        recoveredShulkersDuringCurrentRetrieval = new HashMap<>();
        recoveredSourceItemId = null;
        recoveredSourceContents = Map.of();
        retrievalRequest = requestName("variant_source");
        retrievalSucceeded = null;
        retrievalSettleTicks = 0;
        retrievalVerifiedTicks = 0;
        retrievalCloseTicks = 0;
        state = State.VARIANT_WAIT_SOURCE;
        if (!persist()) { block("kit_checkpoint_write_failed"); return; }
        if (!trace("kit_retrieval_baseline", Map.of())) {
            block("kit_provenance_baseline_write_failed"); return;
        }
        java.util.function.Predicate<ItemStack> filter = stack ->
                variantRepair.itemId().equals(ItemIdentifier.getItemId(stack))
                        && variantRepair.sourceHash().equals(
                                KitVariantFingerprint.variant(stack,
                                        variantRepair.fingerprintVersion()));
        if (!retriever.startKit(retrievalRequest,
                Map.of(variantRepair.itemId(), variantRepair.amount()), candidates,
                regionPos1, regionPos2, excludedPositions, false, null, null,
                -1, filter, false, null)) {
            block("kit_variant_source_retrieval_start_failed");
        }
    }

    private boolean packedStackCapacityRepairActive() {
        return variantRepair != null && variantRepair.replacementOnly()
                && variantRepair.cargoRawSlot() == -1
                && KitPackedStackCapacityRepair.matchesPackedBatch(
                    variantRepair.itemId(), currentItem, currentQuantity, recipe,
                    packedContents);
    }

    private void tickVariantWaitSource() {
        if (retrievalSucceeded == null) return;
        if (!retrievalSucceeded) {
            block("kit_variant_source_retrieval_incomplete"); return;
        }
        if (borrowedSourceWorksite != null) {
            block("kit_variant_source_box_not_recovered"); return;
        }
        if (!awaitRetrievalContainerClose()) return;
        int incomingRaw = uniqueVariantIncomingSlot();
        boolean packedStackCapacityRepair = packedStackCapacityRepairActive();
        if (incomingRaw < 9 || incomingRaw > 44
                || incomingRaw > 35 && !packedStackCapacityRepair) {
            retrievalVerifiedTicks = 0;
            if (++retrievalSettleTicks >= RETRIEVAL_SETTLE_TICKS)
                block("kit_variant_source_cargo_unverified");
            return;
        }
        if (++retrievalVerifiedTicks < RETRIEVAL_STABLE_TICKS) return;
        if (!variantBaselinePreserved()) {
            block("kit_variant_preexisting_inventory_changed"); return;
        }
        int observedShellSlot = KitShulkerLedger.ownedSlot(
                inventoryShulkerSlots(), ownedShellSlot, packedContents, null);
        if (observedShellSlot < 9) {
            block("kit_variant_owned_shell_not_unique"); return;
        }
        if (observedShellSlot != ownedShellSlot) {
            ownedShellSlot = observedShellSlot;
            if (!persist()) { block("kit_checkpoint_write_failed"); return; }
        }
        Container player = playerInventory();
        ItemStack shell = player == null ? null : player.getItemStack(ownedShellSlot);
        if (shell == null || !packedContents.equals(ItemIdentifier.readShulkerContents(shell))
                || !variantRepair.shellHash().equals(KitVariantFingerprint.shell(
                        ItemIdentifier.readShulkerPhysicalSlotStacks(shell),
                        variantRepair.fingerprintVersion()))) {
            block("kit_variant_owned_shell_changed"); return;
        }
        Map<Integer, ItemStack> shellSlots = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
        List<KitVariantExchangePlan.SlotStack> physical = shellSlots.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        boolean packedStackRepair = packedStackCapacityRepairActive();
        var step = variantRepair.replacementOnly() && !packedStackRepair
                ? KitCrystalNormalizationPlan.next(shellSlots) : null;
        var opportunity = packedStackRepair
                ? KitPackedStackCapacityRepair.opportunity(shellSlots,
                        variantRepair.itemId())
                : variantRepair.replacementOnly() ? null
                : KitVariantExchangePlan.shellOpportunity(physical,
                        variantRepair.itemId(), packedContents.getOrDefault(
                                variantRepair.itemId(), 0), KitCapacity.MAX_SLOTS
                                - (variantRepair.cargoRawSlot() == -1 ? 2 : 1));
        ItemStack incoming = player.getItemStack(incomingRaw);
        if ((variantRepair.replacementOnly() && !packedStackRepair
                    ? step == null || step.amount() != variantRepair.amount()
                        || step.outgoingSlot() != variantRepair.outgoingSlot()
                        || step.anchorSlot() != variantRepair.mergeTargetSlot()
                    : opportunity == null || opportunity.amount() != variantRepair.amount()
                        || opportunity.outgoingSlot() != variantRepair.outgoingSlot()
                        || opportunity.mergeTargetSlot() != variantRepair.mergeTargetSlot())
                || !variantRepair.sourceHash().equals(KitVariantFingerprint.variant(
                        incoming, variantRepair.fingerprintVersion()))) {
            block("kit_variant_source_mismatched_opportunity"); return;
        }
        var plan = variantRepair.replacementOnly() && !packedStackRepair
                ? new KitVariantExchangePlan.Plan(step.outgoingSlot(), step.outgoing(),
                        step.anchorSlot(), step.anchor(), -1, incoming,
                        step.amount(), 64, step.slotsAfter())
                : new KitVariantExchangePlan.Plan(opportunity.outgoingSlot(),
                        opportunity.outgoingStack(), opportunity.mergeTargetSlot(),
                        opportunity.mergeTargetStack(), -1, incoming, opportunity.amount(),
                        opportunity.maxStack(), opportunity.slotsAfter());
        Set<Integer> protectedSlots = new HashSet<>(occupiedSlots().keySet());
        protectedSlots.remove(ownedShellSlot);
        protectedSlots.remove(variantRepair.cargoRawSlot());
        if (variantRepair.cargoRawSlot() == -1 && !packedStackRepair) {
            if (!verifiedPinnedAppleBudgetCargo()) {
                block("kit_variant_audited_apple_cargo_changed"); return;
            }
            protectedSlots.removeAll(auditedCargoSlots.keySet());
        }
        protectedSlots.remove(incomingRaw);
        if (!packer.start(packedContents,
                Map.of(currentItem, packedStackRepair ? variantRepair.amount() : currentQuantity),
                protectedSlots, ownedShellSlot, null,
                new KitPacker.VariantExchange(plan, incomingRaw,
                        variantRepair.replacementOnly()))) {
            block("kit_variant_packer_start_failed:" + packer.failureReason()); return;
        }
        kitWorksite = packer.worksite();
        state = State.VARIANT_PACKING;
        if (!persist()) {
            packer.cancelBeforeWorldAction();
            block("kit_checkpoint_write_failed");
        }
    }

    private int uniqueVariantIncomingSlot() {
        Container player = playerInventory();
        if (player == null || variantRepair == null) return -1;
        int found = -1;
        int total = 0;
        for (int raw = 9; raw <= 44; raw++) {
            if (baselineSlots.containsKey(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            if (!variantRepair.itemId().equals(ItemIdentifier.getItemId(stack))
                    || !variantRepair.sourceHash().equals(
                            KitVariantFingerprint.variant(stack,
                                    variantRepair.fingerprintVersion()))) continue;
            if (found >= 0) return -1;
            found = raw;
            total += stack.getAmount();
        }
        if (found > 35 && !packedStackCapacityRepairActive())
            return -1;
        return total == variantRepair.amount() ? found : -1;
    }

    private boolean verifiedPinnedAppleBudgetCargo() {
        if (variantRepair == null || variantRepair.cargoRawSlot() != -1
                || auditedCargoSlots.size() != 2) return false;
        Container player = playerInventory();
        if (player == null) return false;
        Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0)
                stacks.put(raw, new KitCargoAudit.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
        }
        if (!KitCargoAudit.verifiedBatch(stacks, currentItem, currentQuantity,
                ownedShellSlot, auditedCargoSlots, true)) return false;
        for (int raw : auditedCargoSlots.keySet()) {
            if (!variantRepair.cargoHash().equals(KitVariantFingerprint.variant(
                    player.getItemStack(raw), variantRepair.fingerprintVersion()))) return false;
        }
        return true;
    }

    private boolean variantBaselinePreserved() {
        Map<Integer, KitInventoryBaseline.Stack> before = new HashMap<>();
        for (var entry : baselineSlots.entrySet())
            before.put(entry.getKey(), new KitInventoryBaseline.Stack(
                    entry.getValue().itemId(), entry.getValue().amount()));
        Map<Integer, KitInventoryBaseline.Stack> after = new HashMap<>();
        for (var entry : occupiedSlots().entrySet())
            after.put(entry.getKey(), new KitInventoryBaseline.Stack(
                    entry.getValue().itemId(), entry.getValue().amount()));
        if (!KitInventoryBaseline.compare(before, after, variantRepair.itemId(),
                variantRepair.amount()).preserved()) return false;
        return KitShulkerLedger.matchesAfterRetrieval(auditedShulkers,
                inventoryShulkerIdentities(), recoveredShulkersDuringCurrentRetrieval);
    }

    private void tickVariantPacking() {
        packer.tick();
        if (packer.state() == KitPacker.State.PLACING
                && (ownedShellSlot != packer.selectedShellSlot()
                    || !java.util.Arrays.equals(kitWorksite, packer.worksite()))) {
            ownedShellSlot = packer.selectedShellSlot();
            kitWorksite = packer.worksite();
            if (!persist()) {
                packer.cancelBeforeWorldAction();
                block("kit_checkpoint_write_failed");
            }
            return;
        }
        if (packer.state() == KitPacker.State.FAILED) {
            block("kit_variant_pack_failed:" + packer.failureReason()); return;
        }
        if (packer.state() != KitPacker.State.DONE) return;
        int raw = packer.variantDisplacedRaw();
        ItemStack displaced = packer.variantDisplacedStack();
        int recovered = packer.recoveredSlot();
        Container player = playerInventory();
        ItemStack shell = player == null || recovered < 9 ? null
                : player.getItemStack(recovered);
        ItemStack liveDisplaced = player == null || raw < 9 ? null
                : player.getItemStack(raw);
        ItemStack liveCargo = variantRepair == null || player == null ? null
                : player.getItemStack(variantRepair.cargoRawSlot());
        if (variantRepair != null && player != null
                && packedStackCapacityRepairActive()
                && (liveDisplaced == null
                    || !variantRepair.outgoingHash().equals(
                            KitVariantFingerprint.variant(liveDisplaced,
                                    variantRepair.fingerprintVersion())))) {
            // InventoryBehavior may move the displaced stack into the hotbar
            // after the shell GUI closes. Only a unique exact stack can replace
            // the packer's original destination as import evidence.
            int unique = -1;
            for (int candidate = 9; candidate <= 44; candidate++) {
                ItemStack stack = player.getItemStack(candidate);
                if (stack == null || stack.getAmount() != variantRepair.amount()
                        || !variantRepair.itemId().equals(ItemIdentifier.getItemId(stack))
                        || !variantRepair.outgoingHash().equals(
                                KitVariantFingerprint.variant(stack,
                                        variantRepair.fingerprintVersion()))) continue;
                if (unique >= 0) { unique = -1; break; }
                unique = candidate;
            }
            if (unique >= 9) {
                raw = unique;
                liveDisplaced = player.getItemStack(unique);
            }
        }
        if (variantRepair == null || !packer.variantRecoveredExact()
                || shell == null || !variantRepair.outgoingHash().equals(
                        KitVariantFingerprint.variant(liveDisplaced,
                                variantRepair.fingerprintVersion()))
                || displaced == null || liveDisplaced == null
                || liveDisplaced.getAmount() != variantRepair.amount()
                || (variantRepair.replacementOnly()
                    && !packedStackCapacityRepairActive()
                    && (liveCargo == null || liveCargo.getAmount() != 1
                        || !variantRepair.cargoHash().equals(KitVariantFingerprint.variant(
                                liveCargo, variantRepair.fingerprintVersion()))))
                || !variantRepair.outgoingHash().equals(
                        KitVariantFingerprint.variant(displaced,
                                variantRepair.fingerprintVersion()))) {
            block("kit_variant_packed_evidence_unverified"); return;
        }
        ownedShellSlot = recovered;
        kitWorksite = null;
        if (!variantRepair.replacementOnly()) {
            packedContents.merge(currentItem, currentQuantity, Integer::sum);
            if (variantRepair.cargoRawSlot() == -1) auditedCargoSlots = Map.of();
        }
        variantRepair = variantRepair.withResult(raw,
                KitVariantFingerprint.variant(liveDisplaced,
                        variantRepair.fingerprintVersion()),
                KitVariantFingerprint.shell(
                        ItemIdentifier.readShulkerPhysicalSlotStacks(shell),
                        variantRepair.fingerprintVersion()));
        state = State.VARIANT_STAGING;
        variantImportStarted = false;
        if (!persist()) block("kit_checkpoint_write_failed");
    }

    private void tickVariantStaging() {
        if (variantRepair == null || variantRepair.displacedRawSlot() < 9
                || variantRepair.resultShellHash() == null) {
            block("kit_variant_displacement_ledger_missing"); return;
        }
        if (!variantImportStarted) {
            Container player = playerInventory();
            ItemStack displaced = player == null ? null
                    : player.getItemStack(variantRepair.displacedRawSlot());
            ItemStack shell = player == null ? null : player.getItemStack(ownedShellSlot);
            if (displaced == null || displaced.getAmount() != variantRepair.amount()
                    || !variantRepair.displacedHash().equals(
                            KitVariantFingerprint.variant(displaced,
                                    variantRepair.fingerprintVersion()))
                    || shell == null || !packedContents.equals(
                            ItemIdentifier.readShulkerContents(shell))
                    || !variantRepair.resultShellHash().equals(KitVariantFingerprint.shell(
                            ItemIdentifier.readShulkerPhysicalSlotStacks(shell),
                            variantRepair.fingerprintVersion()))
                    || !KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(
                            candidates, registeredReturnChests, excludedPositions,
                            regionPos1, regionPos2, null, exportPosition,
                            variantRepair.importPosition())) {
                block("kit_variant_import_preflight_changed"); return;
            }
            if (!exporter.startLooseImport(variantRepair.importPosition(), displaced,
                    variantRepair.displacedRawSlot(),
                    variantRepair.expectedImportSlots())) {
                block("kit_variant_import_start_failed"); return;
            }
            variantImportStarted = true;
            return;
        }
        exporter.tick();
        if (exporter.state() == KitExporter.State.FAILED) {
            block("kit_variant_import_failed:" + exporter.failureReason()); return;
        }
        if (exporter.state() != KitExporter.State.DONE) return;
        Container player = playerInventory();
        ItemStack remaining = player == null ? null
                : player.getItemStack(variantRepair.displacedRawSlot());
        if (remaining != null && remaining.getAmount() > 0) {
            block("kit_variant_import_inventory_not_clear"); return;
        }
        var finished = variantRepair;
        variantRepair = null;
        if (finished.replacementOnly()) {
            ItemStack liveShell = player == null ? null : player.getItemStack(ownedShellSlot);
            Map<Integer, ItemStack> slots = ItemIdentifier.readShulkerPhysicalSlotStacks(liveShell);
            boolean packedStackRepair = finished.cargoRawSlot() == -1
                    && KitPackedStackCapacityRepair.matchesPackedBatch(
                        finished.itemId(), currentItem, currentQuantity, recipe,
                        packedContents);
            if (packedStackRepair) {
                if (slots.size() != KitPackedStackCapacityRepair.profile(
                        finished.itemId()).physicalSlots()
                        || !KitPhysicalSlotBudget.fitsAfterCompaction(
                        slots.size(), List.copyOf(slots.values()), recipe, packedContents)) {
                    variantRepair = finished;
                    block("kit_packed_stack_normalization_result_unverified"); return;
                }
            } else {
            var next = KitCrystalNormalizationPlan.next(slots);
            if (next != null && next.number() == 2 && finished.amount() == 18) {
                String shellHash = KitVariantFingerprint.canonicalShell(slots);
                String sourceHash = KitVariantFingerprint.canonicalVariant(next.anchor());
                String outgoingHash = KitVariantFingerprint.canonicalVariant(next.outgoing());
                if (shellHash == null || sourceHash == null || outgoingHash == null) {
                    block("kit_crystal_next_exchange_fingerprint_unavailable"); return;
                }
                variantRepair = new KitFulfillmentJournalStore.VariantRepair(
                        finished.importPosition(), finished.expectedImportSlots(),
                        finished.cargoRawSlot(), finished.cargoHash(), "end_crystal",
                        next.amount(), next.outgoingSlot(), next.anchorSlot(),
                        sourceHash, outgoingHash, shellHash, -1, null, null,
                        0, 2, true);
            } else if (!KitCrystalNormalizationPlan.normalized(slots)
                    || finished.amount() != 60) {
                block("kit_crystal_normalization_result_unverified"); return;
            }
            }
        }
        state = sourceBoxesToReturn.isEmpty()
                ? variantRepair == null ? State.NEED_CARGO : State.VARIANT_NEED_SOURCE
                : State.RETURNING_SOURCE;
        if (!persist()) {
            variantRepair = finished;
            block("kit_checkpoint_write_failed");
        } else {
            emit("kit_variant_repair_completed", Map.of("amount", finished.amount(),
                    "import", java.util.Arrays.toString(finished.importPosition())));
        }
    }

    private void tickNeedShell() {
        if (!admitInventory(Set.of())) return;
        if (!hotbarSlotsReady()) { block("kit_hotbar_slots_unavailable"); return; }
        if (retainedShell != null) {
            int reservedSlot = uniqueRetainedShellSlot(retainedShell, -1);
            if (reservedSlot < 0) { block("kit_retained_shell_identity_ambiguous"); return; }
            var consumed = retainedShell;
            ownedShellSlot = reservedSlot;
            retainedShell = null;
            state = State.NEED_CARGO;
            if (!persist()) {
                retainedShell = consumed;
                ownedShellSlot = -1;
                block("kit_checkpoint_write_failed");
            }
            return;
        }
        ownedShellSlot = findEmptyShellSlot(Set.of());
        if (ownedShellSlot >= 0) { state = State.NEED_CARGO; return; }
        emptyShellSlotsBefore = emptyShellSlots();
        retrievalRequest = requestName("shell");
        retrievalSucceeded = null;
        retrievalSettleTicks = 0;
        retrievalVerifiedTicks = 0;
        retrievalCloseTicks = 0;
        state = State.WAIT_SHELL;
        if (!persist()) { block("kit_checkpoint_write_failed"); return; }
        if (!trace("kit_retrieval_baseline", Map.of())) {
            block("kit_provenance_baseline_write_failed"); return;
        }
        if (!startRetrieval(Map.of(StashRetriever.EMPTY_SHELL_REQUEST, 1))) {
            block("empty_shell_retrieval_start_failed"); return;
        }
    }

    private void tickWaitShell() {
        if (retrievalSucceeded == null) return;
        if (!retrievalSucceeded) { block("empty_shell_retrieval_incomplete"); return; }
        if (borrowedSourceWorksite != null) { block("borrowed_source_shell_not_recovered"); return; }
        if (!awaitRetrievalContainerClose()) return;
        ownedShellSlot = findEmptyShellSlot(emptyShellSlotsBefore);
        // Retrieval completion can precede the server's inventory update by several ticks.
        if (ownedShellSlot < 0) {
            if (++retrievalSettleTicks >= RETRIEVAL_SETTLE_TICKS) {
                block("empty_shell_not_visible_after_retrieval");
            }
            return;
        }
        borrowedSourceWorksite = null;
        state = State.NEED_CARGO;
        if (!persist()) block("kit_checkpoint_write_failed");
    }

    /** A retriever completion can precede both the close action and the raw inventory sync. */
    private boolean awaitRetrievalContainerClose() {
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId <= 0) {
            retrievalCloseTicks = 0;
            return true;
        }
        if (++retrievalCloseTicks > RETRIEVAL_CLOSE_TIMEOUT_TICKS) {
            block("kit_source_container_close_timeout");
            return false;
        }
        if ((retrievalCloseTicks == 1 || retrievalCloseTicks % 10 == 0)
                && !INVENTORY.hasActiveRequest()) {
            try {
                INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(5000)
                        .actions(new CloseContainer(openId)).build());
            } catch (RuntimeException e) {
                emit("kit_source_container_close_rejected", Map.of(
                        "container_id", openId, "error", e.getClass().getSimpleName()));
            }
        }
        return false;
    }

    private void tickNeedCargo() {
        Container live = playerInventory();
        ItemStack owned = live == null || ownedShellSlot < 9 || ownedShellSlot > 44
                ? null : live.getItemStack(ownedShellSlot);
        if (!isShulker(owned) || owned.getAmount() != 1
                || !packedContents.equals(ItemIdentifier.readShulkerContents(owned))) {
            block("kit_owned_shell_identity_ambiguous"); return;
        }
        int physicalSlots = ItemIdentifier.readShulkerOccupiedSlots(owned);
        boolean fitsNow = KitPhysicalSlotBudget.fits(physicalSlots, recipe, packedContents);
        if (!KitPhysicalSlotBudget.fitsAfterCompaction(physicalSlots,
                ItemIdentifier.readShulkerPhysicalStacks(owned), recipe, packedContents)) {
            emit("kit_physical_slot_budget_exceeded", Map.of(
                    "physical_slots", physicalSlots,
                    "minimum_remaining_slots",
                    KitPhysicalSlotBudget.minimumRemaining(recipe, packedContents),
                    "kit", kitName, "box", deliveredBoxes() + 1));
            block("kit_physical_slot_budget_exceeded"); return;
        }
        if (!fitsNow) emit("kit_physical_slot_budget_compactable", Map.of(
                "physical_slots", physicalSlots,
                "minimum_remaining_slots",
                KitPhysicalSlotBudget.minimumRemaining(recipe, packedContents),
                "kit", kitName, "box", deliveredBoxes() + 1));
        Set<Integer> ownedSlots = new HashSet<>();
        ownedSlots.add(ownedShellSlot);
        if (recoveryInventorySourceSlot >= 0) {
            if (!matchesRecoveryInventorySource()) {
                block("borrowed_inventory_source_changed"); return;
            }
            ownedSlots.add(recoveryInventorySourceSlot);
        }
        if (!admitInventory(ownedSlots)) return;
        if (packedContents.equals(recipe) || !currentBoxMissing.isEmpty()) {
            if (!currentBoxMissing.isEmpty()
                    && !currentBoxMissing.equals(missingFromRecipe())) {
                block("kit_incomplete_shortfall_changed"); return;
            }
            state = State.EXPORTING;
            if (!persist()) { block("kit_checkpoint_write_failed"); return; }
            boolean workingStockRepack = kitName != null
                    && kitName.startsWith("@stash-working-repack:");
            boolean exportStarted = workingStockRepack
                    ? exporter.start(exportPosition, packedContents, ownedShellSlot,
                            null, true, 54, true)
                    : exporter.start(exportPosition, packedContents, ownedShellSlot);
            if (!exportStarted) {
                block("kit_export_start_failed"); return;
            }
            return;
        }
        // A witnessed carried-box recovery must finish its already selected task.
        // Map iteration order is not a stable way to reconstruct that task after
        // a restart, and selecting another recipe item would strand the source.
        String nextItem = heldBoxRebase == null ? nextUnpackedItem() : currentItem;
        if (nextItem == null || !recipe.containsKey(nextItem)) {
            if (appleStockExhaustedForBox && markCurrentBoxIncomplete()) return;
            block("kit_recipe_progress_mismatch"); return;
        }
        if (recoveryInventorySourceSlot >= 0 && !nextItem.equals(currentItem)) {
            block("borrowed_inventory_source_changed"); return;
        }
        currentItem = nextItem;
        int remaining = recipe.get(currentItem) - packedContents.getOrDefault(currentItem, 0);
        if (ENCHANTED_APPLE.equals(currentItem)) {
            Container player = playerInventory();
            ItemStack shell = player == null || ownedShellSlot < 9 || ownedShellSlot > 44
                    ? null : player.getItemStack(ownedShellSlot);
            if (shell == null || !packedContents.equals(ItemIdentifier.readShulkerContents(shell))) {
                block("kit_owned_shell_identity_ambiguous"); return;
            }
            var applePlan = KitAppleExchangePlan.choose(
                    ItemIdentifier.readShulkerPhysicalStacks(shell), List.of(),
                    recipe.get(ENCHANTED_APPLE),
                    KitCapacity.requiredSlots(Map.of(ENCHANTED_APPLE, recipe.get(ENCHANTED_APPLE))));
            appleCargoExemplar = tryUnnamedApples ? null : applePlan.exemplar();
        } else {
            appleCargoExemplar = null;
            tryUnnamedApples = false;
        }
        if (recoveryInventorySourceSlot < 0) currentQuantity = 0;
        if (recoveryInventorySourceSlot >= 0
                && (!currentItem.equals(recoveryExpectedItem)
                    || currentQuantity <= 0 || currentQuantity > remaining
                    || recoveryInventorySourceContents.getOrDefault(currentItem, 0) < currentQuantity
                    || !matchesRecoveryInventorySource())) {
            block("borrowed_inventory_source_changed"); return;
        }
        if (!ensureHotbarRoom()) return;
        if (recoveryInventorySourceSlot < 0) {
            currentQuantity = ENCHANTED_APPLE.equals(currentItem) && tryUnnamedApples
                    ? recipe.get(ENCHANTED_APPLE)
                    : KitFulfillmentPlan.nextBatch(
                            Map.of(currentItem, remaining), freeMainSlots())
                            .getOrDefault(currentItem, 0);
        }
        // ensureHotbarRoom reserved a hotbar slot for a borrowed source box. The
        // retriever moves that box out of main inventory before taking its cargo,
        // then picks it up into the vacated hotbar slot. Reserving another main
        // slot here strands a kit when kept supplies leave only one main slot.
        int sourceReserve = 0;
        if (ENCHANTED_APPLE.equals(currentItem) && tryUnnamedApples) {
            // The old named stacks must have separate empty player slots during exchange.
            Container player = playerInventory();
            ItemStack shell = player == null ? null : player.getItemStack(ownedShellSlot);
            sourceReserve += (int) ItemIdentifier.readShulkerPhysicalStacks(shell).stream()
                    .filter(stack -> ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack)))
                    .count();
        }
        if (currentQuantity <= 0 || freeMainSlots() <
                KitCapacity.requiredSlots(Map.of(currentItem, currentQuantity)) + sourceReserve) {
            block("kit_main_inventory_batch_headroom_insufficient"); return;
        }
        baselineSlots = occupiedSlots();
        auditedShulkers = inventoryShulkerIdentities();
        sourceRecoveredDuringCurrentRetrieval = false;
        recoveredShulkersDuringCurrentRetrieval = new HashMap<>();
        if (recoveryInventorySourceSlot >= 0) captureRecoveryShulkerBaseline();
        if (unownedUnitsOf(currentItem, baselineSlots.keySet()) > 0) {
            block("kit_cargo_already_in_inventory"); return;
        }
        retrievalRequest = requestName(currentItem);
        retrievalSucceeded = null;
        retrievalSettleTicks = 0;
        retrievalVerifiedTicks = 0;
        retrievalCloseTicks = 0;
        appleRetrievalHadTargetFailure = false;
        appleNamedInaccessibleTargetSkipped = false;
        state = State.WAIT_CARGO;
        if (!persist()) { block("kit_checkpoint_write_failed"); return; }
        if (!trace("kit_retrieval_baseline", Map.of())) {
            block("kit_provenance_baseline_write_failed"); return;
        }
        if (!startRetrieval(Map.of(currentItem, currentQuantity))) {
            if (appleStockExhaustedWithoutCargo()) return;
            block("kit_cargo_retrieval_start_failed"); return;
        }
    }

    private boolean admitInventory(Set<Integer> ownedSlots) {
        if (inventoryKeepRules == null) {
            block("kit_inventory_keep_policy_unavailable");
            return false;
        }
        if (!verifiedDisplacedAppleSlots(displacedAppleSlots)) {
            block("kit_displaced_apple_slots_changed");
            return false;
        }
        Set<Integer> admitted = new HashSet<>(ownedSlots);
        admitted.addAll(displacedAppleSlots.keySet());
        if (retainedShell != null) {
            int reservedSlot = uniqueRetainedShellSlot(retainedShell, ownedShellSlot);
            if (reservedSlot < 0) {
                block("kit_retained_shell_identity_ambiguous");
                return false;
            }
            admitted.add(reservedSlot);
        }
        var result = KitInventoryAdmissionPolicy.inspectLive(inventoryKeepRules, admitted);
        if (result == null) {
            block("kit_inventory_unavailable_for_admission");
            return false;
        }
        if (result.accepted()) return true;
        emit("kit_inventory_admission_rejected", Map.of(
                "unexpected_slots", result.unexpectedSlots(),
                "phase", state.name()));
        block("kit_inventory_unexpected_items");
        return false;
    }

    public static boolean verifiedDisplacedAppleSlots(
            Map<Integer, KitInventoryAppleOwnership.Slot> slots) {
        Container player = playerInventory();
        if (slots == null || player == null) return false;
        Map<Integer, ItemStack> live = new HashMap<>();
        for (int raw : slots.keySet()) {
            if (raw < 9 || raw > 44) return false;
            live.put(raw, player.getItemStack(raw));
        }
        return KitInventoryAppleOwnership.matches(slots, live);
    }

    /** Only the physical, recipe-matching shell may be exported short of supplies. */
    boolean markCurrentBoxIncomplete() {
        if (state != State.NEED_CARGO || !sourceBoxesToReturn.isEmpty()
                || packer.isActive() || exporter.isActive() || retriever.isActive()
                || kitWorksite != null || borrowedSourceWorksite != null
                || !currentBoxMissing.isEmpty()) return false;
        var inventory = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = inventory.getMouseStack();
        if (inventory.getOpenContainerId() > 0
                || (cursor != null && cursor.getAmount() > 0)) return false;
        Map<String, Integer> missing = missingFromRecipe();
        if (missing.size() != 1 || !missing.containsKey("enchanted_golden_apple")) return false;
        Container player = playerInventory();
        if (player == null || ownedShellSlot < 9 || ownedShellSlot > 44) return false;
        ItemStack shell = player.getItemStack(ownedShellSlot);
        if (!isShulker(shell) || shell.getAmount() != 1
                || !packedContents.equals(ItemIdentifier.readShulkerContents(shell))) return false;
        currentBoxMissing.putAll(missing);
        if (!persist()) {
            currentBoxMissing.clear();
            block("kit_checkpoint_write_failed");
            return false;
        }
        emit("kit_fulfillment_box_marked_incomplete", Map.of(
                "kit", kitName, "box", deliveredBoxes() + 1,
                "missing_by_item", Map.copyOf(currentBoxMissing)));
        return true;
    }

    private Map<String, Integer> missingFromRecipe() {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (var item : recipe.entrySet()) {
            int shortfall = item.getValue() - packedContents.getOrDefault(item.getKey(), 0);
            if (shortfall > 0) missing.put(item.getKey(), shortfall);
        }
        return missing;
    }

    /** A full physical shell may need one mismatched apple variant displaced first. */
    private KitPacker.AppleExchange appleExchangeForCargo(ItemStack shell) {
        pendingAppleExchangeRemoval = 0;
        if (shell == null || !packedContents.equals(ItemIdentifier.readShulkerContents(shell))) {
            pendingAppleExchangeRemoval = -1;
            return null;
        }
        List<ItemStack> physical = ItemIdentifier.readShulkerPhysicalStacks(shell);
        int required = recipe.getOrDefault(ENCHANTED_APPLE, 0);
        if (required <= 0) return null;
        ItemStack exemplar;
        int remove;
        if (ENCHANTED_APPLE.equals(currentItem) && tryUnnamedApples) {
            exemplar = appleCargoExemplar;
            remove = packedContents.getOrDefault(ENCHANTED_APPLE, 0);
            if (exemplar == null || KitAppleExchangePlan.named(exemplar)) {
                pendingAppleExchangeRemoval = -1;
                return null;
            }
        } else if (!ENCHANTED_APPLE.equals(currentItem)
                && ItemIdentifier.readShulkerOccupiedSlots(shell) >= KitCapacity.MAX_SLOTS
                && KitCapacity.requiredSlots(Map.of(currentItem, currentQuantity)) > 0) {
            int appleSlots = KitCapacity.requiredSlots(Map.of(ENCHANTED_APPLE, required));
            long physicalAppleSlots = physical.stream()
                    .filter(stack -> ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack)))
                    .count();
            if (physicalAppleSlots <= appleSlots) return null;
            var plan = KitAppleExchangePlan.choose(physical, List.of(), required, appleSlots);
            exemplar = plan.exemplar();
            remove = plan.toRemove();
            if (remove > 0 && (exemplar == null || plan.retained() == 0)) {
                pendingAppleExchangeRemoval = -1;
                return null;
            }
        } else {
            return null;
        }
        if (remove <= 0) return null;
        int displacedStacks = (int) physical.stream()
                .filter(stack -> ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack))
                        && !KitAppleExchangePlan.samePhysicalStack(stack, exemplar)).count();
        int displacedUnits = physical.stream()
                .filter(stack -> ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack))
                        && !KitAppleExchangePlan.samePhysicalStack(stack, exemplar))
                .mapToInt(ItemStack::getAmount).sum();
        if (displacedUnits != remove || displacedStacks == 0
                || freeMainSlots() < displacedStacks) {
            pendingAppleExchangeRemoval = -1;
            return null;
        }
        pendingAppleExchangeRemoval = remove;
        return new KitPacker.AppleExchange(exemplar, remove);
    }

    private boolean exactUnownedAppleCargo(Set<Integer> baseline, ItemStack exemplar,
                                           int expected) {
        if (exemplar == null) return false;
        Container player = playerInventory();
        if (player == null) return false;
        int total = 0;
        for (int raw = 9; raw <= 44; raw++) {
            if (baseline.contains(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (!ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack))) continue;
            if (!KitAppleExchangePlan.samePhysicalStack(stack, exemplar)) return false;
            total += stack.getAmount();
        }
        return total == expected;
    }

    private static ItemStack auditedAppleExemplar(KitFulfillmentJournalStore.Snapshot snapshot,
                                                  int singleSlot, boolean allowMixed) {
        Container player = playerInventory();
        if (player == null) return null;
        Set<Integer> slots = snapshot.auditedCargoSlots().isEmpty()
                ? Set.of(singleSlot) : snapshot.auditedCargoSlots().keySet();
        ItemStack exemplar = null;
        int total = 0;
        for (int raw : slots) {
            if (raw < 9 || raw > 44) return null;
            ItemStack stack = player.getItemStack(raw);
            if (!ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack))
                    || (!allowMixed && exemplar != null
                        && !KitAppleExchangePlan.samePhysicalStack(exemplar, stack))) return null;
            if (exemplar == null) exemplar = stack;
            total += stack.getAmount();
        }
        return total == snapshot.currentQuantity() ? exemplar : null;
    }

    /** Mixed named apples are safe only if their physical stacks fit this recipe's apple slots. */
    public static boolean auditedAppleFitsSlots(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || !ENCHANTED_APPLE.equals(snapshot.currentItem())
                || snapshot.packedContents().getOrDefault(ENCHANTED_APPLE, 0) != 0) return false;
        Container player = playerInventory();
        if (player == null) return false;
        List<ItemStack> selected = new ArrayList<>();
        for (int raw : snapshot.auditedCargoSlots().keySet()) {
            if (raw < 9 || raw > 44) return false;
            selected.add(player.getItemStack(raw));
        }
        int appleSlots = KitCapacity.requiredSlots(Map.of(ENCHANTED_APPLE,
                snapshot.recipe().getOrDefault(ENCHANTED_APPLE, 0)));
        return KitAppleExchangePlan.fitsPhysicalSlots(selected,
                snapshot.currentQuantity(), appleSlots);
    }

    private boolean exactAuditedKeptAppleCargo() {
        Container player = playerInventory();
        if (player == null) return false;
        List<ItemStack> selected = new ArrayList<>();
        for (int raw = 9; raw <= 44; raw++) {
            if (baselineSlots.containsKey(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && ENCHANTED_APPLE.equals(ItemIdentifier.getItemId(stack))) {
                selected.add(stack);
            }
        }
        return KitAppleExchangePlan.fitsPhysicalSlots(selected, currentQuantity,
                KitCapacity.requiredSlots(Map.of(ENCHANTED_APPLE,
                        recipe.getOrDefault(ENCHANTED_APPLE, 0))));
    }

    private void tickWaitCargo() {
        if (retrievalSucceeded == null) return;
        if (!retrievalSucceeded) {
            int partial = unownedUnitsOf(currentItem, baselineSlots.keySet());
            if (ENCHANTED_APPLE.equals(currentItem) && !tryUnnamedApples
                    && !appleRetrievalHadTargetFailure && borrowedSourceWorksite == null
                    && blocker != null && blocker.endsWith(":no_more_targets")
                    && partial > 0 && partial < currentQuantity
                    && exactUnownedAppleCargo(baselineSlots.keySet(), appleCargoExemplar, partial)) {
                currentQuantity = partial;
                retrievalSucceeded = true;
                blocker = null;
                if (!persist()) { block("kit_checkpoint_write_failed"); return; }
                emit("kit_apple_partial_named_batch_verified", Map.of(
                        "kit", kitName, "box", deliveredBoxes() + 1,
                        "received", partial));
            } else {
                if (appleStockExhaustedWithoutCargo()) return;
                block("kit_cargo_retrieval_incomplete"); return;
            }
        }
        if (borrowedSourceWorksite != null) { block("borrowed_source_shell_not_recovered"); return; }
        if (!awaitRetrievalContainerClose()) return;
        var incoming = incomingCargo(currentItem, baselineSlots);
        int received = incoming.units();
        if (received != currentQuantity) {
            retrievalVerifiedTicks = 0;
            if (++retrievalSettleTicks >= RETRIEVAL_SETTLE_TICKS) {
                emit("kit_retrieved_cargo_mismatch", Map.of(
                        "item", currentItem,
                        "expected", currentQuantity,
                        "unowned", received,
                        "baseline_occupied_slots", baselineSlots.keySet().toString(),
                        "baseline_item_slots", itemSlotCounts(currentItem, baselineSlots),
                        "live_item_slots", liveItemSlotCounts(currentItem)));
                block("kit_retrieved_cargo_unverified:" + received + "/" + currentQuantity);
            }
            return;
        }
        if (++retrievalVerifiedTicks < RETRIEVAL_STABLE_TICKS) return;
        if (protectedLoose != null) {
            Container player = playerInventory();
            Map<Integer, ItemStack> live = new HashMap<>();
            if (player != null) for (int raw = 9; raw <= 44; raw++)
                live.put(raw, player.getItemStack(raw));
            if (!KitPartialBorrowedCargoGuard.verifiedActive(
                    protectedLoose, auditedCargoSlots, live)) {
                block("kit_protected_loose_changed"); return;
            }
        }
        if (!baselineStillPresent()) { block("kit_preexisting_inventory_changed"); return; }
        for (KitShulkerLedger.Identity source : recoveredShulkersDuringCurrentRetrieval.keySet()) {
            if (source.contents().equals(packedContents)) {
                block("kit_owned_shell_identity_ambiguous"); return;
            }
        }
        KitShulkerLedger.Identity recovered = sourceRecoveredDuringCurrentRetrieval
                ? new KitShulkerLedger.Identity(recoveredSourceItemId, recoveredSourceContents)
                : null;
        int verifiedShellSlot = KitShulkerLedger.ownedSlot(inventoryShulkerSlots(),
                ownedShellSlot, packedContents, recovered);
        if (verifiedShellSlot < 0) { block("kit_owned_shell_identity_ambiguous"); return; }
        ownedShellSlot = verifiedShellSlot;
        if (heldBoxRebase != null) {
            Container player = playerInventory();
            Map<Integer, ItemStack> live = new HashMap<>();
            if (player != null) for (int raw = 9; raw <= 44; raw++)
                live.put(raw, player.getItemStack(raw));
            if (KitHeldBorrowedBoxPolicy.observe(
                    KitHeldBorrowedBoxPolicy.fromWitness(heldBoxRebase, packedContents), live,
                    ownedShellSlot) != KitHeldBorrowedBoxPolicy.Phase.TASK_EXTRACTED) {
                block("held_box_task_extraction_unverified"); return;
            }
        }
        Set<Integer> protectedSlots = new HashSet<>(baselineSlots.keySet());
        protectedSlots.addAll(incoming.relocatedBaselineSlots());
        protectedSlots.removeAll(incoming.slots());
        protectedSlots.remove(ownedShellSlot);
        protectedSlots.addAll(inventoryShulkerSlots().keySet());
        protectedSlots.remove(ownedShellSlot);
        Container player = playerInventory();
        ItemStack shell = player == null ? null : player.getItemStack(ownedShellSlot);
        KitPacker.AppleExchange appleExchange = appleExchangeForCargo(shell);
        if (pendingAppleExchangeRemoval < 0) {
            pendingAppleExchangeRemoval = 0;
            block("kit_apple_exchange_preflight_failed"); return;
        }
        if (ENCHANTED_APPLE.equals(currentItem)
                && !(auditedKeptAppleCargo ? exactAuditedKeptAppleCargo()
                        : exactUnownedAppleCargo(protectedSlots, appleCargoExemplar,
                                currentQuantity))) {
            block("kit_apple_cargo_components_unverified"); return;
        }
        if (!packer.start(packedContents, Map.of(currentItem, currentQuantity),
                protectedSlots, ownedShellSlot, appleExchange)) {
            block("kit_packer_start_failed"); return;
        }
        kitWorksite = packer.worksite();
        recoveryInventorySourceSlot = -1;
        recoveryExpectedItem = null;
        recoveryOtherShulkers = Map.of();
        recoverySourceAfter = null;
        auditedShulkers = Map.of();
        sourceRecoveredDuringCurrentRetrieval = false;
        recoveredShulkersDuringCurrentRetrieval = new HashMap<>();
        state = State.PACKING;
        if (!persist()) {
            packer.cancelBeforeWorldAction();
            block("kit_checkpoint_write_failed");
        }
    }

    private void tickPacking() {
        packer.tick();
        if (packer.state() == KitPacker.State.PLACING
                && (ownedShellSlot != packer.selectedShellSlot()
                    || !java.util.Arrays.equals(kitWorksite, packer.worksite()))) {
            ownedShellSlot = packer.selectedShellSlot();
            kitWorksite = packer.worksite();
            if (!persist()) {
                packer.cancelBeforeWorldAction();
                block("kit_checkpoint_write_failed");
            }
            return;
        }
        if (packer.state() == KitPacker.State.FAILED) {
            block("kit_pack_failed:" + packer.failureReason());
        } else if (packer.state() == KitPacker.State.DONE) {
            if (pendingAppleExchangeRemoval > 0) {
                var removedSlots = packer.displacedAppleSlots();
                int verifiedUnits = removedSlots.values().stream()
                        .mapToInt(KitInventoryAppleOwnership.Slot::amount).sum();
                if (verifiedUnits != pendingAppleExchangeRemoval
                        || removedSlots.keySet().stream().anyMatch(displacedAppleSlots::containsKey)
                        || !verifiedDisplacedAppleSlots(removedSlots)) {
                    block("kit_displaced_apple_ownership_unverified");
                    return;
                }
                displacedAppleSlots.putAll(removedSlots);
                packedContents.computeIfPresent(ENCHANTED_APPLE,
                        (item, amount) -> amount - pendingAppleExchangeRemoval);
                packedContents.values().removeIf(amount -> amount <= 0);
                emit("kit_apple_exchange_displaced", Map.of(
                        "kit", kitName, "box", deliveredBoxes() + 1,
                        "preserved_in_inventory", pendingAppleExchangeRemoval));
                pendingAppleExchangeRemoval = 0;
            }
            packedContents.merge(currentItem, currentQuantity, Integer::sum);
            auditedKeptAppleCargo = false;
            auditedCargoSlot = -1;
            auditedCargoSlots = Map.of();
            protectedLoose = null;
            heldBoxRebase = null;
            ownedShellSlot = packer.recoveredSlot();
            if (ownedShellSlot < 0) { block("packed_kit_shell_slot_unverified"); return; }
            kitWorksite = null;
            emit("kit_fulfillment_batch_packed", Map.of(
                    "kit", kitName, "item", currentItem,
                    "packed_types", packedContents.size(), "recipe_types", recipe.size()));
            retainOneEmptySourceForNextBox();
            state = sourceBoxesToReturn.isEmpty() ? State.NEED_CARGO : State.RETURNING_SOURCE;
            if (!persist()) block("kit_checkpoint_write_failed");
        }
    }

    private void retainOneEmptySourceForNextBox() {
        if (returnAuditedEmptySource) {
            returnAuditedEmptySource = false;
            return;
        }
        if (deliveredBoxes() + 1 >= requestedBoxes || retainedShell != null) return;
        int pendingEmpty = (int) sourceBoxesToReturn.stream()
                .filter(box -> box.contents().isEmpty()).count();
        if (emptyShellSlots().size() > pendingEmpty) return;
        for (int i = 0; i < sourceBoxesToReturn.size(); i++) {
            var source = sourceBoxesToReturn.get(i);
            if (!source.contents().isEmpty()) continue;
            int slot = findBorrowedSourceSlot(source);
            if (slot < 0) continue;
            var evidence = KitRetainedShell.evidence(slot, playerInventory().getItemStack(slot));
            if (evidence == null || uniqueRetainedShellSlot(evidence, ownedShellSlot) != slot)
                continue;
            retainedShell = evidence;
            sourceBoxesToReturn.remove(i);
            emit("kit_empty_source_recycled", Map.of(
                    "disposition", "retain_for_next_kit_box", "item_id", source.itemId()));
            return;
        }
    }

    private void tickReturningSource() {
        // A transient inventory observation must never permit the borrowed source
        // to leave while the newly packed kit shell is no longer verifiable.
        KitShulkerLedger.Identity returning = sourceBoxesToReturn.isEmpty() ? null
                : new KitShulkerLedger.Identity(sourceBoxesToReturn.getFirst().itemId(),
                        sourceBoxesToReturn.getFirst().contents());
        int verifiedShellSlot = KitShulkerLedger.ownedSlot(inventoryShulkerSlots(),
                ownedShellSlot, packedContents, returning);
        if (verifiedShellSlot < 0) {
            block("kit_owned_shell_missing_before_source_return");
            return;
        }
        if (verifiedShellSlot != ownedShellSlot) {
            ownedShellSlot = verifiedShellSlot;
            if (!persist()) { block("kit_checkpoint_write_failed"); return; }
        }
        if (sourceBoxesToReturn.isEmpty()) {
            if (variantRepair != null && variantRepair.replacementOnly()) {
                state = State.VARIANT_NEED_SOURCE;
                if (!persist()) block("kit_checkpoint_write_failed");
            } else if (crystalCargoReadyForAudit()) {
                block("recovered_box_requires_cargo_audit");
            } else {
                state = State.NEED_CARGO;
                if (!persist()) block("kit_checkpoint_write_failed");
            }
            return;
        }
        var source = sourceBoxesToReturn.getFirst();
        if (!sourceReturnStarted) {
            if (source.targetPosition() == null) {
                block("kit_source_return_no_capacity_import");
                return;
            }
            var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            if (inventoryCache.getOpenContainerId() > 0 || INVENTORY.hasActiveRequest()) {
                if (++sourceReturnCloseWaitTicks > 120) block("kit_source_return_close_unverified");
                return;
            }
            sourceReturnCloseWaitTicks = 0;
            int sourceSlot = findBorrowedSourceSlot(source);
            if (sourceSlot < 0) { block("kit_source_return_identity_unverified"); return; }
            boolean requireImportCapacity = sourceReturnOriginalAudit
                    || source.returnPosition() != null
                    && !java.util.Arrays.equals(source.returnPosition(), source.sourcePosition());
            int expectedReturnSlots = 0;
            if (requireImportCapacity) {
                ContainerEntry targetEntry = candidates.stream()
                        .filter(entry -> java.util.Arrays.equals(
                                new int[]{entry.x(), entry.y(), entry.z()}, source.targetPosition()))
                        .findFirst().orElse(null);
                if (targetEntry == null || !targetEntry.inventoryFootprintKnown()) {
                    block("kit_source_return_target_footprint_unknown");
                    return;
                }
                expectedReturnSlots = targetEntry.isDouble() ? 54 : 27;
            }
            if (!exporter.start(source.targetPosition(), source.contents(),
                    sourceSlot, source.itemId(), requireImportCapacity, expectedReturnSlots)) {
                block("kit_source_return_start_failed");
                return;
            }
            sourceReturnStarted = true;
            emit("kit_source_return_started", Map.of("item_id", source.itemId(),
                    "empty", source.contents().isEmpty()));
            return;
        }
        exporter.tick();
        if (exporter.state() == KitExporter.State.FAILED) {
            if (sourceReturnOriginalAudit) {
                block("kit_original_source_return_failed:" + exporter.failureReason());
                return;
            }
            if ("export_chest_full".equals(exporter.failureReason())
                    || "export_target_footprint_changed".equals(exporter.failureReason())
                    || "wrong_export_container_opened".equals(exporter.failureReason())) {
                if (!selectNextReturnTarget("return_" + exporter.failureReason())) {
                    block("kit_source_return_no_capacity_import");
                    return;
                }
                sourceReturnStarted = false;
                sourceReturnCloseWaitTicks = 0;
                if (!persist()) block("kit_checkpoint_write_failed");
            } else {
                block("kit_source_return_failed:" + exporter.failureReason());
            }
        } else if (exporter.state() == KitExporter.State.DONE) {
            int[] returnedTo = source.targetPosition();
            trace("kit_source_return_box_verified", Map.of(
                    "source_item_id", source.itemId(),
                    "source_contents_after", source.contents(),
                    "source_position", source.sourcePosition() == null ? "inventory"
                            : source.sourcePosition()[0] + ", " + source.sourcePosition()[1]
                                + ", " + source.sourcePosition()[2],
                    "return_position", returnedTo == null ? "unknown"
                            : returnedTo[0] + ", " + returnedTo[1] + ", " + returnedTo[2]));
            sourceBoxesToReturn.removeFirst();
            sourceReturnStarted = false;
            sourceReturnOriginalAudit = false;
            emit("kit_source_return_verified", Map.of("item_id", source.itemId(),
                    "empty", source.contents().isEmpty(),
                    "remaining_returns", sourceBoxesToReturn.size()));
            if (sourceBoxesToReturn.isEmpty()) {
                recoveredSourceItemId = null;
                recoveredSourceContents = Map.of();
            }
            state = sourceBoxesToReturn.isEmpty()
                    ? variantRepair != null && variantRepair.replacementOnly()
                        ? State.VARIANT_NEED_SOURCE
                        : crystalCargoReadyForAudit()
                            ? State.RETURNING_SOURCE : State.NEED_CARGO
                    : State.RETURNING_SOURCE;
            if (!persist()) block("kit_checkpoint_write_failed");
        }
    }

    private boolean crystalCargoReadyForAudit() {
        if (variantRepair != null || !sourceBoxesToReturn.isEmpty()
                || currentItem == null
                || !"diamond_chestplate".equals(ItemIdentifier.baseItemId(currentItem))
                || currentQuantity != 1 || packedContents.containsKey(currentItem)
                || packedContents.getOrDefault("end_crystal", 0) != 256)
            return false;
        Container player = playerInventory();
        ItemStack shell = player == null ? null : player.getItemStack(ownedShellSlot);
        return shell != null && KitCrystalNormalizationPlan.normalized(
                ItemIdentifier.readShulkerPhysicalSlotStacks(shell));
    }

    /** A submitted click did not move the box. Reopen the original source twice,
     * prove it is full and lacks the exact box, then allow a capacity-checked import. */
    private void tickVerifyFailedReturn() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
            exporter.abortForRecovery("failed_return_audit_interrupted");
            block("kit_failed_return_audit_interrupted");
            return;
        }
        if (sourceBoxesToReturn.size() != 1
                || sourceBoxesToReturn.getFirst().sourcePosition() == null
                || sourceBoxesToReturn.getFirst().returnPosition() == null) {
            block("kit_failed_return_obligation_changed");
            return;
        }
        var source = sourceBoxesToReturn.getFirst();
        var sourceIdentity = new KitShulkerLedger.Identity(source.itemId(), source.contents());
        if (KitShulkerLedger.ownedSlot(inventoryShulkerSlots(), ownedShellSlot,
                packedContents, sourceIdentity) != ownedShellSlot
                || findBorrowedSourceSlot(source) < 0) {
            exporter.abortForRecovery("failed_return_audit_inventory_changed");
            block("kit_failed_return_inventory_changed");
            return;
        }
        if (!sourceReturnStarted) {
            var cache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = cache.getMouseStack();
            if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)
                    || INVENTORY.hasActiveRequest()) {
                block("kit_failed_return_window_not_clear");
                return;
            }
            ContainerEntry original = null;
            for (ContainerEntry entry : candidates) {
                if (java.util.Arrays.equals(new int[]{entry.x(), entry.y(), entry.z()},
                        source.sourcePosition())) {
                    original = entry;
                    break;
                }
            }
            if (original == null || !original.inventoryFootprintKnown()
                    || !exporter.startVerifyFailedReturn(source.sourcePosition(),
                            source.itemId(), source.contents(), original.isDouble() ? 54 : 27)) {
                block("kit_failed_return_audit_start_failed");
                return;
            }
            sourceReturnStarted = true;
            emit("kit_failed_return_audit_started", Map.of("item_id", source.itemId()));
            return;
        }
        exporter.tick();
        if (exporter.state() == KitExporter.State.FAILED) {
            block("kit_failed_return_audit_failed:" + exporter.failureReason());
            return;
        }
        if (exporter.state() != KitExporter.State.DONE) return;
        var cache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = cache.getMouseStack();
        if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)
                || findBorrowedSourceSlot(source) < 0
                || KitShulkerLedger.ownedSlot(inventoryShulkerSlots(), ownedShellSlot,
                        packedContents, sourceIdentity) != ownedShellSlot) {
            block("kit_failed_return_post_audit_inventory_changed");
            return;
        }
        sourceReturnStarted = false;
        state = State.RETURNING_SOURCE;
        emit("kit_failed_return_audit_verified", Map.of("item_id", source.itemId(),
                "disposition", "original_full_route_to_import"));
        if (!persist()) block("kit_checkpoint_write_failed");
    }

    /** Audit an already-submitted return without any deposit click. A failed
     * inspection leaves the pending source obligation in the checkpoint. */
    private void tickVerifyCompletedReturn() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()) {
            exporter.abortForRecovery("return_audit_interrupted");
            block("kit_completed_return_audit_interrupted");
            return;
        }
        if (sourceBoxesToReturn.size() != 1 || sourceBoxesToReturn.getFirst().returnPosition() == null) {
            block("kit_completed_return_obligation_changed");
            return;
        }
        var source = sourceBoxesToReturn.getFirst();
        int shellSlot = KitShulkerLedger.ownedSlot(inventoryShulkerSlots(), ownedShellSlot,
                packedContents, new KitShulkerLedger.Identity(source.itemId(), source.contents()));
        if (shellSlot != ownedShellSlot || findBorrowedSourceSlot(source) >= 0) {
            exporter.abortForRecovery("return_audit_inventory_changed");
            block("kit_completed_return_inventory_changed");
            return;
        }
        if (!sourceReturnStarted) {
            var cache = CACHE.getPlayerCache().getInventoryCache();
            ItemStack cursor = cache.getMouseStack();
            if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)
                    || INVENTORY.hasActiveRequest()) {
                block("kit_completed_return_window_not_clear");
                return;
            }
            ContainerEntry target = null;
            for (ContainerEntry entry : candidates) {
                if (java.util.Arrays.equals(new int[]{entry.x(), entry.y(), entry.z()},
                        source.returnPosition())) {
                    target = entry;
                    break;
                }
            }
            if (target == null || !target.inventoryFootprintKnown()
                    || !KitSourceReturnPolicy.registeredImportForLiveCapacityAudit(candidates,
                            registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                            source.sourcePosition(), exportPosition, source.returnPosition())
                    || !exporter.startVerifyExistingReturn(source.returnPosition(),
                            source.itemId(), source.contents(), target.isDouble() ? 54 : 27)) {
                block("kit_completed_return_audit_start_failed");
                return;
            }
            sourceReturnStarted = true;
            emit("kit_completed_return_audit_started", Map.of("item_id", source.itemId()));
            return;
        }
        exporter.tick();
        if (exporter.state() == KitExporter.State.FAILED) {
            block("kit_completed_return_audit_failed:" + exporter.failureReason());
            return;
        }
        if (exporter.state() != KitExporter.State.DONE) return;
        var cache = CACHE.getPlayerCache().getInventoryCache();
        ItemStack cursor = cache.getMouseStack();
        if (cache.getOpenContainerId() > 0 || (cursor != null && cursor.getAmount() > 0)
                || findBorrowedSourceSlot(source) >= 0
                || KitShulkerLedger.ownedSlot(inventoryShulkerSlots(), ownedShellSlot,
                        packedContents, new KitShulkerLedger.Identity(
                                source.itemId(), source.contents())) != ownedShellSlot) {
            block("kit_completed_return_post_audit_inventory_changed");
            return;
        }
        sourceBoxesToReturn.removeFirst();
        sourceReturnStarted = false;
        emit("kit_completed_return_audit_verified", Map.of("item_id", source.itemId(),
                "disposition", "already_in_registered_import"));
        state = State.NEED_CARGO;
        currentItem = null;
        currentQuantity = 0;
        if (!persist()) block("kit_checkpoint_write_failed");
    }

    private int findBorrowedSourceSlot(KitFulfillmentJournalStore.BorrowedBox source) {
        Container player = playerInventory();
        if (player == null) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == ownedShellSlot) continue;
            ItemStack stack = player.getItemStack(raw);
            if (isShulker(stack) && stack.getAmount() == 1
                    && source.itemId().equals(ItemIdentifier.getItemId(stack))
                    && source.contents().equals(ItemIdentifier.readShulkerContents(stack))) {
                if (found >= 0) return -1;
                found = raw;
            }
        }
        return found;
    }

    public static int uniqueRetainedShellSlot(KitFulfillmentJournalStore.Snapshot snapshot) {
        return snapshot == null ? -1
                : uniqueRetainedShellSlot(snapshot.retainedShell(), snapshot.ownedShellSlot());
    }

    private static int uniqueRetainedShellSlot(
            KitFulfillmentJournalStore.RetainedShell expected, int ownedSlot) {
        Container player = playerInventory();
        if (player == null) return -1;
        Map<Integer, ItemStack> live = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) live.put(raw, player.getItemStack(raw));
        return KitRetainedShell.uniqueSlot(expected, live, ownedSlot);
    }

    private boolean selectNextReturnTarget(String reason) {
        if (sourceBoxesToReturn.isEmpty()) return false;
        var source = sourceBoxesToReturn.getFirst();
        List<int[]> alternatives = KitSourceReturnPolicy.capacityImports(candidates,
                registeredReturnChests, excludedPositions, regionPos1, regionPos2,
                source.sourcePosition(), exportPosition);
        int[] next = KitSourceReturnPolicy.nextAfter(alternatives, source.returnPosition());
        if (next == null) return false;
        sourceBoxesToReturn.set(0, new KitFulfillmentJournalStore.BorrowedBox(
                source.sourcePosition(), source.itemId(), source.contents(), next));
        emit("kit_source_return_rerouted", Map.of(
                "reason", reason, "item_id", source.itemId(),
                "source_position", java.util.Arrays.toString(source.sourcePosition()),
                "return_position", java.util.Arrays.toString(next)));
        return true;
    }

    private static List<int[]> copyPositions(List<int[]> positions) {
        if (positions == null) return List.of();
        return positions.stream().filter(pos -> pos != null && pos.length == 3)
                .map(int[]::clone).toList();
    }

    private void tickExporting() {
        exporter.tick();
        if (exporter.state() == KitExporter.State.FAILED) {
            block("kit_export_failed:" + exporter.failureReason());
        } else if (exporter.state() == KitExporter.State.DONE) {
            boolean incomplete = !currentBoxMissing.isEmpty();
            if (incomplete) {
                incompleteBoxes++;
                currentBoxMissing.forEach((item, amount) ->
                        missingTotals.merge(item, amount, Integer::sum));
            } else {
                completedBoxes++;
            }
            emit("kit_fulfillment_box_delivered", Map.of("kit", kitName,
                    "completed_boxes", completedBoxes, "incomplete_boxes", incompleteBoxes,
                    "requested_boxes", requestedBoxes, "incomplete", incomplete));
            if (deliveredBoxes() >= requestedBoxes) {
                if (!displacedAppleSlots.isEmpty()) {
                    block("kit_displaced_apples_require_reconciliation");
                    return;
                }
                state = State.COMPLETE;
                try {
                    journal.clear();
                } catch (IOException e) {
                    block("kit_checkpoint_clear_failed");
                    return;
                }
                emit(incompleteBoxes == 0 ? "kit_fulfillment_completed"
                        : "kit_fulfillment_partial", Map.of(
                        "kit", kitName, "completed_boxes", completedBoxes,
                        "incomplete_boxes", incompleteBoxes,
                        "requested_boxes", requestedBoxes,
                        "missing_by_item", Map.copyOf(missingTotals),
                        "action", incompleteBoxes == 0 ? "none"
                                : "manually fill incomplete exported shulkers"));
            } else {
                packedContents.clear();
                currentBoxMissing.clear();
                appleStockExhaustedForBox = false;
                tryUnnamedApples = false;
                appleCargoExemplar = null;
                ownedShellSlot = -1;
                state = State.NEED_SHELL;
                if (!persist()) block("kit_checkpoint_write_failed");
            }
        }
    }

    private boolean startRetrieval(Map<String, Integer> wanted) {
        java.util.function.Predicate<ItemStack> physicalFilter = null;
        java.util.function.Consumer<List<ItemStack>> physicalSurvey = null;
        if (wanted.containsKey(ENCHANTED_APPLE)) {
            KitItemSourcePolicy sourcePolicy = tryUnnamedApples
                    ? KitItemSourcePolicy.REGULAR_APPLES
                    : KitItemSourcePolicy.NAMED_APPLES;
            String phaseName = tryUnnamedApples ? "regular" : "named";
            boolean[] surveyed = {false};
            physicalFilter = stack -> {
                if (!sourcePolicy.acceptsCandidate(stack)) return false;
                // A checkpoint-verified inventory source is inspected before any GUI is open.
                // Opened containers instead select from the complete live physical survey.
                if (appleCargoExemplar == null && !surveyed[0]) appleCargoExemplar = stack;
                return sourcePolicy.accepts(stack, appleCargoExemplar);
            };
            physicalSurvey = observed -> {
                surveyed[0] = true;
                if (appleCargoExemplar == null) {
                    appleCargoExemplar = sourcePolicy.choose(
                            observed, wanted.get(ENCHANTED_APPLE));
                    if (appleCargoExemplar != null) {
                        ItemStack chosen = appleCargoExemplar;
                        emit("kit_apple_source_variant_selected", Map.of(
                                "phase", phaseName,
                                "request_units", wanted.get(ENCHANTED_APPLE),
                                "observed_group_units", observed.stream()
                                        .filter(stack -> sourcePolicy.accepts(stack, chosen))
                                        .mapToInt(ItemStack::getAmount).sum()));
                    }
                }
            };
        } else if (wanted.containsKey("end_crystal")) {
            int requested = wanted.get("end_crystal");
            Container player = playerInventory();
            ItemStack shell = player == null || ownedShellSlot < 9 || ownedShellSlot > 44
                    ? null : player.getItemStack(ownedShellSlot);
            List<ItemStack> shellStacks = shell == null ? List.of()
                    : ItemIdentifier.readShulkerPhysicalStacks(shell);
            KitItemSourcePolicy sourcePolicy = KitItemSourcePolicy.CRYSTALS;
            boolean alreadyPackedCrystals = shellStacks.stream()
                    .anyMatch(sourcePolicy::acceptsCandidate);
            ItemStack anchored = sourcePolicy.partialShellVariant(shellStacks, requested);
            if (alreadyPackedCrystals && anchored == null) return false;
            ItemStack[] selected = {anchored};
            physicalFilter = stack -> {
                if (!sourcePolicy.acceptsCandidate(stack)) return false;
                return selected[0] == null || sourcePolicy.accepts(stack, selected[0]);
            };
            physicalSurvey = observed -> {
                if (selected[0] == null) selected[0] = sourcePolicy.choose(observed, requested);
                if (selected[0] != null) emit("kit_crystal_source_variant_selected", Map.of(
                        "request_units", requested,
                        "observed_group_units", observed.stream()
                                .filter(stack -> sourcePolicy.accepts(stack, selected[0]))
                                .mapToInt(ItemStack::getAmount).sum()));
            };
        }
        return retriever.startKit(retrievalRequest, wanted, candidates,
                regionPos1, regionPos2, excludedPositions, false, null, null,
                recoveryInventorySourceSlot, physicalFilter, false, physicalSurvey);
    }

    /** Only a conclusive no-stock result may trigger a different variant or a partial box. */
    private boolean appleStockExhaustedWithoutCargo() {
        if (!ENCHANTED_APPLE.equals(currentItem) || appleRetrievalHadTargetFailure
                || borrowedSourceWorksite != null || !sourceBoxesToReturn.isEmpty()
                || (retriever.getLastStartFailureReason() != StashRetriever.StartFailureReason.NO_MATCHES
                    && (blocker == null || !blocker.endsWith(":no_more_targets")))
                || unownedUnitsOf(currentItem, baselineSlots.keySet()) != 0) return false;
        state = State.NEED_CARGO;
        currentQuantity = 0;
        retrievalSucceeded = null;
        blocker = null;
        if (!tryUnnamedApples) {
            tryUnnamedApples = true;
            appleCargoExemplar = null;
            if (!persist()) { block("kit_checkpoint_write_failed"); return true; }
            emit(appleNamedInaccessibleTargetSkipped
                    ? "kit_apple_named_access_limited_fallback"
                    : "kit_apple_named_stock_exhausted", Map.of("kit", kitName,
                    "box", deliveredBoxes() + 1, "next", "try_exact_unnamed_stacks",
                    "inaccessible_source_skipped", appleNamedInaccessibleTargetSkipped));
            return true;
        }
        appleStockExhaustedForBox = true;
        if (missingFromRecipe().size() == 1 && markCurrentBoxIncomplete()) return true;
        if (persist()) {
            emit("kit_apple_stock_exhausted", Map.of("kit", kitName,
                    "box", deliveredBoxes() + 1,
                    "action", "pack_other_recipe_items_before_partial_export"));
            return true;
        }
        block("kit_apple_partial_box_unverified");
        return true;
    }

    private String requestName(String component) {
        return "kit_fulfillment:" + kitName + ":" + deliveredBoxes() + ":" + component;
    }

    private String nextUnpackedItem() {
        for (var item : recipe.entrySet()) {
            if (appleStockExhaustedForBox && ENCHANTED_APPLE.equals(item.getKey())) continue;
            if (packedContents.getOrDefault(item.getKey(), 0) < item.getValue()) return item.getKey();
        }
        return null;
    }

    private void block(String reason) {
        block(reason, false);
    }

    private void block(String reason, boolean keepSchedule) {
        if (!keepSchedule) {
            resumeState = null;
            resumeNotBeforeEpochMilli = 0;
        }
        blocker = reason;
        state = State.BLOCKED;
        persist();
        emit("kit_fulfillment_blocked", Map.of(
                "kit", kitName, "reason", reason,
                "completed_boxes", completedBoxes,
                "incomplete_boxes", incompleteBoxes,
                "requested_boxes", requestedBoxes,
                "missing_by_item_in_delivered_boxes", Map.copyOf(missingTotals),
                "current_box_missing", Map.copyOf(currentBoxMissing)));
    }

    private boolean persist() {
        try {
            journal.save(new KitFulfillmentJournalStore.Snapshot(
                    KitFulfillmentJournalStore.SCHEMA_VERSION, kitName, state.name(),
                    recipe, packedContents, requestedBoxes, completedBoxes,
                    currentItem, currentQuantity, ownedShellSlot, exportPosition,
                    kitWorksite, borrowedSourceWorksite,
                    borrowedSourceItemId, borrowedSourceContents, blocker,
                    System.currentTimeMillis(), resumeState, resumeNotBeforeEpochMilli,
                    sourceBoxesToReturn, auditedCargoSlot, writtenOffSources, auditedCargoSlots,
                    incompleteBoxes, missingTotals, currentBoxMissing,
                    displacedAppleSlots, retainedShell).withVariantRepair(variantRepair)
                    .withProtectedLoose(protectedLoose)
                    .withHeldBoxRebase(heldBoxRebase));
            if (state != lastTracedState || !java.util.Objects.equals(currentItem, lastTracedItem)
                    || deliveredBoxes() != lastTracedBox) {
                if (trace("kit_checkpoint_transition", Map.of())) {
                    lastTracedState = state;
                    lastTracedItem = currentItem;
                    lastTracedBox = deliveredBoxes();
                }
            }
            return true;
        } catch (IOException e) {
            emit("kit_checkpoint_save_failed", Map.of(
                    "phase", state.name(), "error", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
            return false;
        }
    }

    private static int[] parsePosition(Object value) {
        if (!(value instanceof String text)) return null;
        String[] parts = text.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<String, Integer> parseContents(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String id)
                    || !(entry.getValue() instanceof Number count) || count.intValue() < 0) {
                return null;
            }
            if (count.intValue() > 0) contents.put(id, count.intValue());
        }
        return Map.copyOf(contents);
    }

    private boolean hotbarSlotsReady() {
        return freeHotbarSlots() > 0;
    }

    private boolean ensureHotbarRoom() {
        Container player = playerInventory();
        if (player == null) { block("kit_inventory_unavailable"); return false; }
        if (hotbarEvacuation != null) {
            hotbarEvacuationTicks++;
            if (hotbarEvacuation.isCompleted() && !hotbarEvacuation.getNow()) {
                block("kit_hotbar_evacuation_rejected");
                return false;
            }
            ItemStack moved = player.getItemStack(hotbarEvacuationTo);
            if (hotbarEvacuation.isCompleted() && hotbarSlotsReady()
                    && matchesRecoveredSource(moved)) {
                emit("kit_hotbar_source_evacuated", Map.of(
                        "from_slot", hotbarEvacuationFrom, "to_slot", hotbarEvacuationTo));
                hotbarEvacuation = null;
                recoveredSourceItemId = null;
                recoveredSourceContents = Map.of();
                return true;
            }
            if (hotbarEvacuationTicks > 200) block("kit_hotbar_evacuation_unverified");
            return false;
        }
        if (hotbarSlotsReady()) return true;
        // The resumed borrowed box and owned kit shell are already selected hotbar assets.
        if (recoveryInventorySourceSlot >= 36 && recoveryInventorySourceSlot <= 44
                && ownedShellSlot >= 36 && ownedShellSlot <= 44
                && matchesRecoveryInventorySource()) return true;

        int from = -1;
        for (int raw = 36; raw <= 44; raw++) {
            if (raw != ownedShellSlot && matchesRecoveredSource(player.getItemStack(raw))) {
                from = raw;
                break;
            }
        }
        int to = -1;
        for (int raw = 9; raw <= 35; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() == 0) { to = raw; break; }
        }
        if (from < 0 || to < 0) { block("kit_hotbar_slots_unavailable"); return false; }
        hotbarEvacuationFrom = from;
        hotbarEvacuationTo = to;
        hotbarEvacuationTicks = 0;
        try {
            hotbarEvacuation = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(5000)
                    .actions(InventoryActionMacros.swapSlots(from, to))
                    .build());
        } catch (RuntimeException e) {
            block("kit_hotbar_evacuation_rejected");
            return false;
        }
        emit("kit_hotbar_source_evacuation_requested", Map.of(
                "from_slot", from, "to_slot", to));
        return false;
    }

    private boolean matchesRecoveredSource(ItemStack stack) {
        return recoveredSourceItemId != null && isShulker(stack)
                && recoveredSourceItemId.equals(ItemIdentifier.baseItemId(
                        ItemIdentifier.getItemId(stack)))
                && recoveredSourceContents.equals(ItemIdentifier.readShulkerContents(stack));
    }

    private boolean matchesRecoveryInventorySource() {
        Container player = playerInventory();
        ItemStack source = player == null || recoveryInventorySourceSlot < 9
                || recoveryInventorySourceSlot > 44 ? null
                : player.getItemStack(recoveryInventorySourceSlot);
        return isShulker(source)
                && recoveryInventorySourceId.equals(ItemIdentifier.getItemId(source))
                && recoveryInventorySourceContents.equals(ItemIdentifier.readShulkerContents(source));
    }

    static Map<String, Integer> contentsAfterTaking(Map<String, Integer> before,
                                                    String itemId, int quantity) {
        if (before == null || itemId == null || quantity <= 0
                || before.getOrDefault(itemId, 0) < quantity) return Map.of();
        Map<String, Integer> after = new LinkedHashMap<>(before);
        int left = before.get(itemId) - quantity;
        if (left == 0) after.remove(itemId);
        else after.put(itemId, left);
        return Map.copyOf(after);
    }

    public static int freeHotbarSlots() {
        Container player = playerInventory();
        if (player == null) return 0;
        int free = 0;
        for (int raw = 36; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) free++;
        }
        return free;
    }

    private static Container playerInventory() {
        return CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
    }

    public static int freeMainSlots() {
        Container player = playerInventory();
        if (player == null) return 0;
        int free = 0;
        for (int raw = 9; raw <= 35; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) free++;
        }
        return free;
    }

    private static Map<Integer, SlotSnapshot> occupiedSlots() {
        Container player = playerInventory();
        if (player == null) return Map.of();
        Map<Integer, SlotSnapshot> result = new LinkedHashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                result.put(raw, new SlotSnapshot(ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        return result;
    }

    private void captureRecoveryShulkerBaseline() {
        Container player = playerInventory();
        Map<KitShulkerLedger.Identity, Integer> others = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == recoveryInventorySourceSlot) continue;
            ItemStack stack = player.getItemStack(raw);
            if (isShulker(stack)) {
                KitShulkerLedger.Identity identity = shulkerIdentity(stack);
                others.merge(identity, stack.getAmount(), Integer::sum);
            }
        }
        recoveryOtherShulkers = Map.copyOf(others);
        Map<String, Integer> after = contentsAfterTaking(
                recoveryInventorySourceContents, currentItem, currentQuantity);
        recoverySourceAfter = new KitShulkerLedger.Identity(recoveryInventorySourceId, after);
    }

    private static KitShulkerLedger.Identity shulkerIdentity(ItemStack stack) {
        return new KitShulkerLedger.Identity(ItemIdentifier.getItemId(stack),
                Map.copyOf(ItemIdentifier.readShulkerContents(stack)));
    }

    private static Map<Integer, KitShulkerLedger.Identity> inventoryShulkerSlots() {
        Container player = playerInventory();
        if (player == null) return Map.of();
        Map<Integer, KitShulkerLedger.Identity> observed = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (isShulker(stack) && stack.getAmount() == 1) {
                observed.put(raw, shulkerIdentity(stack));
            }
        }
        return Map.copyOf(observed);
    }

    private static Map<KitShulkerLedger.Identity, Integer> inventoryShulkerIdentities() {
        Container player = playerInventory();
        if (player == null) return Map.of();
        Map<KitShulkerLedger.Identity, Integer> observed = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (isShulker(stack)) observed.merge(shulkerIdentity(stack),
                    stack.getAmount(), Integer::sum);
        }
        return Map.copyOf(observed);
    }

    private static int uniqueInventoryShulkerContaining(String itemId, int ownedShellSlot) {
        Container player = playerInventory();
        if (player == null) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == ownedShellSlot) continue;
            ItemStack stack = player.getItemStack(raw);
            if (!isShulker(stack) || ItemIdentifier.readShulkerContents(stack)
                    .getOrDefault(itemId, 0) <= 0) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    private boolean baselineStillPresent() {
        Container player = playerInventory();
        if (player == null) {
            lastBaselineMismatch = "player_inventory_unavailable";
            return false;
        }
        Map<Integer, KitInventoryBaseline.Stack> before = new HashMap<>();
        for (var entry : baselineSlots.entrySet()) {
            before.put(entry.getKey(), new KitInventoryBaseline.Stack(
                    entry.getValue().itemId(), entry.getValue().amount()));
        }
        Map<Integer, KitInventoryBaseline.Stack> after = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                after.put(raw, new KitInventoryBaseline.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        var loose = KitInventoryBaseline.compare(before, after, currentItem, currentQuantity);
        if (!loose.preserved()) {
            lastBaselineMismatch = KitBaselineMismatchDiagnostic.looseTotals(
                    loose.expected(), loose.observed());
            emit("kit_preexisting_loose_inventory_mismatch", Map.of(
                    "expected", loose.expected(), "observed", loose.observed(),
                    "retrieved_item", currentItem, "retrieved_quantity", currentQuantity));
            return false;
        }
        if (recoverySourceAfter != null) {
            Map<KitShulkerLedger.Identity, Integer> observed = new HashMap<>();
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (isShulker(stack)) observed.merge(shulkerIdentity(stack),
                        stack.getAmount(), Integer::sum);
            }
            Map<KitShulkerLedger.Identity, Integer> expected =
                    new HashMap<>(recoveryOtherShulkers);
            expected.merge(recoverySourceAfter, 1, Integer::sum);
            int sourceCount = observed.getOrDefault(recoverySourceAfter, 0);
            if (sourceCount < 1) {
                lastBaselineMismatch = KitBaselineMismatchDiagnostic.shulkerLedger(
                        "recovery_source_missing", expected, observed);
                emit("kit_preexisting_shulker_inventory_mismatch", Map.of(
                        "reason", "recovery_source_missing", "expected", recoverySourceAfter,
                        "observed", observed));
                return false;
            }
            if (sourceCount == 1) observed.remove(recoverySourceAfter);
            else observed.put(recoverySourceAfter, sourceCount - 1);
            boolean matches = recoveryOtherShulkers.equals(observed);
            if (!matches) {
                Map<KitShulkerLedger.Identity, Integer> fullObserved =
                        inventoryShulkerIdentities();
                lastBaselineMismatch = KitBaselineMismatchDiagnostic.shulkerLedger(
                        "recovery_other_boxes", expected, fullObserved);
                emit("kit_preexisting_shulker_inventory_mismatch", Map.of(
                        "reason", "recovery_other_boxes", "expected", recoveryOtherShulkers,
                        "observed", observed));
            }
            return matches;
        }
        Map<KitShulkerLedger.Identity, Integer> observed = inventoryShulkerIdentities();
        boolean matches = KitShulkerLedger.matchesAfterRetrieval(
                auditedShulkers, observed, recoveredShulkersDuringCurrentRetrieval);
        if (!matches) {
            Map<KitShulkerLedger.Identity, Integer> expected =
                    new HashMap<>(auditedShulkers);
            for (var entry : recoveredShulkersDuringCurrentRetrieval.entrySet())
                expected.merge(entry.getKey(), entry.getValue(), Integer::sum);
            lastBaselineMismatch = KitBaselineMismatchDiagnostic.shulkerLedger(
                    "retrieval_box_ledger", expected, observed);
            emit("kit_preexisting_shulker_inventory_mismatch", Map.of(
                    "reason", "retrieval_box_ledger", "before", auditedShulkers,
                    "recovered", recoveredShulkersDuringCurrentRetrieval.toString(),
                    "observed", observed));
        }
        return matches;
    }

    private static int unownedUnitsOf(String itemId, Set<Integer> baselineSlots) {
        Container player = playerInventory();
        if (player == null) return 0;
        int count = 0;
        for (int raw = 9; raw <= 44; raw++) {
            if (baselineSlots.contains(raw)) continue;
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && itemId.equals(ItemIdentifier.getItemId(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static KitInventoryBaseline.IncomingCargo incomingCargo(
            String itemId, Map<Integer, SlotSnapshot> baselineSlots) {
        Container player = playerInventory();
        if (player == null) return new KitInventoryBaseline.IncomingCargo(Set.of(), 0);
        Map<Integer, KitInventoryBaseline.Stack> before = new HashMap<>();
        baselineSlots.forEach((raw, stack) -> before.put(raw,
                new KitInventoryBaseline.Stack(stack.itemId(), stack.amount())));
        Map<Integer, KitInventoryBaseline.Stack> after = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0) {
                after.put(raw, new KitInventoryBaseline.Stack(
                        ItemIdentifier.getItemId(stack), stack.getAmount()));
            }
        }
        return KitInventoryBaseline.incomingCargo(before, after, itemId);
    }

    private static String itemSlotCounts(String itemId, Map<Integer, SlotSnapshot> slots) {
        Map<Integer, Integer> matching = new java.util.TreeMap<>();
        for (var entry : slots.entrySet()) {
            if (itemId.equals(entry.getValue().itemId())) {
                matching.put(entry.getKey(), entry.getValue().amount());
            }
        }
        return matching.toString();
    }

    private static String liveItemSlotCounts(String itemId) {
        Container player = playerInventory();
        if (player == null) return "inventory_unavailable";
        Map<Integer, Integer> matching = new java.util.TreeMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && itemId.equals(ItemIdentifier.getItemId(stack))) {
                matching.put(raw, stack.getAmount());
            }
        }
        return matching.toString();
    }

    private static Set<Integer> emptyShellSlots() {
        Container player = playerInventory();
        if (player == null) return Set.of();
        Set<Integer> slots = new HashSet<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (isShulker(stack) && ItemIdentifier.readShulkerContents(stack).isEmpty()) slots.add(raw);
        }
        return Set.copyOf(slots);
    }

    private static int findEmptyShellSlot(Set<Integer> excluded) {
        for (int raw : emptyShellSlots()) if (!excluded.contains(raw)) return raw;
        return -1;
    }

    private static boolean isShulker(ItemStack stack) {
        return ItemIdentifier.isShulkerBox(stack);
    }

    private void emit(String event, Map<String, Object> fields) {
        trace(event, fields);
        if (events != null) events.accept(event, fields);
    }

    private boolean trace(String event, Map<String, Object> payload) {
        if (kitName == null || (!event.startsWith("kit_")
                && !event.startsWith("retrieve_"))) return false;
        Map<Integer, Integer> itemSlots = new LinkedHashMap<>();
        List<Integer> occupiedSlots = new ArrayList<>();
        Container player = playerInventory();
        if (player != null) {
            for (int raw = 9; raw <= 44; raw++) {
                ItemStack stack = player.getItemStack(raw);
                if (stack == null || stack.getAmount() <= 0) continue;
                occupiedSlots.add(raw);
                if (currentItem != null
                        && currentItem.equals(ItemIdentifier.getItemId(stack))) {
                    itemSlots.put(raw, stack.getAmount());
                }
            }
        }
        Map<String, Integer> contents = parseContents(payload.get("source_contents"));
        if (contents == null) contents = parseContents(payload.get("source_contents_after"));
        String sourceId = payload.get("source_item_id") instanceof String id ? id
                : payload.get("item_id") instanceof String id ? id : null;
        KitProvenanceJournal.Source source = sourceId == null ? null
                : new KitProvenanceJournal.Source(
                        ItemIdentifier.baseItemId(sourceId),
                        contents == null
                                ? payload.get("source_contents_hash") instanceof String hash
                                    ? hash : null
                                : KitProvenanceJournal.contentsHash(contents),
                        payload.get("requested_item_count") instanceof Number count
                                ? count.intValue()
                                : currentItem == null || contents == null ? null
                                    : contents.getOrDefault(currentItem, 0),
                        payload.get("source_position") instanceof String position
                                ? position : payload.get("target_position") instanceof String target
                                    ? target : null,
                        payload.get("placed_position") instanceof String worksite
                                ? worksite : null,
                        payload.get("return_position") instanceof String destination
                                ? destination : null);
        String result = payload.get("reason") instanceof String reason ? reason
                : payload.get("failure_reason") instanceof String failure ? failure : null;
        KitProvenanceJournal.Step step = payload.get("retriever_state") instanceof String
                || payload.get("target_position") instanceof String
                ? new KitProvenanceJournal.Step(
                        payload.get("retriever_state") instanceof String retrieverState
                                ? retrieverState : null,
                        payload.get("target_position") instanceof String target ? target : null,
                        payload.get("remaining_total") instanceof Number remaining
                                ? remaining.intValue() : null,
                        payload.get("moved_stacks") instanceof Number moved
                                ? moved.intValue() : null,
                        payload.get("target_moved_stacks") instanceof Number moved
                                ? moved.intValue() : null,
                        payload.get("target_open_attempted") instanceof Boolean opened
                                ? opened : null) : null;
        try {
            provenance.record(kitName, "kit_fulfillment_box_delivered".equals(event)
                            ? deliveredBoxes() : deliveredBoxes() + 1, retrievalRequest,
                    state.name(), event, currentItem == null ? null
                            : ItemIdentifier.baseItemId(currentItem), currentQuantity,
                    itemSlots, occupiedSlots, source, step, result);
            provenanceFailureReported = false;
            return true;
        } catch (IOException | ArithmeticException e) {
            if (!provenanceFailureReported && events != null) {
                events.accept("kit_provenance_write_failed",
                        Map.of("error", e.getClass().getSimpleName(), "event", event));
            }
            provenanceFailureReported = true;
            return false;
        }
    }
}
