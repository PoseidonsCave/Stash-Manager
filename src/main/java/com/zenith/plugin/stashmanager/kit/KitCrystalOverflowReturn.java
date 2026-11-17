package com.zenith.plugin.stashmanager.kit;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.organizer.KitExporter;
import com.zenith.plugin.stashmanager.organizer.KitFulfillmentCoordinator;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/** One fail-stop return of an excess crystal stack before an audited 250-unit pack. */
public final class KitCrystalOverflowReturn {
    private static final Path CHECKPOINT = Path.of("plugins", "config",
            "stash-manager-kit-fulfillment-checkpoint.json");
    private static final Path PREPARED = Path.of("plugins", "config",
            "stash-manager-crystal-overflow-return.prepared");
    private static final Path SUBMITTED = Path.of("plugins", "config",
            "stash-manager-crystal-overflow-return.submitted");
    private static final Path COMPLETE = Path.of("plugins", "config",
            "stash-manager-crystal-overflow-return.complete");

    private static boolean active;
    private static int outgoingRaw;
    private static String checkpointHash;
    private static Map<Integer, ItemStack> relevantBefore = Map.of();
    private static Map<Integer, Integer> retainedCargo = Map.of();
    private static int[] target;
    private static long cooldownMillis;

    private KitCrystalOverflowReturn() { }

    public static boolean pending() {
        return active || Files.exists(PREPARED) || Files.exists(SUBMITTED);
    }

    /** The caller has already checked the registered import and exact live boundary. */
    public static String schedule(KitFulfillmentJournalStore.Snapshot snapshot,
                                  Map<Integer, ItemStack> relevant,
                                  Map<Integer, Integer> retained,
                                  int raw, int[] importTarget,
                                  KitExporter exporter, long cooldown) {
        if (pending()) return "a crystal return already requires review";
        if (snapshot == null || relevant == null || retained == null
                || importTarget == null || importTarget.length != 3
                || raw < 9 || raw > 44 || retained.size() != 4
                || retained.values().stream().mapToInt(Integer::intValue).sum() != 250
                || exporter == null || exporter.isActive()) return "invalid return plan";
        ItemStack outgoing = relevant.get(raw);
        if (outgoing == null || outgoing.getAmount() != 6
                || !"end_crystal".equals(ItemIdentifier.getItemId(outgoing)))
            return "six-crystal outgoing stack changed";
        try {
            String hash = sha256(CHECKPOINT);
            String witness = "schema=1\ncheckpoint=" + hash + "\nkit="
                    + snapshot.kitName() + "\ncompleted=" + snapshot.completedBoxes()
                    + "\noutgoingRaw=" + raw + "\noutgoingVariant="
                    + KitVariantFingerprint.canonicalVariant(outgoing)
                    + "\noutgoingAmount=6\nretained=" + retained
                    + "\nimport=" + Arrays.toString(importTarget) + "\n";
            Files.writeString(PREPARED, witness, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            if (!exporter.startProtectedLooseReturn(importTarget, outgoing, raw, 54))
                return "return could not start; prepared witness requires review";
            outgoingRaw = raw;
            relevantBefore = Map.copyOf(relevant);
            retainedCargo = Map.copyOf(retained);
            target = importTarget.clone();
            checkpointHash = hash;
            cooldownMillis = Math.max(1_000L, cooldown);
            active = true;
            return null;
        } catch (Exception e) {
            return "could not journal crystal return: " + e.getMessage();
        }
    }

    /** Returns true while this one-shot operation owns the kit checkpoint. */
    public static boolean tick(KitExporter exporter, KitFulfillmentJournalStore journal) {
        if (!pending()) return false;
        if (!active) return true; // A restart must never replay an uncertain click.
        try {
            if (!Proxy.getInstance().isConnected() || Proxy.getInstance().hasActivePlayer()
                    || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
                BARITONE.stop();
                active = false;
                return true;
            }
            if (!checkpointHash.equals(sha256(CHECKPOINT))) {
                BARITONE.stop();
                active = false;
                return true;
            }
            if (exporter.state() == KitExporter.State.DEPOSITING
                    && Files.exists(PREPARED)) {
                if (!matchesBefore()) {
                    BARITONE.stop();
                    active = false;
                    return true;
                }
                Files.move(PREPARED, SUBMITTED, StandardCopyOption.ATOMIC_MOVE);
            }
            exporter.tick();
            if (exporter.state() == KitExporter.State.FAILED) {
                active = false;
                return true;
            }
            if (exporter.state() != KitExporter.State.DONE) return true;
            if (!Files.exists(SUBMITTED) || !matchesAfter()) {
                active = false;
                return true;
            }
            var old = journal.load().orElse(null);
            if (old == null || !"BLOCKED".equals(old.state())
                    || !"kit_packer_start_failed".equals(old.blocker())
                    || old.completedBoxes() != 22
                    || !"end_crystal".equals(old.currentItem())
                    || old.currentQuantity() != 256) {
                active = false;
                return true;
            }
            var scheduled = new KitFulfillmentJournalStore.Snapshot(
                    old.schemaVersion(), old.kitName(), "BLOCKED", old.recipe(),
                    old.packedContents(), old.requestedBoxes(), old.completedBoxes(),
                    old.currentItem(), 250, old.ownedShellSlot(), old.exportPosition(),
                    old.kitWorksite(), old.borrowedSourceWorksite(),
                    old.borrowedSourceItemId(), old.borrowedSourceContents(),
                    "resume_audited_inventory_cargo", System.currentTimeMillis(),
                    "WAIT_CARGO", System.currentTimeMillis() + cooldownMillis,
                    old.sourceBoxesToReturn(), -1, old.writtenOffSources(),
                    retainedCargo, old.incompleteBoxes(), old.missingTotals(),
                    old.currentBoxMissing(), old.displacedAppleSlots(),
                    old.retainedShell(), old.variantRepair(), old.protectedLoose(),
                    old.heldBoxRebase());
            if (!KitFulfillmentCoordinator.verifiedAuditedCargo(scheduled)
                    || !KitFulfillmentCoordinator.verifiedVariantPendingSources(scheduled)) {
                active = false;
                return true;
            }
            journal.save(scheduled);
            Files.move(SUBMITTED, COMPLETE, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            active = false;
            return false;
        } catch (Exception e) {
            BARITONE.stop();
            active = false;
            return true;
        }
    }

    private static boolean matchesBefore() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null || INVENTORY.hasActiveRequest()) return false;
        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        if (cursor != null && cursor.getAmount() > 0) return false;
        for (var entry : relevantBefore.entrySet())
            if (!same(entry.getValue(), player.getItemStack(entry.getKey()))) return false;
        return true;
    }

    private static boolean matchesAfter() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return false;
        ItemStack returned = player.getItemStack(outgoingRaw);
        if (returned != null && returned.getAmount() > 0) return false;
        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        if (cursor != null && cursor.getAmount() > 0) return false;
        for (var entry : relevantBefore.entrySet()) {
            if (entry.getKey() == outgoingRaw) continue;
            if (!same(entry.getValue(), player.getItemStack(entry.getKey()))) return false;
        }
        return target != null;
    }

    private static boolean same(ItemStack expected, ItemStack live) {
        return expected != null && live != null && expected.getId() == live.getId()
                && expected.getAmount() == live.getAmount()
                && Objects.equals(expected.getDataComponents(), live.getDataComponents());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
