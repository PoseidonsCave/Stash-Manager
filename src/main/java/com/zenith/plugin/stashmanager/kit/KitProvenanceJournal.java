package com.zenith.plugin.stashmanager.kit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A bounded, local evidence trail for kit retrievals. It never stores item NBT or chat. */
public final class KitProvenanceJournal {
    private static final Gson GSON = new Gson();
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private final Path path;
    private final long maxBytes;
    private long sequence;

    public record Source(String itemId, String contentsHash, Integer requestedItemCount,
                         String origin, String worksite, String destination) { }

    public record Step(String retrieverState, String targetPosition, Integer remainingUnits,
                       Integer movedStacks, Integer targetMovedStacks,
                       Boolean targetOpenAttempted) { }

    /** The last blocked retrieval and its durable pre-retrieval inventory witness. */
    public record BlockedRetrieval(Map<Integer, Integer> beforeItemSlots,
                                   Set<Integer> beforeOccupiedSlots,
                                   Set<Integer> blockedOccupiedSlots,
                                   String sourcePosition) {
        public BlockedRetrieval {
            beforeItemSlots = Map.copyOf(beforeItemSlots);
            beforeOccupiedSlots = Set.copyOf(beforeOccupiedSlots);
            blockedOccupiedSlots = Set.copyOf(blockedOccupiedSlots);
        }
    }

    private record Entry(int schemaVersion, long sequence, String timestamp, String kit,
                         int box, String request, String state, String event, String item,
                         int requestedUnits, Map<Integer, Integer> inventoryItemSlots,
                         int inventoryItemTotal, List<Integer> occupiedInventorySlots,
                         Source source, Step step, String result,
                         String evidence) { }

    public KitProvenanceJournal(Path path) {
        this(path, MAX_BYTES);
    }

    KitProvenanceJournal(Path path, long maxBytes) {
        if (maxBytes < 8192) throw new IllegalArgumentException("maxBytes too small");
        this.path = path;
        this.maxBytes = maxBytes;
        this.sequence = Math.max(lastSequence(previousPath()), lastSequence(path));
    }

    public static KitProvenanceJournal defaultStore() {
        return new KitProvenanceJournal(Path.of("plugins", "config",
                "stash-manager-kit-provenance.jsonl"));
    }

    /** Reads evidence only; an absent or incomplete pair cannot authorize reconciliation. */
    public synchronized Optional<BlockedRetrieval> blockedRetrieval(
            String kit, int box, String item, int requestedUnits, String blocker)
            throws IOException {
        Entry baseline = null;
        Entry blocked = null;
        String sourcePosition = null;
        for (Path file : List.of(previousPath(), path)) {
            refuseSymlink(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BYTES + 8192) {
                throw new IOException("Provenance file is not a bounded regular file");
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Entry entry;
                try {
                    entry = GSON.fromJson(JsonParser.parseString(line), Entry.class);
                } catch (RuntimeException e) {
                    throw new IOException("Provenance contains an unreadable entry", e);
                }
                if (entry == null || !kit.equals(entry.kit()) || box != entry.box()
                        || !item.equals(entry.item())
                        || requestedUnits != entry.requestedUnits()) continue;
                if ("kit_retrieval_baseline".equals(entry.event())) {
                    baseline = entry;
                    blocked = null;
                    sourcePosition = null;
                } else if (baseline != null
                        && java.util.Objects.equals(baseline.request(), entry.request())) {
                    if (entry.step() != null && entry.step().targetPosition() != null)
                        sourcePosition = entry.step().targetPosition();
                    if ("kit_fulfillment_blocked".equals(entry.event())) blocked = entry;
                }
            }
        }
        if (baseline == null || blocked == null || !blocker.equals(blocked.result())
                || baseline.inventoryItemSlots() == null
                || baseline.occupiedInventorySlots() == null
                || blocked.occupiedInventorySlots() == null
                || sourcePosition == null) return Optional.empty();
        return Optional.of(new BlockedRetrieval(baseline.inventoryItemSlots(),
                Set.copyOf(baseline.occupiedInventorySlots()),
                Set.copyOf(blocked.occupiedInventorySlots()), sourcePosition));
    }

    /** Each append is forced to disk before the next kit event can be processed. */
    public synchronized void record(String kit, int box, String request, String state,
                                    String event, String item, int requestedUnits,
                                    Map<Integer, Integer> inventoryItemSlots,
                                    List<Integer> occupiedInventorySlots,
                                    Source source, Step step, String result) throws IOException {
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        int total = 0;
        for (var slot : inventoryItemSlots.entrySet()) {
            if (slot.getKey() < 9 || slot.getKey() > 44 || slot.getValue() < 0) {
                throw new IOException("Invalid provenance inventory slot");
            }
            slots.put(slot.getKey(), slot.getValue());
            total = Math.addExact(total, slot.getValue());
        }
        if (occupiedInventorySlots.stream().anyMatch(slot -> slot < 9 || slot > 44)) {
            throw new IOException("Invalid provenance occupied slot");
        }
        Entry entry = new Entry(SCHEMA_VERSION, ++sequence, Instant.now().toString(),
                safeToken(kit), box, digest(request), safeToken(state), safeToken(event),
                safeToken(item), requestedUnits, Map.copyOf(slots), total,
                List.copyOf(occupiedInventorySlots),
                source == null ? null : new Source(safeToken(source.itemId()),
                        safeHash(source.contentsHash()), source.requestedItemCount(),
                        safePosition(source.origin()), safePosition(source.worksite()),
                        safePosition(source.destination())),
                step == null ? null : new Step(safeToken(step.retrieverState()),
                        safePosition(step.targetPosition()), step.remainingUnits(),
                        step.movedStacks(), step.targetMovedStacks(),
                        step.targetOpenAttempted()),
                safeToken(result), evidenceFor(event));
        byte[] line = (GSON.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > 8192) throw new IOException("Provenance entry too large");
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        refuseSymlink(path);
        refuseSymlink(previousPath());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) + line.length > maxBytes) {
            Files.move(path, previousPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        createPrivateFile(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            if (channel.size() > 0) {
                ByteBuffer last = ByteBuffer.allocate(1);
                try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
                    reader.read(last, reader.size() - 1);
                }
                if (last.get(0) != '\n') channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
            }
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    /** Fingerprint a box's item counts without writing its names, lore, or full contents. */
    public static String contentsHash(Map<String, Integer> contents) {
        StringBuilder canonical = new StringBuilder();
        contents.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey().length()).append(':')
                        .append(entry.getKey()).append('=').append(entry.getValue()).append(';'));
        return digest(canonical.toString());
    }

    private static String digest(String value) {
        if (value == null) return null;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeToken(String value) {
        if (value == null) return null;
        return value.length() <= 128 && value.matches("[A-Za-z0-9_:.\\-/]+")
                ? value : "redacted";
    }

    private static String evidenceFor(String event) {
        return switch (event) {
            case "kit_retrieval_baseline" -> "pre_retrieval_client_inventory";
            case "retrieve_borrowed_shell_recovered" -> "verified_inventory_box_net_increase";
            case "kit_source_return_box_verified", "kit_fulfillment_box_delivered" ->
                    "exporter_server_window_verified";
            default -> "event_with_client_inventory_snapshot";
        };
    }

    private static String safePosition(String value) {
        if (value == null) return null;
        if ("inventory".equals(value)) return value;
        return value.matches("-?\\d{1,9}, ?-?\\d{1,4}, ?-?\\d{1,9}")
                ? value : "redacted";
    }

    private static String safeHash(String value) {
        return value != null && value.matches("[a-f0-9]{64}") ? value : null;
    }

    private Path previousPath() {
        return path.resolveSibling(path.getFileName() + ".previous");
    }

    private static void refuseSymlink(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Provenance path is a symlink");
    }

    private static void createPrivateFile(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Provenance path is not a regular file");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                Files.createFile(file);
            }
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use their own default ACL.
        }
    }

    private static long lastSequence(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BYTES + 8192) return 0;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                try {
                    JsonObject entry = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    return entry.get("sequence").getAsLong();
                } catch (RuntimeException ignored) {
                    // A partial final line can follow a crash; retain earlier evidence.
                }
            }
        } catch (IOException ignored) {
            // The first write will surface a filesystem error to the caller.
        }
        return 0;
    }
}
