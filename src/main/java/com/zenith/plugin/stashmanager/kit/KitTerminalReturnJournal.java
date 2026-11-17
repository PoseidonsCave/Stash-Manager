package com.zenith.plugin.stashmanager.kit;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Durable receipts for the completed kit's protected loose-apple return. */
public final class KitTerminalReturnJournal {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Gson GSON = new Gson();
    private final Path path;

    public enum Phase { READY, OPENING, SUBMITTED, REVIEW_REQUIRED, COMPLETE }

    public record StackFingerprint(String itemId, int amount, String componentHash) { }

    public record Job(int schemaVersion, String kitName, String checkpointSha256, int[] target,
                      Map<Integer, KitInventoryAppleOwnership.Slot> protectedSlots,
                      Map<Integer, StackFingerprint> otherSlots,
                      List<Integer> order, int nextIndex, int activeRawSlot,
                      Phase phase, String blocker, long updatedAtEpochMilli) {
        public Job {
            target = target == null ? null : target.clone();
            protectedSlots = protectedSlots == null ? null : Map.copyOf(protectedSlots);
            otherSlots = otherSlots == null ? null : Map.copyOf(otherSlots);
            order = order == null ? null : List.copyOf(order);
        }
        @Override public int[] target() { return target == null ? null : target.clone(); }
        public Job with(int nextIndex, int activeRawSlot, Phase phase, String blocker) {
            return new Job(schemaVersion, kitName, checkpointSha256, target,
                    protectedSlots, otherSlots, order,
                    nextIndex, activeRawSlot, phase, blocker, System.currentTimeMillis());
        }
    }

    public KitTerminalReturnJournal(Path path) { this.path = path; }
    public static KitTerminalReturnJournal defaultStore() {
        return new KitTerminalReturnJournal(Path.of("plugins", "config",
                "stash-manager-kit-terminal-return.json"));
    }
    public boolean exists() { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }

    public synchronized Optional<Job> load() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("return journal path is a symlink");
        if (!exists()) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_BYTES) throw new IOException("invalid return journal");
        try {
            Job job = GSON.fromJson(Files.readString(path), Job.class);
            validate(job);
            return Optional.of(job);
        } catch (RuntimeException e) {
            throw new IOException("invalid return journal contents", e);
        }
    }

    public synchronized void save(Job job) throws IOException {
        validate(job);
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || Files.isSymbolicLink(absolute))
            throw new IOException("unsafe return journal path");
        Files.createDirectories(parent);
        byte[] bytes = GSON.toJson(job).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("return journal too large");
        Path temporary = Files.createTempFile(parent, ".kit-terminal-return-", ".tmp");
        try {
            privatePermissions(temporary);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic return journal replacement unavailable", e);
            }
            privatePermissions(absolute);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized void clear() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("return journal path is a symlink");
        Files.deleteIfExists(path);
    }

    public static void validate(Job job) {
        if (job == null || job.schemaVersion() != SCHEMA_VERSION
                || job.kitName() == null || job.kitName().isBlank()
                || job.checkpointSha256() == null
                || !SHA256.matcher(job.checkpointSha256()).matches()
                || job.target() == null || job.target().length != 3
                || job.protectedSlots() == null || job.protectedSlots().isEmpty()
                || job.otherSlots() == null
                || job.order() == null || job.order().size() != job.protectedSlots().size()
                || job.order().stream().distinct().count() != job.order().size()
                || !job.protectedSlots().keySet().containsAll(job.order())
                || job.nextIndex() < 0 || job.nextIndex() > job.order().size()
                || job.phase() == null || job.updatedAtEpochMilli() <= 0)
            throw new IllegalArgumentException("invalid kit terminal return journal");
        for (var entry : job.protectedSlots().entrySet()) {
            int raw = entry.getKey();
            var slot = entry.getValue();
            if (raw < 9 || raw > 44 || slot == null || slot.amount() < 1
                    || slot.amount() > 64 || slot.componentHash() == null
                    || !SHA256.matcher(slot.componentHash()).matches())
                throw new IllegalArgumentException("invalid protected return slot");
        }
        for (var entry : job.otherSlots().entrySet()) {
            var slot = entry.getValue();
            if (entry.getKey() < 9 || entry.getKey() > 44
                    || job.protectedSlots().containsKey(entry.getKey())
                    || slot == null || slot.itemId() == null || slot.itemId().isBlank()
                    || slot.amount() < 1 || slot.amount() > 64
                    || slot.componentHash() == null
                    || !SHA256.matcher(slot.componentHash()).matches())
                throw new IllegalArgumentException("invalid other return slot");
        }
        if (job.phase() == Phase.READY && job.activeRawSlot() != -1
                || job.phase() == Phase.COMPLETE && (job.nextIndex() != job.order().size()
                    || job.activeRawSlot() != -1)
                || (job.phase() == Phase.OPENING || job.phase() == Phase.SUBMITTED)
                    && (job.nextIndex() >= job.order().size()
                        || job.activeRawSlot() != job.order().get(job.nextIndex())))
            throw new IllegalArgumentException("invalid return phase position");
    }

    private static void privatePermissions(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }
}
