package com.zenith.plugin.stashmanager.organizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Atomic, local persistence for a long-running organizer transaction. */
final class OrganizerJournalStore {
    static final int SCHEMA_VERSION = 1;

    record TaskSnapshot(
            int id,
            int[] source,
            int[] destination,
            String itemId,
            String shulkerContentFilter,
            boolean alreadyInInventory,
            boolean mixedDecomposition,
            boolean mixedBatchConsolidation,
            Map<String, Integer> mixedContents) {
        TaskSnapshot(int id, int[] source, int[] destination, String itemId,
                     String shulkerContentFilter, boolean alreadyInInventory) {
            this(id, source, destination, itemId, shulkerContentFilter, alreadyInInventory,
                    false, false, Map.of());
        }
    }

    record ColumnSnapshot(int id, List<int[]> chests) {}

    record Plan(
            int schemaVersion,
            String jobId,
            long createdAtEpochMilli,
            String dimension,
            int[] regionPos1,
            int[] regionPos2,
            List<TaskSnapshot> tasks,
            Map<String, ColumnSnapshot> columnAssignments,
            List<Long> managedSourceContainerKeys) {}

    record Checkpoint(
            int schemaVersion,
            String jobId,
            long updatedAtEpochMilli,
            String interruptedState,
            String currentRole,
            Integer currentTaskId,
            List<Integer> taskQueue,
            List<Integer> consolidationQueue,
            boolean consolidationMode,
            int consolidationSourcesInBatch,
            int movedThisVisit,
            boolean sourceVisitFailed,
            int totalTasks,
            int completedTasks,
            int nextProgressMilestone,
            int[] reconciliationStation,
            int[] reconciliationWorksite,
            String packItemId,
            int[] packDestination,
            int[] shulkerPlacePos,
            boolean fetchedPackingShulker,
            int shulkerInventoryCountBeforePlacement,
            int compatibleShulkerCountBeforePlacement,
            boolean temporaryShulkerOutstanding,
            List<int[]> stagingImportDestinations,
            List<String> stagingStorageClassesPlanned,
            List<String> stagedStorageClasses,
            int stagedShulkers,
            int permanentLaneGaps,
            String stagingReason,
            Map<String, Integer> overflowItems,
            boolean mixedDecompositionMode,
            boolean mixedBatchConsolidationMode,
            boolean mixedBoxDrained,
            int decomposedMixedShulkers,
            int generatedLooseTasks,
            int mixedPendingSourceSlot,
            int mixedPendingCargoSlot,
            List<Integer> mixedCargoSlots,
            List<int[]> mixedStagingUsedDestinations,
            List<Integer> protectedInventorySlots,
            boolean stopAfterShulkerRecovery,
            String shulkerRecoveryTrigger) {}

    record Loaded(Plan plan, Checkpoint checkpoint) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path planPath;
    private final Path checkpointPath;

    OrganizerJournalStore(Path planPath, Path checkpointPath) {
        this.planPath = Objects.requireNonNull(planPath, "planPath");
        this.checkpointPath = Objects.requireNonNull(checkpointPath, "checkpointPath");
    }

    static OrganizerJournalStore defaultStore() {
        Path configDirectory = Path.of("plugins", "config");
        return new OrganizerJournalStore(
                configDirectory.resolve("stash-manager-organizer-plan.json"),
                configDirectory.resolve("stash-manager-organizer-checkpoint.json"));
    }

    boolean exists() {
        return Files.exists(planPath) || Files.exists(checkpointPath);
    }

    void savePlan(Plan plan) throws IOException {
        validatePlan(plan);
        atomicWrite(planPath, GSON.toJson(plan));
    }

    void saveCheckpoint(Checkpoint checkpoint) throws IOException {
        validateCheckpointShape(checkpoint);
        atomicWrite(checkpointPath, GSON.toJson(checkpoint));
    }

    Optional<Loaded> load() throws IOException {
        boolean hasPlan = Files.exists(planPath);
        boolean hasCheckpoint = Files.exists(checkpointPath);
        if (!hasPlan && !hasCheckpoint) return Optional.empty();
        if (!hasPlan || !hasCheckpoint) {
            throw new IOException("Organizer journal is incomplete (plan/checkpoint pair required)");
        }

        final Plan plan;
        final Checkpoint checkpoint;
        try {
            plan = GSON.fromJson(Files.readString(planPath, StandardCharsets.UTF_8), Plan.class);
            checkpoint = GSON.fromJson(
                    Files.readString(checkpointPath, StandardCharsets.UTF_8), Checkpoint.class);
        } catch (RuntimeException e) {
            throw new IOException("Organizer journal contains invalid JSON", e);
        }

        validatePlan(plan);
        validateCheckpointShape(checkpoint);
        if (!plan.jobId().equals(checkpoint.jobId())) {
            throw new IOException("Organizer plan and checkpoint belong to different jobs");
        }

        Map<Integer, TaskSnapshot> tasksById = plan.tasks().stream()
                .collect(java.util.stream.Collectors.toMap(TaskSnapshot::id, task -> task));
        if (checkpoint.currentTaskId() != null
                && !tasksById.containsKey(checkpoint.currentTaskId())) {
            throw new IOException("Organizer checkpoint references an unknown current task");
        }
        for (Integer id : checkpoint.taskQueue()) {
            if (!tasksById.containsKey(id)) {
                throw new IOException("Organizer checkpoint references unknown task " + id);
            }
        }
        for (Integer id : checkpoint.consolidationQueue()) {
            if (!tasksById.containsKey(id)) {
                throw new IOException("Organizer checkpoint references unknown consolidation task " + id);
            }
        }
        return Optional.of(new Loaded(plan, checkpoint));
    }

    void clear() throws IOException {
        Files.deleteIfExists(checkpointPath);
        Files.deleteIfExists(planPath);
        Files.deleteIfExists(tempPath(checkpointPath));
        Files.deleteIfExists(tempPath(planPath));
    }

    private static void validatePlan(Plan plan) throws IOException {
        if (plan == null || plan.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported organizer plan schema");
        }
        if (plan.jobId() == null || plan.jobId().isBlank()) {
            throw new IOException("Organizer plan is missing its job id");
        }
        requirePosition(plan.regionPos1(), "region pos1");
        requirePosition(plan.regionPos2(), "region pos2");
        if (plan.tasks() == null || plan.columnAssignments() == null
                || plan.managedSourceContainerKeys() == null) {
            throw new IOException("Organizer plan is missing required collections");
        }
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        for (TaskSnapshot task : plan.tasks()) {
            if (task == null || task.id() < 1 || !ids.add(task.id())) {
                throw new IOException("Organizer plan contains an invalid or duplicate task id");
            }
            requirePosition(task.source(), "task source");
            requirePosition(task.destination(), "task destination");
            if (task.itemId() == null || task.itemId().isBlank()) {
                throw new IOException("Organizer plan contains a task without an item id");
            }
        }
    }

    private static void validateCheckpointShape(Checkpoint checkpoint) throws IOException {
        if (checkpoint == null || checkpoint.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported organizer checkpoint schema");
        }
        if (checkpoint.jobId() == null || checkpoint.jobId().isBlank()
                || checkpoint.interruptedState() == null || checkpoint.currentRole() == null) {
            throw new IOException("Organizer checkpoint is missing required state");
        }
        if (checkpoint.taskQueue() == null || checkpoint.consolidationQueue() == null
                || checkpoint.stagingImportDestinations() == null
                || checkpoint.stagingStorageClassesPlanned() == null
                || checkpoint.stagedStorageClasses() == null
                || checkpoint.overflowItems() == null) {
            throw new IOException("Organizer checkpoint is missing required collections");
        }
        if (checkpoint.completedTasks() < 0 || checkpoint.totalTasks() < 0) {
            throw new IOException("Organizer checkpoint has invalid task counts");
        }
        if (checkpoint.protectedInventorySlots() != null
                && checkpoint.protectedInventorySlots().stream()
                        .anyMatch(slot -> slot == null || slot < 9 || slot > 44)) {
            throw new IOException("Organizer checkpoint has an invalid protected inventory slot");
        }
    }

    private static void requirePosition(int[] position, String label) throws IOException {
        if (position == null || position.length != 3) {
            throw new IOException("Organizer journal has invalid " + label);
        }
    }

    private static void atomicWrite(Path target, String json) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = tempPath(target);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path tempPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }
}
