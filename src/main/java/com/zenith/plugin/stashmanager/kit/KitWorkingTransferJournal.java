package com.zenith.plugin.stashmanager.kit;

import com.google.gson.Gson;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

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
import java.util.Optional;

/** One durable box transfer. A submitted click is never replayed after restart. */
public final class KitWorkingTransferJournal {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private static final Gson GSON = new Gson();
    private final Path path;

    public enum Phase {
        PREPARED, SOURCE_VERIFIED, PICKUP_SUBMITTED, CARGO_VERIFIED,
        DESTINATION_VERIFIED, DEPOSIT_SUBMITTED, DEPOSIT_VERIFIED,
        COMPLETE, REVIEW_REQUIRED
    }

    public record Ticket(int schemaVersion, String kitName, String itemId, int units,
                         int[] source, int sourceSlot, int[] destination, int destinationSlot,
                         String boxFingerprint, Phase phase, long updatedAtEpochMilli,
                         String blocker, Phase reviewFromPhase) {
        public Ticket {
            source = source == null ? null : source.clone();
            destination = destination == null ? null : destination.clone();
        }
        public Ticket(int schemaVersion, String kitName, String itemId, int units,
                      int[] source, int sourceSlot, int[] destination, int destinationSlot,
                      String boxFingerprint, Phase phase, long updatedAtEpochMilli,
                      String blocker) {
            this(schemaVersion, kitName, itemId, units, source, sourceSlot, destination,
                    destinationSlot, boxFingerprint, phase, updatedAtEpochMilli, blocker, null);
        }
        @Override public int[] source() { return source == null ? null : source.clone(); }
        @Override public int[] destination() {
            return destination == null ? null : destination.clone();
        }
        public Ticket with(Phase next, String reason, long now) {
            return new Ticket(schemaVersion, kitName, itemId, units, source, sourceSlot,
                    destination, destinationSlot, boxFingerprint, next, now, reason,
                    next == Phase.REVIEW_REQUIRED
                            ? phase == Phase.REVIEW_REQUIRED ? reviewFromPhase : phase : null);
        }
    }

    public KitWorkingTransferJournal(Path path) {
        this.path = path;
    }

    public static KitWorkingTransferJournal defaultStore() {
        return new KitWorkingTransferJournal(Path.of("plugins", "config",
                "stash-manager-kit-working-transfer.json"));
    }

    public synchronized Optional<Ticket> load() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("transfer journal path is a symlink");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_BYTES) throw new IOException("invalid transfer journal");
        try {
            Ticket ticket = GSON.fromJson(Files.readString(path), Ticket.class);
            validate(ticket);
            return Optional.of(ticket);
        } catch (RuntimeException e) {
            throw new IOException("invalid transfer journal contents", e);
        }
    }

    public synchronized void save(Ticket ticket) throws IOException {
        validate(ticket);
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("transfer journal has no parent");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("transfer journal path is a symlink");
        }
        byte[] bytes = GSON.toJson(ticket).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("transfer journal too large");
        Path temporary = Files.createTempFile(parent, ".kit-working-transfer-", ".tmp");
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
                throw new IOException("atomic transfer journal replacement unavailable", e);
            }
            privatePermissions(normalized);
            // Persist the rename itself before a caller is allowed to submit a click.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Replace only a completed ticket after the fill job has advanced its durable index. */
    public synchronized void beginAfterComplete(Ticket next) throws IOException {
        validate(next);
        if (next.phase() != Phase.PREPARED
                || load().map(Ticket::phase).orElse(null) != Phase.COMPLETE)
            throw new IOException("previous box transfer is not complete");
        save(next);
    }

    public static boolean sameTransfer(Ticket a, Ticket b) {
        return a != null && b != null && a.schemaVersion() == b.schemaVersion()
                && a.kitName().equals(b.kitName()) && a.itemId().equals(b.itemId())
                && a.units() == b.units() && a.sourceSlot() == b.sourceSlot()
                && a.destinationSlot() == b.destinationSlot()
                && java.util.Arrays.equals(a.source(), b.source())
                && java.util.Arrays.equals(a.destination(), b.destination())
                && a.boxFingerprint().equals(b.boxFingerprint());
    }

    public static void validate(Ticket ticket) {
        if (ticket == null || ticket.schemaVersion() != SCHEMA_VERSION
                || ticket.kitName() == null || ticket.kitName().isBlank()
                || ticket.itemId() == null || ticket.itemId().isBlank()
                || ticket.units() < 1 || ticket.source() == null
                || ticket.source().length != 3 || ticket.destination() == null
                || ticket.destination().length != 3
                || ticket.sourceSlot() < 0 || ticket.sourceSlot() >= 54
                || ticket.destinationSlot() < 0 || ticket.destinationSlot() >= 54
                || ticket.boxFingerprint() == null
                || !ticket.boxFingerprint().matches("[a-f0-9]{64}")
                || ticket.phase() == null || ticket.updatedAtEpochMilli() <= 0) {
            throw new IllegalArgumentException("invalid kit working transfer ticket");
        }
        if (ticket.reviewFromPhase() != null
                && (ticket.phase() != Phase.REVIEW_REQUIRED
                    || ticket.reviewFromPhase() == Phase.REVIEW_REQUIRED
                    || ticket.reviewFromPhase() == Phase.COMPLETE))
            throw new IllegalArgumentException("invalid working transfer review phase");
        int maxStack = LaneStorageCapacity.itemCapacityFor(
                ItemProfiles.baseItemId(ticket.itemId())).maxStackSize();
        if (ticket.units() > (long) LaneStorageCapacity.SLOTS_PER_SHULKER * maxStack
                || java.util.Arrays.equals(ticket.source(), ticket.destination())) {
            throw new IllegalArgumentException("kit working transfer exceeds one box or loops to source");
        }
    }

    private static void privatePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems retain their default ACL.
        }
    }
}
