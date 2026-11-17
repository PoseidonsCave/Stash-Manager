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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable manifest cursor. One-box intent and click state live in the separate transfer journal. */
public final class KitWorkingFillJournal {
    private static final int MAX_BYTES = 512 * 1024;
    private static final Gson GSON = new Gson();
    private final Path path;

    public enum Status { RUNNING, REVIEW_REQUIRED, COMPLETE }

    public record Job(int schemaVersion, List<KitWorkingTransferJournal.Ticket> transfers,
                      int nextIndex, Status status, String blocker, long updatedAtEpochMilli) {
        public Job { transfers = transfers == null ? null : List.copyOf(transfers); }
        public Job with(int index, Status next, String reason) {
            return new Job(schemaVersion, transfers, index, next, reason,
                    System.currentTimeMillis());
        }
    }

    public KitWorkingFillJournal(Path path) { this.path = path; }

    public static KitWorkingFillJournal defaultStore() {
        return new KitWorkingFillJournal(Path.of("plugins", "config",
                "stash-manager-kit-working-fill.json"));
    }

    public synchronized Optional<Job> load() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("fill journal path is a symlink");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_BYTES) throw new IOException("invalid fill journal");
        try {
            Job job = GSON.fromJson(Files.readString(path), Job.class);
            validate(job);
            return Optional.of(job);
        } catch (RuntimeException e) {
            throw new IOException("invalid fill journal contents", e);
        }
    }

    public synchronized void save(Job job) throws IOException {
        validate(job);
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("fill journal has no parent");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(normalized)) throw new IOException("fill journal path is a symlink");
        byte[] bytes = GSON.toJson(job).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("fill journal too large");
        Path temporary = Files.createTempFile(parent, ".kit-working-fill-", ".tmp");
        try {
            privatePermissions(temporary);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic fill journal replacement unavailable", e);
            }
            privatePermissions(normalized);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void validate(Job job) {
        if (job == null || job.schemaVersion() != 1 || job.transfers() == null
                || job.transfers().isEmpty() || job.transfers().size() > 512
                || job.nextIndex() < 0 || job.nextIndex() > job.transfers().size()
                || job.status() == null || job.updatedAtEpochMilli() <= 0
                || (job.status() == Status.COMPLETE && job.nextIndex() != job.transfers().size())
                || (job.status() != Status.COMPLETE && job.nextIndex() == job.transfers().size()))
            throw new IllegalArgumentException("invalid kit working fill job");
        Set<String> sources = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        for (var ticket : job.transfers()) {
            KitWorkingTransferJournal.validate(ticket);
            if (ticket.phase() != KitWorkingTransferJournal.Phase.PREPARED
                    || !sources.add(key(ticket.source(), ticket.sourceSlot()))
                    || !destinations.add(key(ticket.destination(), ticket.destinationSlot())))
                throw new IllegalArgumentException("duplicate or nonprepared kit working transfer");
        }
    }

    private static String key(int[] pos, int slot) {
        return pos[0] + ":" + pos[1] + ":" + pos[2] + ":" + slot;
    }

    private static void privatePermissions(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }
}
