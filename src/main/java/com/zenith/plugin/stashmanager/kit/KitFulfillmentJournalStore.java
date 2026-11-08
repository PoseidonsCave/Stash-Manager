package com.zenith.plugin.stashmanager.kit;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Atomic checkpoint; an uncertain transfer must be inspected, never replayed blindly. */
public final class KitFulfillmentJournalStore {
    public static final int SCHEMA_VERSION = 12;
    private static final Gson GSON = new Gson();

    public record BorrowedBox(int[] sourcePosition, String itemId,
                              Map<String, Integer> contents, int[] returnPosition) {
        public BorrowedBox(int[] sourcePosition, String itemId,
                           Map<String, Integer> contents) {
            this(sourcePosition, itemId, contents, null);
        }

        public BorrowedBox {
            sourcePosition = sourcePosition == null ? null : sourcePosition.clone();
            contents = contents == null ? Map.of() : Map.copyOf(contents);
            returnPosition = returnPosition == null ? null : returnPosition.clone();
        }

        @Override public int[] sourcePosition() {
            return sourcePosition == null ? null : sourcePosition.clone();
        }

        @Override public int[] returnPosition() {
            return returnPosition == null ? null : returnPosition.clone();
        }

        public int[] targetPosition() {
            return returnPosition == null ? sourcePosition() : returnPosition();
        }
    }

    public record RetainedShell(int rawSlot, String itemId, String componentsHash) { }

    /** Write-ahead intent for a single component-exact, fail-stop kit repair. */
    public record VariantRepair(int[] importPosition, int expectedImportSlots,
                                int cargoRawSlot, String cargoHash, String itemId, int amount,
                                int outgoingSlot, int mergeTargetSlot,
                                String sourceHash, String outgoingHash, String shellHash,
                                int displacedRawSlot, String displacedHash,
                                String resultShellHash, int auditedIncomingRawSlot,
                                int fingerprintVersion) {
        public VariantRepair(int[] importPosition, int expectedImportSlots,
                             int cargoRawSlot, String cargoHash, String itemId, int amount,
                             int outgoingSlot, int mergeTargetSlot,
                             String sourceHash, String outgoingHash, String shellHash,
                             int displacedRawSlot, String displacedHash,
                             String resultShellHash) {
            this(importPosition, expectedImportSlots, cargoRawSlot, cargoHash,
                    itemId, amount, outgoingSlot, mergeTargetSlot, sourceHash,
                    outgoingHash, shellHash, displacedRawSlot, displacedHash,
                    resultShellHash, 0, 0);
        }
        public VariantRepair(int[] importPosition, int expectedImportSlots,
                             int cargoRawSlot, String cargoHash, String itemId, int amount,
                             int outgoingSlot, int mergeTargetSlot,
                             String sourceHash, String outgoingHash, String shellHash,
                             int displacedRawSlot, String displacedHash,
                             String resultShellHash, int auditedIncomingRawSlot) {
            this(importPosition, expectedImportSlots, cargoRawSlot, cargoHash,
                    itemId, amount, outgoingSlot, mergeTargetSlot, sourceHash,
                    outgoingHash, shellHash, displacedRawSlot, displacedHash,
                    resultShellHash, auditedIncomingRawSlot, 0);
        }
        public VariantRepair {
            importPosition = importPosition == null ? null : importPosition.clone();
        }
        @Override public int[] importPosition() {
            return importPosition == null ? null : importPosition.clone();
        }
        public VariantRepair withDisplaced(int raw, String hash) {
            return new VariantRepair(importPosition, expectedImportSlots, cargoRawSlot,
                    cargoHash,
                    itemId, amount, outgoingSlot, mergeTargetSlot, sourceHash,
                    outgoingHash, shellHash, raw, hash, resultShellHash,
                    auditedIncomingRawSlot, fingerprintVersion);
        }
        public VariantRepair withResult(int raw, String hash, String resultHash) {
            return new VariantRepair(importPosition, expectedImportSlots, cargoRawSlot,
                    cargoHash,
                    itemId, amount, outgoingSlot, mergeTargetSlot, sourceHash,
                    outgoingHash, shellHash, raw, hash, resultHash,
                    auditedIncomingRawSlot, fingerprintVersion);
        }
        public VariantRepair withAuditedIncoming(int raw) {
            return new VariantRepair(importPosition, expectedImportSlots, cargoRawSlot,
                    cargoHash, itemId, amount, outgoingSlot, mergeTargetSlot,
                    sourceHash, outgoingHash, shellHash, displacedRawSlot,
                    displacedHash, resultShellHash, raw, fingerprintVersion);
        }
        public VariantRepair withCanonicalEvidence(String cargo, String source,
                                                    String outgoing, String shell) {
            return new VariantRepair(importPosition, expectedImportSlots, cargoRawSlot,
                    cargo, itemId, amount, outgoingSlot, mergeTargetSlot, source,
                    outgoing, shell, displacedRawSlot, displacedHash, resultShellHash,
                    auditedIncomingRawSlot, 2);
        }
    }

    public record Snapshot(int schemaVersion, String kitName, String state,
                           Map<String, Integer> recipe, Map<String, Integer> packedContents,
                           int requestedBoxes, int completedBoxes, String currentItem,
                           int currentQuantity, int ownedShellSlot, int[] exportPosition,
                           int[] kitWorksite, int[] borrowedSourceWorksite,
                           String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                           String blocker, long updatedAtEpochMilli,
                           String resumeState, long resumeNotBeforeEpochMilli,
                           List<BorrowedBox> sourceBoxesToReturn,
                           int auditedCargoSlot,
                           List<BorrowedBox> writtenOffSources,
                           Map<Integer, Integer> auditedCargoSlots,
                           int incompleteBoxes, Map<String, Integer> missingTotals,
                           Map<String, Integer> currentBoxMissing,
                           Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots,
                           RetainedShell retainedShell, VariantRepair variantRepair) {
        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot, List<BorrowedBox> writtenOffSources,
                        Map<Integer, Integer> auditedCargoSlots, int incompleteBoxes,
                        Map<String, Integer> missingTotals, Map<String, Integer> currentBoxMissing,
                        Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots,
                        RetainedShell retainedShell) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, displacedAppleSlots, retainedShell, null);
        }
        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot, List<BorrowedBox> writtenOffSources,
                        Map<Integer, Integer> auditedCargoSlots,
                        int incompleteBoxes, Map<String, Integer> missingTotals,
                        Map<String, Integer> currentBoxMissing,
                        Map<Integer, KitInventoryAppleOwnership.Slot> displacedAppleSlots) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, displacedAppleSlots, null);
        }

        public Snapshot withRetainedShell(RetainedShell shell) {
            return new Snapshot(schemaVersion, kitName, state, recipe, packedContents,
                    requestedBoxes, completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, displacedAppleSlots, shell, variantRepair);
        }
        public Snapshot withVariantRepair(VariantRepair repair) {
            return new Snapshot(schemaVersion, kitName, state, recipe, packedContents,
                    requestedBoxes, completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, displacedAppleSlots, retainedShell, repair);
        }
        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot, List<BorrowedBox> writtenOffSources,
                        Map<Integer, Integer> auditedCargoSlots,
                        int incompleteBoxes, Map<String, Integer> missingTotals,
                        Map<String, Integer> currentBoxMissing) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, Map.of());
        }

        public Snapshot withDisplacedAppleSlots(
                Map<Integer, KitInventoryAppleOwnership.Slot> slots) {
            return new Snapshot(schemaVersion, kitName, state, recipe, packedContents,
                    requestedBoxes, completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, incompleteBoxes, missingTotals,
                    currentBoxMissing, slots, retainedShell, variantRepair);
        }
        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot, List<BorrowedBox> writtenOffSources,
                        Map<Integer, Integer> auditedCargoSlots) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, auditedCargoSlots, 0, Map.of(), Map.of());
        }

        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot, List<BorrowedBox> writtenOffSources) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot,
                    writtenOffSources, Map.of());
        }

        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli, String resumeState,
                        long resumeNotBeforeEpochMilli, List<BorrowedBox> sourceBoxesToReturn,
                        int auditedCargoSlot) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite, borrowedSourceItemId,
                    borrowedSourceContents, blocker, updatedAtEpochMilli, resumeState,
                    resumeNotBeforeEpochMilli, sourceBoxesToReturn, auditedCargoSlot, List.of());
        }

        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli,
                        String resumeState, long resumeNotBeforeEpochMilli,
                        List<BorrowedBox> sourceBoxesToReturn) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite,
                    borrowedSourceItemId, borrowedSourceContents, blocker,
                    updatedAtEpochMilli, resumeState, resumeNotBeforeEpochMilli,
                    sourceBoxesToReturn, -1);
        }

        public Snapshot(int schemaVersion, String kitName, String state,
                        Map<String, Integer> recipe, Map<String, Integer> packedContents,
                        int requestedBoxes, int completedBoxes, String currentItem,
                        int currentQuantity, int ownedShellSlot, int[] exportPosition,
                        int[] kitWorksite, int[] borrowedSourceWorksite,
                        String borrowedSourceItemId, Map<String, Integer> borrowedSourceContents,
                        String blocker, long updatedAtEpochMilli,
                        String resumeState, long resumeNotBeforeEpochMilli) {
            this(schemaVersion, kitName, state, recipe, packedContents, requestedBoxes,
                    completedBoxes, currentItem, currentQuantity, ownedShellSlot,
                    exportPosition, kitWorksite, borrowedSourceWorksite,
                    borrowedSourceItemId, borrowedSourceContents, blocker,
                    updatedAtEpochMilli, resumeState, resumeNotBeforeEpochMilli, List.of(), -1);
        }

        public Snapshot {
            recipe = recipe == null ? Map.of() : Map.copyOf(recipe);
            packedContents = packedContents == null ? Map.of() : Map.copyOf(packedContents);
            exportPosition = exportPosition == null ? null : exportPosition.clone();
            kitWorksite = kitWorksite == null ? null : kitWorksite.clone();
            borrowedSourceWorksite = borrowedSourceWorksite == null
                    ? null : borrowedSourceWorksite.clone();
            borrowedSourceContents = borrowedSourceContents == null
                    ? Map.of() : Map.copyOf(borrowedSourceContents);
            sourceBoxesToReturn = sourceBoxesToReturn == null
                    ? List.of() : List.copyOf(sourceBoxesToReturn);
            writtenOffSources = writtenOffSources == null
                    ? List.of() : List.copyOf(writtenOffSources);
            auditedCargoSlots = auditedCargoSlots == null
                    ? Map.of() : Map.copyOf(auditedCargoSlots);
            missingTotals = missingTotals == null ? Map.of() : Map.copyOf(missingTotals);
            currentBoxMissing = currentBoxMissing == null
                    ? Map.of() : Map.copyOf(currentBoxMissing);
            displacedAppleSlots = displacedAppleSlots == null
                    ? Map.of() : Map.copyOf(displacedAppleSlots);
        }

        @Override public int[] exportPosition() {
            return exportPosition == null ? null : exportPosition.clone();
        }

        @Override public int[] kitWorksite() {
            return kitWorksite == null ? null : kitWorksite.clone();
        }

        @Override public int[] borrowedSourceWorksite() {
            return borrowedSourceWorksite == null ? null : borrowedSourceWorksite.clone();
        }
    }

    private final Path path;

    public KitFulfillmentJournalStore(Path path) {
        this.path = path;
    }

    public static KitFulfillmentJournalStore defaultStore() {
        return new KitFulfillmentJournalStore(Path.of("plugins", "config",
                "stash-manager-kit-fulfillment-checkpoint.json"));
    }

    public boolean exists() { return Files.exists(path); }

    public synchronized void save(Snapshot snapshot) throws IOException {
        validate(snapshot);
        if (exists() && snapshot.displacedAppleSlots().isEmpty()) {
            Snapshot previous = load().orElse(null);
            if (previous != null && previous.kitName().equals(snapshot.kitName())
                    && !previous.displacedAppleSlots().isEmpty()) {
                throw new IOException("Refusing to discard verified displaced apple ownership");
            }
        }
        if (exists() && snapshot.retainedShell() == null) {
            Snapshot previous = load().orElse(null);
            if (previous != null && previous.kitName().equals(snapshot.kitName())
                    && previous.retainedShell() != null
                    && !("NEED_CARGO".equals(snapshot.state())
                        && snapshot.ownedShellSlot() >= 9
                        && snapshot.packedContents().isEmpty())) {
                throw new IOException("Refusing to discard reserved kit shell ownership");
            }
        }
        if (exists() && snapshot.variantRepair() == null) {
            Snapshot previous = load().orElse(null);
            if (previous != null && previous.kitName().equals(snapshot.kitName())
                    && previous.variantRepair() != null
                    && !("RETURNING_SOURCE".equals(snapshot.state())
                        || "NEED_CARGO".equals(snapshot.state()))) {
                throw new IOException("Refusing to discard unresolved variant repair");
            }
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, path,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized Optional<Snapshot> load() throws IOException {
        if (!exists()) return Optional.empty();
        Snapshot snapshot;
        try {
            snapshot = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Snapshot.class);
        } catch (RuntimeException e) {
            throw new IOException("Invalid kit fulfillment checkpoint", e);
        }
        if (snapshot != null && snapshot.schemaVersion() >= 2
                && snapshot.schemaVersion() < SCHEMA_VERSION) {
            snapshot = new Snapshot(SCHEMA_VERSION, snapshot.kitName(), snapshot.state(),
                    snapshot.recipe(), snapshot.packedContents(), snapshot.requestedBoxes(),
                    snapshot.completedBoxes(), snapshot.currentItem(), snapshot.currentQuantity(),
                    snapshot.ownedShellSlot(), snapshot.exportPosition(), snapshot.kitWorksite(),
                    snapshot.borrowedSourceWorksite(), snapshot.borrowedSourceItemId(),
                    snapshot.borrowedSourceContents(), snapshot.blocker(),
                    snapshot.updatedAtEpochMilli(), snapshot.schemaVersion() >= 3
                            ? snapshot.resumeState() : null,
                    snapshot.schemaVersion() >= 3
                            ? snapshot.resumeNotBeforeEpochMilli() : 0,
                    snapshot.schemaVersion() >= 4
                            ? snapshot.sourceBoxesToReturn() : List.of(),
                    snapshot.schemaVersion() >= 5 ? snapshot.auditedCargoSlot() : -1,
                    snapshot.schemaVersion() >= 7 ? snapshot.writtenOffSources() : List.of(),
                    snapshot.schemaVersion() >= 8 ? snapshot.auditedCargoSlots() : Map.of(),
                    snapshot.schemaVersion() >= 9 ? snapshot.incompleteBoxes() : 0,
                    snapshot.schemaVersion() >= 9 ? snapshot.missingTotals() : Map.of(),
                    snapshot.schemaVersion() >= 9 ? snapshot.currentBoxMissing() : Map.of(),
                    snapshot.schemaVersion() >= 10 ? snapshot.displacedAppleSlots() : Map.of())
                    .withRetainedShell(snapshot.retainedShell())
                    .withVariantRepair(snapshot.schemaVersion() >= 11
                            ? snapshot.variantRepair() : null);
        }
        validate(snapshot);
        return Optional.of(snapshot);
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
    }

    private static void validate(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.schemaVersion() != SCHEMA_VERSION
                || snapshot.kitName() == null || snapshot.kitName().isBlank()
                || snapshot.state() == null || snapshot.recipe() == null
                || snapshot.recipe().isEmpty() || snapshot.packedContents() == null
                || snapshot.requestedBoxes() < 1 || snapshot.completedBoxes() < 0
                || snapshot.incompleteBoxes() < 0
                || snapshot.completedBoxes() + snapshot.incompleteBoxes() > snapshot.requestedBoxes()
                || snapshot.missingTotals() == null || snapshot.currentBoxMissing() == null
                || snapshot.missingTotals().entrySet().stream().anyMatch(e ->
                    e.getKey() == null || e.getKey().isBlank() || e.getValue() == null
                            || e.getValue() <= 0)
                || snapshot.currentBoxMissing().entrySet().stream().anyMatch(e ->
                    e.getKey() == null || e.getKey().isBlank() || e.getValue() == null
                            || e.getValue() <= 0)
                || (!snapshot.currentBoxMissing().isEmpty()
                    && (snapshot.currentBoxMissing().size() != 1
                        || !snapshot.currentBoxMissing().containsKey("enchanted_golden_apple")
                        || snapshot.completedBoxes() + snapshot.incompleteBoxes()
                            >= snapshot.requestedBoxes()
                        || snapshot.currentBoxMissing().get("enchanted_golden_apple")
                            != snapshot.recipe().getOrDefault("enchanted_golden_apple", 0)
                                - snapshot.packedContents().getOrDefault(
                                    "enchanted_golden_apple", 0)))
                || !validPosition(snapshot.exportPosition())
                || (snapshot.kitWorksite() != null && !validPosition(snapshot.kitWorksite()))
                || (snapshot.borrowedSourceWorksite() != null
                    && (!validPosition(snapshot.borrowedSourceWorksite())
                        || snapshot.borrowedSourceItemId() == null
                        || snapshot.borrowedSourceItemId().isBlank()))
                || snapshot.borrowedSourceContents() == null
                || snapshot.sourceBoxesToReturn() == null
                || snapshot.writtenOffSources() == null
                || snapshot.auditedCargoSlots() == null
                || snapshot.displacedAppleSlots() == null
                || (snapshot.variantRepair() != null && (!validPosition(
                        snapshot.variantRepair().importPosition())
                    || (snapshot.variantRepair().expectedImportSlots() != 27
                        && snapshot.variantRepair().expectedImportSlots() != 54)
                    || snapshot.variantRepair().cargoRawSlot() < 9
                    || snapshot.variantRepair().cargoRawSlot() > 44
                    || !sha(snapshot.variantRepair().cargoHash())
                    || (snapshot.variantRepair().fingerprintVersion() != 0
                        && snapshot.variantRepair().fingerprintVersion() != 2)
                    || snapshot.variantRepair().itemId() == null
                    || snapshot.variantRepair().itemId().isBlank()
                    || snapshot.variantRepair().amount() < 1
                    || snapshot.variantRepair().amount() > 64
                    || snapshot.variantRepair().outgoingSlot() < 0
                    || snapshot.variantRepair().outgoingSlot() >= 27
                    || snapshot.variantRepair().mergeTargetSlot() < 0
                    || snapshot.variantRepair().mergeTargetSlot() >= 27
                    || snapshot.variantRepair().mergeTargetSlot()
                            == snapshot.variantRepair().outgoingSlot()
                    || !sha(snapshot.variantRepair().sourceHash())
                    || !sha(snapshot.variantRepair().outgoingHash())
                    || !sha(snapshot.variantRepair().shellHash())
                    || (snapshot.variantRepair().resultShellHash() != null
                        && !sha(snapshot.variantRepair().resultShellHash()))
                    || (snapshot.variantRepair().auditedIncomingRawSlot() != 0
                        && (snapshot.variantRepair().auditedIncomingRawSlot() < 9
                            || snapshot.variantRepair().auditedIncomingRawSlot() > 35
                            || snapshot.variantRepair().auditedIncomingRawSlot()
                                == snapshot.variantRepair().cargoRawSlot()))
                    || (snapshot.variantRepair().displacedRawSlot() != -1
                        && (snapshot.variantRepair().displacedRawSlot() < 9
                            || snapshot.variantRepair().displacedRawSlot() > 35
                            || !sha(snapshot.variantRepair().displacedHash())))))
                || (snapshot.retainedShell() != null
                    && (snapshot.retainedShell().rawSlot() < 9
                        || snapshot.retainedShell().rawSlot() > 44
                        || snapshot.retainedShell().rawSlot() == snapshot.ownedShellSlot()
                        || snapshot.retainedShell().itemId() == null
                        || !snapshot.retainedShell().itemId().endsWith("shulker_box")
                        || snapshot.retainedShell().componentsHash() == null
                        || !snapshot.retainedShell().componentsHash().matches("[0-9a-f]{64}")))
                || snapshot.displacedAppleSlots().entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getKey() < 9 || entry.getKey() > 44
                        || entry.getKey() == snapshot.ownedShellSlot()
                        || entry.getValue() == null || entry.getValue().amount() < 1
                        || entry.getValue().amount() > 64
                        || !entry.getValue().customPresentation()
                        || entry.getValue().componentHash() == null
                        || !entry.getValue().componentHash().matches("[0-9a-f]{64}"))
                || snapshot.sourceBoxesToReturn().stream().anyMatch(box -> box == null
                    || (box.sourcePosition() != null && !validPosition(box.sourcePosition()))
                    || (box.returnPosition() != null && !validPosition(box.returnPosition()))
                    || box.itemId() == null || box.itemId().isBlank()
                    || box.contents() == null)
                || snapshot.writtenOffSources().stream().anyMatch(box -> box == null
                    || !validPosition(box.sourcePosition())
                    || box.itemId() == null || box.itemId().isBlank()
                    || box.contents() == null)
                || (snapshot.auditedCargoSlot() != -1
                    && (snapshot.auditedCargoSlot() < 9
                        || snapshot.auditedCargoSlot() > 44
                        || snapshot.auditedCargoSlot() == snapshot.ownedShellSlot()))
                || (!snapshot.auditedCargoSlots().isEmpty()
                    && (snapshot.auditedCargoSlot() != -1
                        || snapshot.currentItem() == null
                        || snapshot.currentQuantity() <= 0
                        || snapshot.auditedCargoSlots().entrySet().stream().anyMatch(entry ->
                            entry.getKey() == null || entry.getKey() < 9 || entry.getKey() > 44
                                || entry.getKey() == snapshot.ownedShellSlot()
                                || entry.getValue() == null || entry.getValue() < 1
                                || entry.getValue() > 64)
                        || snapshot.auditedCargoSlots().values().stream()
                            .mapToInt(Integer::intValue).sum() != snapshot.currentQuantity()))) {
            throw new IOException("Kit fulfillment checkpoint is incomplete or unsupported");
        }
        if ((snapshot.resumeState() == null && snapshot.resumeNotBeforeEpochMilli() != 0)
                || snapshot.resumeNotBeforeEpochMilli() < 0) {
            throw new IOException("Kit fulfillment resume schedule is invalid");
        }
    }

    private static boolean validPosition(int[] position) {
        return position != null && position.length == 3;
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
