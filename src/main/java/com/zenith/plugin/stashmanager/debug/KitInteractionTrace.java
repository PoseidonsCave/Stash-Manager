package com.zenith.plugin.stashmanager.debug;

import com.google.gson.Gson;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Opt-in, passive wire evidence for kit inventory investigations. A click and its changedSlots
 * are client proposals; only later server packets are observations. This class never handles,
 * changes, or authorizes a Minecraft packet or a kit checkpoint.
 */
public final class KitInteractionTrace {
    private static final Gson GSON = new Gson();
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 8192;

    public record Stack(int id, int count, Integer nestedItemId, Integer nestedUnits,
                        int nestedStacks) {
        public Stack(int id, int count) {
            this(id, count, null, null, 0);
        }

        static Stack of(Object value) {
            if (value == null) return null;
            // Newer protocols send HashedStack predictions. They are deliberately opaque
            // here: only the server's ItemStack updates can establish item counts.
            if (!(value instanceof ItemStack item)) return new Stack(-1, 0);
            if (item.getAmount() <= 0) return null;
            try {
                Object contents = item.getDataComponents() == null ? null
                        : item.getDataComponents().get(DataComponentTypes.CONTAINER);
                if (!(contents instanceof List<?> slots) || slots.size() != 27)
                    return new Stack(item.getId(), item.getAmount());
                Integer singleId = null;
                int total = 0;
                int occupied = 0;
                boolean mixed = false;
                for (int index = 0; index < slots.size(); index++) {
                    Object entry = slots.get(index);
                    Object unwrapped = entry instanceof Optional<?> optional
                            ? optional.orElse(null) : entry;
                    if (!(unwrapped instanceof ItemStack inner) || inner.getAmount() <= 0)
                        continue;
                    occupied++;
                    total += inner.getAmount();
                    if (singleId == null) singleId = inner.getId();
                    else if (singleId != inner.getId()) mixed = true;
                }
                return new Stack(item.getId(), item.getAmount(),
                        mixed ? null : singleId, mixed ? null : total, occupied);
            } catch (RuntimeException ignored) {
                return new Stack(item.getId(), item.getAmount());
            }
        }
    }

    /** Only a later independent player-inventory packet can confirm the dual-view gate. */
    public enum Verdict { NO_CLICK, WAITING_FOR_FULL_WINDOW, BALANCED_WINDOW_DELTA,
        DUAL_VIEW_MATCH, DUAL_VIEW_MISMATCH, NO_TRANSFER, UNBALANCED,
        AMBIGUOUS_CLICKS, WINDOW_CHANGED }

    /** Observer status only; a verdict is not a durable transaction receipt. */
    public record Status(Verdict verdict, boolean awaitingFullWindow,
                         boolean awaitingPlayerMirror, boolean failed) { }

    private record Entry(long sequence, String time, String kind, int window, int state,
                         Integer slot, String action, Map<Integer, Stack> slots,
                         Stack cursor, String verdict, Map<Integer, int[]> balance) { }

    private record Pending(int window, int state, Map<Integer, Stack> before,
                           Stack beforeCursor, TreeSet<Integer> items, boolean ambiguous) { }

    private final Path path;
    private final long maxBytes;
    private long sequence;
    private int window = -1;
    private int containerSlots = -1;
    private Map<Integer, Stack> serverSlots = Map.of();
    private Stack serverCursor;
    private Pending pending;
    private Map<Integer, Stack> pendingPlayerView;
    private Verdict lastVerdict = Verdict.NO_CLICK;
    private boolean failed;

    public KitInteractionTrace(Path path) {
        this(path, MAX_BYTES);
    }

    KitInteractionTrace(Path path, long maxBytes) {
        if (maxBytes < 16_384) throw new IllegalArgumentException("maxBytes too small");
        this.path = path;
        this.maxBytes = maxBytes;
    }

    public static KitInteractionTrace defaultStore() {
        return new KitInteractionTrace(Path.of("plugins", "config",
                "stash-manager-kit-interactions.jsonl"));
    }

    public synchronized Verdict lastVerdict() { return lastVerdict; }
    public synchronized boolean failed() { return failed; }
    public synchronized Status status() {
        return new Status(lastVerdict, pending != null, pendingPlayerView != null, failed);
    }

    public synchronized void onOpen(int containerId) {
        if (containerId <= 0) return;
        if (pending != null) lastVerdict = Verdict.WINDOW_CHANGED;
        pendingPlayerView = null;
        window = containerId;
        containerSlots = -1;
        serverSlots = Map.of();
        serverCursor = null;
        pending = null;
        write("server_open", containerId, -1, null, null, Map.of(), null,
                lastVerdict.name(), Map.of());
    }

    public synchronized void onClose(int containerId, boolean server) {
        write(server ? "server_close" : "client_close", containerId, -1,
                null, null, Map.of(), null, pending == null ? null : "pending_unsettled", Map.of());
        if (containerId == window) {
            if (pending != null) lastVerdict = Verdict.WINDOW_CHANGED;
            window = -1;
            containerSlots = -1;
            serverSlots = Map.of();
            serverCursor = null;
            pending = null;
        }
    }

    /** Called only after the click was passed through Zenith's outbound packet pipeline. */
    public synchronized void onClick(ServerboundContainerClickPacket packet) {
        Map<Integer, Stack> proposed = new LinkedHashMap<>();
        packet.getChangedSlots().int2ObjectEntrySet().forEach(entry ->
                proposed.put(entry.getIntKey(), Stack.of(entry.getValue())));
        onClickProposal(packet.getContainerId(), packet.getStateId(), packet.getSlot(),
                packet.getActionType().name() + ":" + packet.getParam(), proposed,
                Stack.of(packet.getCarriedItem()));
    }

    synchronized void onClickProposal(int containerId, int stateId, int slot, String action,
                                      Map<Integer, Stack> proposed, Stack carried) {
        pendingPlayerView = null;
        TreeSet<Integer> items = new TreeSet<>();
        if (containerId == window && containerSlots >= 0) {
            Stack clicked = serverSlots.get(slot);
            if (clicked != null) items.add(clicked.id());
            if (serverCursor != null) items.add(serverCursor.id());
            proposed.values().stream().filter(s -> s != null && s.id() > 0)
                    .forEach(s -> items.add(s.id()));
            boolean ambiguous = pending != null;
            pending = new Pending(window, stateId, Map.copyOf(serverSlots),
                    serverCursor, items, ambiguous);
            lastVerdict = ambiguous ? Verdict.AMBIGUOUS_CLICKS : Verdict.WAITING_FOR_FULL_WINDOW;
        } else {
            pending = null;
            lastVerdict = Verdict.WINDOW_CHANGED;
        }
        write("client_click_proposal", containerId, stateId, slot, action,
                proposed, carried, lastVerdict.name(), Map.of());
    }

    public synchronized void onSetSlot(int containerId, int stateId, int slot, ItemStack item) {
        Stack value = Stack.of(item);
        if (containerId == window && slot >= 0) {
            Map<Integer, Stack> next = new LinkedHashMap<>(serverSlots);
            if (value == null) next.remove(slot); else next.put(slot, value);
            serverSlots = Map.copyOf(next);
        } else if (containerId == -1 && slot == -1) {
            serverCursor = value;
        }
        write("server_slot_observed", containerId, stateId, slot, null,
                Map.of(slot, value == null ? new Stack(0, 0) : value), null,
                pending == null ? null : "partial_not_creditable", Map.of());
    }

    public synchronized void onSetContent(int containerId, int stateId,
                                          ItemStack[] items, ItemStack cursor) {
        Map<Integer, Stack> next = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i++) {
            Stack value = Stack.of(items[i]);
            if (value != null) next.put(i, value);
        }
        Stack nextCursor = Stack.of(cursor);
        boolean firstWindowSnapshot = containerId != window || containerSlots < 0;
        Map<Integer, Stack> changes = firstWindowSnapshot ? next : changed(serverSlots, next);
        Map<Integer, int[]> balance = Map.of();
        String verdict = null;
        if (containerId == window && pending != null) {
            if (pending.ambiguous()) {
                lastVerdict = Verdict.AMBIGUOUS_CLICKS;
            } else if (stateId <= pending.state()) {
                lastVerdict = Verdict.WAITING_FOR_FULL_WINDOW;
            } else if (containerSlots < 0 || items.length != containerSlots + 36) {
                lastVerdict = Verdict.WINDOW_CHANGED;
            } else {
                balance = balance(pending, next, nextCursor, containerSlots);
                lastVerdict = verdict(balance);
                if (lastVerdict == Verdict.BALANCED_WINDOW_DELTA) {
                    pendingPlayerView = playerFooter(next, containerSlots);
                }
            }
            verdict = lastVerdict.name();
            if (lastVerdict != Verdict.WAITING_FOR_FULL_WINDOW) pending = null;
        }
        if (containerId == 0 && pendingPlayerView != null && items.length >= 45) {
            lastVerdict = pendingPlayerView.equals(playerView(next))
                    ? Verdict.DUAL_VIEW_MATCH : Verdict.DUAL_VIEW_MISMATCH;
            verdict = lastVerdict.name();
            pendingPlayerView = null;
        }
        if (containerId == window || containerId > 0) {
            if (containerId != window) onOpen(containerId);
            containerSlots = items.length - 36;
            serverSlots = Map.copyOf(next);
            serverCursor = nextCursor;
        }
        // The first full window records its nonempty slots. Later windows record only deltas.
        write("server_full_observed", containerId, stateId, null,
                "container_slots=" + Math.max(0, items.length - 36), changes,
                nextCursor, verdict, balance);
    }

    private static Verdict verdict(Map<Integer, int[]> balance) {
        boolean moved = false;
        for (int[] delta : balance.values()) {
            if (delta[0] + delta[1] + delta[2] != 0) return Verdict.UNBALANCED;
            if (delta[0] != 0 && delta[1] != 0 && delta[0] == -delta[1]
                    && delta[2] == 0) moved = true;
        }
        return moved ? Verdict.BALANCED_WINDOW_DELTA : Verdict.NO_TRANSFER;
    }

    private static Map<Integer, Stack> playerFooter(Map<Integer, Stack> window, int chestSize) {
        Map<Integer, Stack> result = new LinkedHashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            int inWindow = chestSize + (raw <= 35 ? raw - 9 : 27 + raw - 36);
            Stack value = window.get(inWindow);
            if (value != null) result.put(raw, value);
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, Stack> playerView(Map<Integer, Stack> windowZero) {
        Map<Integer, Stack> result = new LinkedHashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            Stack value = windowZero.get(raw);
            if (value != null) result.put(raw, value);
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, int[]> balance(Pending pending, Map<Integer, Stack> after,
                                               Stack cursor, int chestSize) {
        Map<Integer, int[]> result = new LinkedHashMap<>();
        for (int id : pending.items()) {
            result.put(id, new int[]{units(after, id, 0, chestSize)
                    - units(pending.before(), id, 0, chestSize),
                    units(after, id, chestSize, chestSize + 36)
                    - units(pending.before(), id, chestSize, chestSize + 36),
                    cursorUnits(cursor, id) - cursorUnits(pending.beforeCursor(), id)});
        }
        return result;
    }

    private static int cursorUnits(Stack stack, int id) {
        return stack != null && stack.id() == id ? stack.count() : 0;
    }

    private static int units(Map<Integer, Stack> slots, int id, int first, int end) {
        int result = 0;
        for (int raw = first; raw < end; raw++) result += cursorUnits(slots.get(raw), id);
        return result;
    }

    private static Map<Integer, Stack> changed(Map<Integer, Stack> before,
                                               Map<Integer, Stack> after) {
        Map<Integer, Stack> result = new LinkedHashMap<>();
        TreeSet<Integer> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (int key : keys) {
            if (!java.util.Objects.equals(before.get(key), after.get(key))) {
                result.put(key, after.getOrDefault(key, new Stack(0, 0)));
            }
        }
        return result;
    }

    private void write(String kind, int id, int state, Integer slot, String action,
                       Map<Integer, Stack> slots, Stack cursor, String verdict,
                       Map<Integer, int[]> balance) {
        if (failed) return;
        try {
            byte[] line = (GSON.toJson(new Entry(++sequence, Instant.now().toString(),
                    kind, id, state, slot, action, slots, cursor, verdict, balance)) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (line.length > MAX_LINE_BYTES) throw new IOException("trace entry too large");
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (Files.isSymbolicLink(path) || Files.isSymbolicLink(previous()))
                throw new IOException("trace path is a symlink");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(path) + line.length > maxBytes) {
                Files.move(path, previous(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException ignored) {
                    Files.createFile(path);
                }
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("trace path is not a regular file");
            try { Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ByteBuffer bytes = ByteBuffer.wrap(line);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(false);
            }
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic failure must never change packet handling or a kit checkpoint.
            failed = true;
        }
    }

    private Path previous() { return path.resolveSibling(path.getFileName() + ".previous"); }
}
