package com.zenith.plugin.stashmanager.kit;

import com.google.gson.Gson;
import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.player.World;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;
import com.zenith.plugin.stashmanager.organizer.KitExporter;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/** One operator-requested terminal return. A submitted click is never replayed after restart. */
public final class KitTerminalReturnRunner {
    private static final Path REQUEST = Path.of("plugins", "config",
            "stash-manager-kit-terminal-return-request.json");
    private static final Path RECOVERY_REQUEST = Path.of("plugins", "config",
            "stash-manager-kit-terminal-return-recovery-request.json");
    private static final Path CHECKPOINT = Path.of("plugins", "config",
            "stash-manager-kit-fulfillment-checkpoint.json");
    private static final Path JOURNAL_PATH = Path.of("plugins", "config",
            "stash-manager-kit-terminal-return.json");
    private static final KitTerminalReturnJournal JOURNAL =
            new KitTerminalReturnJournal(JOURNAL_PATH);
    private static final Gson GSON = new Gson();
    private static KitTerminalReturnJournal.Job job;
    private static KitExporter exporter;
    private static String lastReported;

    public record Request(int schemaVersion, String checkpointSha256, int[] target,
                          long requestedAtEpochMilli) { }
    public record RecoveryRequest(int schemaVersion, String checkpointSha256,
                                  String reviewedJournalSha256, int[] target,
                                  long requestedAtEpochMilli) { }

    private KitTerminalReturnRunner() { }

    /** Called before the normal kit-checkpoint tick. Only a local operator request activates it. */
    public static synchronized boolean tick(StashManagerModule module) {
        try {
            if (job == null && JOURNAL.exists()) job = JOURNAL.load().orElse(null);
            if (job == null && !Files.exists(REQUEST, LinkOption.NOFOLLOW_LINKS)) return false;
            if (job != null && job.phase() == KitTerminalReturnJournal.Phase.COMPLETE) return false;
            if (job == null) {
                if (!worldReady() || !idle(module)) return true;
                arm(module);
            }
            if (job.phase() == KitTerminalReturnJournal.Phase.REVIEW_REQUIRED) {
                if (Files.exists(RECOVERY_REQUEST, LinkOption.NOFOLLOW_LINKS)
                        && worldReady() && !Proxy.getInstance().hasActivePlayer()
                        && idle(module)) {
                    try { rearmReviewed(module); }
                    catch (IOException e) { report("terminal return recovery request rejected: "
                            + e.getMessage()); }
                }
                return true;
            }
            if (!worldReady() || Proxy.getInstance().hasActivePlayer()) {
                if (exporter != null && exporter.isActive()) review("return_interrupted_by_connection_or_controller");
                return true;
            }
            if (!idle(module)) { review("other_automation_started"); return true; }
            if (job.phase() == KitTerminalReturnJournal.Phase.SUBMITTED && exporter == null) {
                review("submitted_click_requires_live_audit_after_restart");
                return true;
            }
            if (job.nextIndex() == job.order().size()) {
                if (!checkpointMatches(job, true) || !inventoryMatches(job, job.nextIndex())) {
                    review("terminal_return_final_audit_mismatch"); return true;
                }
                if (Files.exists(CHECKPOINT, LinkOption.NOFOLLOW_LINKS))
                    KitFulfillmentJournalStore.defaultStore().clear();
                persist(job.with(job.nextIndex(), -1,
                        KitTerminalReturnJournal.Phase.COMPLETE, null));
                report("terminal return complete: " + job.order().size() + " stacks verified");
                return false;
            }
            if (!checkpointMatches(job, false)) { review("kit_checkpoint_changed"); return true; }
            if (exporter == null) {
                if (!inventoryMatches(job, job.nextIndex()) || !targetMatches(module, job.target())) {
                    review("return_source_or_lane_changed"); return true;
                }
                if (INVENTORY.hasActiveRequest()
                        || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                        || hasCursor() || BARITONE.getCustomGoalProcess().isActive()) return true;
                int raw = job.order().get(job.nextIndex());
                persist(job.with(job.nextIndex(), raw,
                        KitTerminalReturnJournal.Phase.OPENING, null));
                exporter = new KitExporter();
                ItemStack stack = player().getItemStack(raw);
                if (!exporter.startProtectedLooseReturn(job.target(), stack, raw, 54)) {
                    review("protected_return_start_rejected"); return true;
                }
            }
            if (exporter.state() == KitExporter.State.DEPOSITING
                    && job.phase() == KitTerminalReturnJournal.Phase.OPENING) {
                int raw = job.order().get(job.nextIndex());
                if (!inventoryMatches(job, job.nextIndex())) {
                    review("return_source_changed_before_click"); return true;
                }
                if (!liveTargetHasStableChest(module, job.target())) {
                    review("return_target_hopper_or_unknown_live_geometry"); return true;
                }
                // Durably record the uncertain boundary before tick() can submit a click.
                persist(job.with(job.nextIndex(), raw,
                        KitTerminalReturnJournal.Phase.SUBMITTED, null));
            }
            exporter.tick();
            if (exporter.state() == KitExporter.State.FAILED) {
                review("exporter_" + exporter.failureReason()); return true;
            }
            if (exporter.state() == KitExporter.State.DONE) {
                if (job.phase() != KitTerminalReturnJournal.Phase.SUBMITTED
                        || !inventoryMatches(job, job.nextIndex() + 1)) {
                    review("return_receipt_inventory_mismatch"); return true;
                }
                persist(job.with(job.nextIndex() + 1, -1,
                        KitTerminalReturnJournal.Phase.READY, null));
                report("terminal return verified stack " + job.nextIndex()
                        + "/" + job.order().size());
                exporter = null;
            }
        } catch (Exception e) {
            try { review("terminal_return_exception_" + e.getClass().getSimpleName()); }
            catch (Exception nested) { report("terminal return journal unavailable: " + nested.getClass().getSimpleName()); }
        }
        return true;
    }

    public static synchronized void onContainerData(int containerId) {
        if (exporter != null && exporter.isActive()) exporter.onContainerData(containerId);
    }

    private static void arm(StashManagerModule module) throws Exception {
        if (Files.isSymbolicLink(REQUEST) || !Files.isRegularFile(REQUEST, LinkOption.NOFOLLOW_LINKS)
                || Files.size(REQUEST) > 1024) throw new IOException("invalid return request");
        Request request = GSON.fromJson(Files.readString(REQUEST), Request.class);
        if (request == null || request.schemaVersion() != 1
                || request.checkpointSha256() == null
                || !request.checkpointSha256().matches("[a-f0-9]{64}")
                || request.target() == null || request.target().length != 3
                || request.requestedAtEpochMilli() <= 0
                || !request.checkpointSha256().equals(checkpointHash()))
            throw new IOException("return request does not match checkpoint");
        var snapshot = KitFulfillmentJournalStore.defaultStore().load().orElse(null);
        if (!terminalSnapshot(snapshot) || !targetMatches(module, request.target())
                || INVENTORY.hasActiveRequest()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || hasCursor() || BARITONE.getCustomGoalProcess().isActive())
            throw new IOException("terminal return preflight rejected");
        Container inventory = player();
        if (inventory == null || !KitInventoryAppleOwnership.matches(
                snapshot.displacedAppleSlots(), protectedLive(snapshot.displacedAppleSlots())))
            throw new IOException("protected apple slots differ from checkpoint");
        if (KitFulfillmentJournalStore.defaultStore().exists()
                && ownedKitBoxStillPresent(snapshot, inventory))
            throw new IOException("owned kit shell still present");
        Map<Integer, KitTerminalReturnJournal.StackFingerprint> other = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            if (snapshot.displacedAppleSlots().containsKey(raw)) continue;
            var fingerprint = fingerprint(inventory.getItemStack(raw));
            if (fingerprint != null) other.put(raw, fingerprint);
        }
        List<Integer> order = snapshot.displacedAppleSlots().keySet().stream().sorted().toList();
        persist(new KitTerminalReturnJournal.Job(KitTerminalReturnJournal.SCHEMA_VERSION,
                snapshot.kitName(), request.checkpointSha256(), request.target(),
                snapshot.displacedAppleSlots(), other, order, 0, -1,
                KitTerminalReturnJournal.Phase.READY, null, System.currentTimeMillis()));
        report("terminal return armed: " + order.size() + " protected stacks");
    }

    /** A reviewed uncertain first click can only be retried after a new, hash-bound request. */
    private static void rearmReviewed(StashManagerModule module) throws Exception {
        if (Files.isSymbolicLink(RECOVERY_REQUEST)
                || !Files.isRegularFile(RECOVERY_REQUEST, LinkOption.NOFOLLOW_LINKS)
                || Files.size(RECOVERY_REQUEST) > 1024)
            throw new IOException("invalid return recovery request");
        RecoveryRequest request = GSON.fromJson(Files.readString(RECOVERY_REQUEST),
                RecoveryRequest.class);
        if (request == null || request.schemaVersion() != 1
                || request.checkpointSha256() == null
                || request.reviewedJournalSha256() == null
                || request.target() == null || request.target().length != 3
                || request.requestedAtEpochMilli() <= 0
                || !request.checkpointSha256().equals(checkpointHash())
                || !request.reviewedJournalSha256().equals(sha256(JOURNAL_PATH)))
            throw new IOException("return recovery request does not match journals");
        ContainerIndex index = (ContainerIndex) field(module, "index");
        if (!reviewedFirstTransferEligible(job) || !checkpointMatches(job, false))
            throw new IOException("reviewed journal or kit checkpoint changed");
        if (!inventoryMatches(job, 0))
            throw new IOException("protected inventory differs from reviewed journal");
        if (index == null || !index.isImportChest(request.target()[0],
                request.target()[1], request.target()[2])
                || !targetMatches(module, request.target()))
            throw new IOException("registered import footprint or scan trust failed");
        if (INVENTORY.hasActiveRequest()
                || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0
                || hasCursor() || BARITONE.getCustomGoalProcess().isActive())
            throw new IOException("inventory or route is busy");
        persist(new KitTerminalReturnJournal.Job(job.schemaVersion(), job.kitName(),
                job.checkpointSha256(), request.target(), job.protectedSlots(),
                job.otherSlots(), job.order(), 0, -1,
                KitTerminalReturnJournal.Phase.READY, null, System.currentTimeMillis()));
        Files.delete(RECOVERY_REQUEST);
        report("reviewed terminal return rearmed for verified import");
    }

    static boolean reviewedFirstTransferEligible(KitTerminalReturnJournal.Job reviewed) {
        return reviewed != null
                && reviewed.phase() == KitTerminalReturnJournal.Phase.REVIEW_REQUIRED
                && reviewed.nextIndex() == 0 && !reviewed.order().isEmpty()
                && reviewed.activeRawSlot() == reviewed.order().getFirst()
                && "exporter_export_postclose_verification_mismatch"
                    .equals(reviewed.blocker());
    }

    private static boolean terminalSnapshot(KitFulfillmentJournalStore.Snapshot snapshot) {
        return snapshot != null && "BLOCKED".equals(snapshot.state())
                && "kit_displaced_apples_require_reconciliation".equals(snapshot.blocker())
                && snapshot.completedBoxes() + snapshot.incompleteBoxes() == snapshot.requestedBoxes()
                && snapshot.requestedBoxes() > 0
                && snapshot.displacedAppleSlots() != null && !snapshot.displacedAppleSlots().isEmpty()
                && snapshot.sourceBoxesToReturn().isEmpty()
                && snapshot.kitWorksite() == null && snapshot.borrowedSourceWorksite() == null
                && snapshot.retainedShell() == null && snapshot.heldBoxRebase() == null
                && snapshot.protectedLoose() == null;
    }

    private static boolean checkpointMatches(KitTerminalReturnJournal.Job saved,
                                             boolean allowCleared) throws Exception {
        if (!Files.exists(CHECKPOINT, LinkOption.NOFOLLOW_LINKS)) return allowCleared;
        if (!saved.checkpointSha256().equals(checkpointHash())) return false;
        var snapshot = KitFulfillmentJournalStore.defaultStore().load().orElse(null);
        return terminalSnapshot(snapshot) && saved.kitName().equals(snapshot.kitName())
                && saved.protectedSlots().equals(snapshot.displacedAppleSlots());
    }

    private static boolean inventoryMatches(KitTerminalReturnJournal.Job saved, int completed) {
        Container inventory = player();
        if (inventory == null) return false;
        for (int i = 0; i < saved.order().size(); i++) {
            int raw = saved.order().get(i);
            ItemStack live = inventory.getItemStack(raw);
            if (i < completed) {
                if (live != null && live.getAmount() > 0) return false;
            } else if (!saved.protectedSlots().get(raw)
                    .equals(KitInventoryAppleOwnership.evidence(live))) return false;
        }
        for (int raw = 9; raw <= 44; raw++) {
            if (saved.protectedSlots().containsKey(raw)) continue;
            if (!fingerprintMatches(saved.otherSlots().get(raw),
                    inventory.getItemStack(raw))) return false;
        }
        return !hasCursor();
    }

    private static Map<Integer, ItemStack> protectedLive(
            Map<Integer, KitInventoryAppleOwnership.Slot> slots) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int raw : slots.keySet()) result.put(raw, player().getItemStack(raw));
        return result;
    }

    static boolean fingerprintMatches(KitTerminalReturnJournal.StackFingerprint expected,
                                      ItemStack live) {
        if (expected == null) return live == null || live.getAmount() <= 0;
        if (live == null || live.getAmount() != expected.amount()
                || !expected.itemId().equals(ItemIdentifier.getItemId(live))) return false;
        if (expected.equals(fingerprint(live))) return true;
        DataComponents components = live.getDataComponents();
        if (components == null || components.getDataComponents().size() > 5) return false;
        var entries = new ArrayList<>(components.getDataComponents().entrySet());
        int[] order = new int[entries.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        do {
            var ordered = new LinkedHashMap<>(components.getDataComponents());
            ordered.clear();
            for (int index : order) {
                var entry = entries.get(index);
                ordered.put(entry.getKey(), entry.getValue());
            }
            if (expected.componentHash().equals(componentHash(live.getId(),
                    new DataComponents(ordered)))) return true;
        } while (nextPermutation(order));
        return false;
    }

    private static boolean nextPermutation(int[] order) {
        int pivot = order.length - 2;
        while (pivot >= 0 && order[pivot] >= order[pivot + 1]) pivot--;
        if (pivot < 0) return false;
        int swap = order.length - 1;
        while (order[swap] <= order[pivot]) swap--;
        int value = order[pivot];
        order[pivot] = order[swap];
        order[swap] = value;
        for (int left = pivot + 1, right = order.length - 1; left < right; left++, right--) {
            value = order[left];
            order[left] = order[right];
            order[right] = value;
        }
        return true;
    }

    static KitTerminalReturnJournal.StackFingerprint fingerprint(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;
        return new KitTerminalReturnJournal.StackFingerprint(
                ItemIdentifier.getItemId(stack), stack.getAmount(),
                componentHash(stack.getId(), stack.getDataComponents()));
    }

    private static String componentHash(int itemId, Object components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(itemId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(components)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean ownedKitBoxStillPresent(
            KitFulfillmentJournalStore.Snapshot snapshot, Container inventory) {
        if (snapshot.packedContents().isEmpty()) return false;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = inventory.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                        .contains("shulker_box")
                    && snapshot.packedContents().equals(ItemIdentifier.readShulkerContents(stack)))
                return true;
        }
        return false;
    }

    private static boolean targetMatches(StashManagerModule module, int[] target) throws Exception {
        DatabaseManager database = (DatabaseManager) field(module, "database");
        ContainerIndex index = (ContainerIndex) field(module, "index");
        if (database == null || !database.isInitialized() || index == null) return false;
        if (!Boolean.TRUE.equals(field(module, "latestScanTrusted"))) {
            StashManagerConfig config = (StashManagerConfig) field(module, "config");
            if (config == null || !freshTargetInSingleFailureScan(
                    database.getLatestScanSummary(config.pos1, config.pos2).orElse(null),
                    index.get(target[0], target[1], target[2]))) return false;
        }
        int[] assigned = database.loadColumnAssignments().get(
                StorageClassPolicy.route("enchanted_golden_apple"));
        return indexedTargetAllowed(index, target, assigned);
    }

    static boolean freshTargetInSingleFailureScan(DatabaseManager.ScanSummary summary,
                                                   ContainerEntry target) {
        return summary != null && target != null
                && "partial".equalsIgnoreCase(summary.completionStatus())
                && summary.found() > 0 && summary.indexed() == summary.found() - 1
                && summary.failed() == 1 && summary.startedAtEpochMilli() > 0
                && target.timestamp() >= summary.startedAtEpochMilli();
    }

    static boolean indexedTargetAllowed(ContainerIndex index, int[] target, int[] assigned) {
        if (index == null || target == null || target.length != 3) return false;
        boolean assignedLane = Arrays.equals(target, assigned);
        boolean registeredImport = index.isImportChest(target[0], target[1], target[2]);
        if (!assignedLane && !registeredImport) return false;
        ContainerEntry entry = index.get(target[0], target[1], target[2]);
        if (entry == null || !entry.inventoryFootprintKnown() || !entry.isDouble()
                || !"minecraft:chest".equals(entry.blockType())) return false;
        int x = entry.inventoryX(), y = entry.inventoryY(), z = entry.inventoryZ();
        if (!"X".equals(entry.doubleChestAxis())
                && !"Z".equals(entry.doubleChestAxis())) return false;
        for (int half = 0; half < 2; half++) {
            int hx = x + ("X".equals(entry.doubleChestAxis()) ? half : 0);
            int hz = z + ("Z".equals(entry.doubleChestAxis()) ? half : 0);
            ContainerEntry indexedHalf = index.get(hx, y, hz);
            ContainerEntry below = index.get(hx, y - 1, hz);
            if (indexedHalf != null && (!indexedHalf.inventoryFootprintKnown()
                    || indexedHalf.inventoryKey() != entry.inventoryKey()
                    || index.isOrganizerExcluded(indexedHalf))
                    || (registeredImport && !index.isImportChest(hx, y, hz))
                    || (below != null && "minecraft:hopper".equals(below.blockType()))) return false;
        }
        return true;
    }

    /** Check both live chest halves just before the first inventory click. */
    private static boolean liveTargetHasStableChest(StashManagerModule module, int[] target) {
        try {
            if (!World.isChunkLoadedBlockPos(target[0], target[2])
                    || World.getChunkSection(target[0], target[1], target[2]) == null)
                return false;
            var identity = DoubleChestIdentity.resolve(target[0], target[1], target[2], true);
            ContainerIndex index = (ContainerIndex) field(module, "index");
            ContainerEntry indexed = index.get(target[0], target[1], target[2]);
            if (!identity.identityKnown() || identity.blocks().size() != 2
                    || indexed == null || identity.inventoryX() != indexed.inventoryX()
                    || identity.inventoryY() != indexed.inventoryY()
                    || identity.inventoryZ() != indexed.inventoryZ()) return false;
            for (int[] half : identity.blocks()) {
                int x = half[0], y = half[1], z = half[2];
                if (!World.isChunkLoadedBlockPos(x, z)
                        || World.getChunkSection(x, y - 1, z) == null
                        || !"chest".equals(World.getBlockState(x, y, z).block().name())
                        || "hopper".equals(World.getBlockState(x, y - 1, z).block().name()))
                    return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean idle(StashManagerModule module) throws Exception {
        Object scanState = field(module, "state");
        if (scanState == null || !"IDLE".equals(scanState.toString())) return false;
        for (String name : List.of("organizer", "retriever", "kitFulfillment", "kitRecovery")) {
            Object component = field(module, name);
            if (component != null) {
                Method active = component.getClass().getMethod("isActive");
                if (Boolean.TRUE.equals(active.invoke(component))) return false;
            }
        }
        return true;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean worldReady() {
        return Proxy.getInstance().isConnected()
                && CACHE.getPlayerCache() != null
                && CACHE.getPlayerCache().getThePlayer() != null
                && CACHE.getPlayerCache().getThePlayer().isAlive()
                && player() != null;
    }

    private static Container player() {
        return CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
    }

    private static boolean hasCursor() {
        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        return cursor != null && cursor.getAmount() > 0;
    }

    private static String checkpointHash() throws Exception {
        if (Files.isSymbolicLink(CHECKPOINT)) throw new IOException("unsafe checkpoint path");
        byte[] bytes = Files.readAllBytes(CHECKPOINT);
        if (bytes.length > 256 * 1024) throw new IOException("checkpoint too large");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 16 * 1024) throw new IOException("unsafe return journal path");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private static void persist(KitTerminalReturnJournal.Job next) throws IOException {
        JOURNAL.save(next);
        job = next;
    }

    private static void review(String reason) throws IOException {
        if (exporter != null && exporter.isActive()) exporter.abortForRecovery(reason);
        exporter = null;
        if (job != null && job.phase() != KitTerminalReturnJournal.Phase.REVIEW_REQUIRED)
            persist(job.with(job.nextIndex(), job.activeRawSlot(),
                    KitTerminalReturnJournal.Phase.REVIEW_REQUIRED, reason));
        report("terminal return blocked: " + reason);
    }

    private static void report(String message) {
        if (!message.equals(lastReported)) {
            System.out.println("[StashManager] " + message);
            lastReported = message;
        }
    }
}
