package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Exact one-stack normalization at a witnessed physical kit capacity boundary. */
public final class KitPackedStackCapacityRepair {
    private KitPackedStackCapacityRepair() { }

    public record Profile(String itemId, int quantity, int physicalSlots,
                          int allowedSlots) { }

    /** Only these observed, fully packed batches may enter the guarded exchange. */
    public static Profile profile(String itemId) {
        if (itemId == null) return null;
        return switch (itemId) {
            case "obsidian" -> new Profile(itemId, 128, 24, 23);
            case "ender_chest" -> new Profile(itemId, 16, 19, 18);
            case "firework_rocket" -> new Profile(itemId, 576, 19, 18);
            default -> null;
        };
    }

    public static boolean matchesPackedBatch(String itemId, String currentItem,
            int currentQuantity, Map<String, Integer> recipe,
            Map<String, Integer> packedContents) {
        Profile profile = profile(itemId);
        return profile != null && recipe != null && packedContents != null
                && itemId.equals(currentItem)
                && currentQuantity == profile.quantity()
                && recipe.getOrDefault(itemId, 0) == profile.quantity()
                && packedContents.getOrDefault(itemId, 0) == profile.quantity();
    }

    public record PackedProof(int displacedRawSlot, String resultShellHash) { }

    /** Prove a completed one-stack exchange after an inventory behavior moved the
     * displaced stack out of the packer's original destination slot. */
    public static PackedProof proveAlreadyPacked(
            KitFulfillmentJournalStore.Snapshot snapshot,
            Map<Integer, ItemStack> shellSlots,
            Map<Integer, ItemStack> playerSlots) {
        if (!repairCheckpoint(snapshot) || !"BLOCKED".equals(snapshot.state())
                || !"kit_variant_packed_evidence_unverified".equals(snapshot.blocker())
                || snapshot.resumeState() != null || snapshot.kitWorksite() == null
                || snapshot.borrowedSourceWorksite() != null
                || snapshot.sourceBoxesToReturn().size() != 1
                || shellSlots == null || shellSlots.size()
                    != profile(snapshot.currentItem()).physicalSlots()
                || playerSlots == null)
            return null;
        var repair = snapshot.variantRepair();
        if (repair.displacedRawSlot() != -1 || repair.resultShellHash() != null)
            return null;
        Map<String, Integer> contents = new HashMap<>();
        for (var stack : shellSlots.values()) {
            if (stack == null || stack.getAmount() < 1) return null;
            contents.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
        }
        if (!contents.equals(snapshot.packedContents())) return null;
        ItemStack incoming = shellSlots.get(repair.outgoingSlot());
        if (incoming == null || incoming.getAmount() != repair.amount()
                || !repair.sourceHash().equals(KitVariantFingerprint.variant(
                        incoming, repair.fingerprintVersion()))) return null;
        int displacedRaw = -1;
        ItemStack displaced = null;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == snapshot.ownedShellSlot()) continue;
            ItemStack stack = playerSlots.get(raw);
            if (stack == null || stack.getAmount() != repair.amount()
                    || !repair.itemId().equals(ItemIdentifier.getItemId(stack))
                    || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                            stack, repair.fingerprintVersion()))) continue;
            if (displacedRaw >= 0) return null;
            displacedRaw = raw;
            displaced = stack;
        }
        if (displacedRaw < 9) return null;
        Map<Integer, ItemStack> before = new HashMap<>(shellSlots);
        before.put(repair.outgoingSlot(), displaced);
        if (!repair.shellHash().equals(KitVariantFingerprint.shell(
                before, repair.fingerprintVersion()))
                || !KitPhysicalSlotBudget.fitsAfterCompaction(shellSlots.size(),
                    List.copyOf(shellSlots.values()), snapshot.recipe(),
                    snapshot.packedContents())) return null;
        String resultHash = KitVariantFingerprint.shell(shellSlots,
                repair.fingerprintVersion());
        return resultHash == null ? null : new PackedProof(displacedRaw, resultHash);
    }

    /** Read-only reason for a refused proof; never relaxes the proof itself. */
    public static String explainAlreadyPacked(
            KitFulfillmentJournalStore.Snapshot snapshot,
            Map<Integer, ItemStack> shellSlots,
            Map<Integer, ItemStack> playerSlots) {
        if (!repairCheckpoint(snapshot) || !"BLOCKED".equals(snapshot.state())
                || !"kit_variant_packed_evidence_unverified".equals(snapshot.blocker())
                || snapshot.resumeState() != null || snapshot.kitWorksite() == null
                || snapshot.borrowedSourceWorksite() != null
                || snapshot.sourceBoxesToReturn().size() != 1)
            return "checkpoint_boundary";
        var repair = snapshot.variantRepair();
        if (repair.displacedRawSlot() != -1 || repair.resultShellHash() != null)
            return "already_staged";
        if (shellSlots == null || shellSlots.size()
                != profile(snapshot.currentItem()).physicalSlots() || playerSlots == null)
            return "shell_slots_unreadable";
        Map<String, Integer> contents = new HashMap<>();
        for (var stack : shellSlots.values()) {
            if (stack == null || stack.getAmount() < 1) return "shell_stack_unreadable";
            contents.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
        }
        if (!contents.equals(snapshot.packedContents())) return "shell_contents_changed";
        ItemStack incoming = shellSlots.get(repair.outgoingSlot());
        if (incoming == null || incoming.getAmount() != repair.amount()
                || !repair.sourceHash().equals(KitVariantFingerprint.variant(
                        incoming, repair.fingerprintVersion())))
            return "incoming_shell_variant_mismatch";
        int matches = 0;
        ItemStack displaced = null;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == snapshot.ownedShellSlot()) continue;
            ItemStack stack = playerSlots.get(raw);
            if (stack == null || stack.getAmount() != repair.amount()
                    || !repair.itemId().equals(ItemIdentifier.getItemId(stack))
                    || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                            stack, repair.fingerprintVersion()))) continue;
            matches++;
            displaced = stack;
        }
        if (matches != 1) return matches == 0
                ? "displaced_variant_missing" : "displaced_variant_ambiguous";
        Map<Integer, ItemStack> before = new HashMap<>(shellSlots);
        before.put(repair.outgoingSlot(), displaced);
        if (!repair.shellHash().equals(KitVariantFingerprint.shell(
                before, repair.fingerprintVersion())))
            return "pre_exchange_shell_fingerprint_mismatch";
        if (!KitPhysicalSlotBudget.fitsAfterCompaction(shellSlots.size(),
                List.copyOf(shellSlots.values()), snapshot.recipe(),
                snapshot.packedContents())) return "physical_capacity_unresolved";
        return proveAlreadyPacked(snapshot, shellSlots, playerSlots) == null
                ? "unclassified_proof_failure" : "none";
    }

    public static boolean blockedBoundary(KitFulfillmentJournalStore.Snapshot snapshot) {
        return snapshot != null && "BLOCKED".equals(snapshot.state())
                && "kit_physical_slot_budget_exceeded".equals(snapshot.blocker())
                && snapshot.resumeState() == null
                && snapshot.completedBoxes() + snapshot.incompleteBoxes()
                    < snapshot.requestedBoxes()
                && snapshot.variantRepair() == null
                && snapshot.kitWorksite() == null
                && snapshot.borrowedSourceWorksite() == null
                && snapshot.sourceBoxesToReturn().isEmpty()
                && snapshot.auditedCargoSlot() == -1
                && snapshot.auditedCargoSlots().isEmpty()
                && snapshot.protectedLoose() == null
                && snapshot.heldBoxRebase() == null
                && snapshot.currentBoxMissing().isEmpty()
                && matchesPackedBatch(snapshot.currentItem(), snapshot.currentItem(),
                    snapshot.currentQuantity(), snapshot.recipe(),
                    snapshot.packedContents())
                && snapshot.ownedShellSlot() >= 9 && snapshot.ownedShellSlot() <= 44;
    }

    public static boolean repairCheckpoint(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.variantRepair() == null) return false;
        var repair = snapshot.variantRepair();
        return repair.replacementOnly() && repair.cargoRawSlot() == -1
                && repair.fingerprintVersion() == 2
                && repair.amount() > 0 && repair.amount() < 64
                && repair.cargoHash() != null
                && repair.cargoHash().equals(repair.sourceHash())
                && matchesPackedBatch(repair.itemId(), snapshot.currentItem(),
                    snapshot.currentQuantity(), snapshot.recipe(),
                    snapshot.packedContents())
                && snapshot.auditedCargoSlot() == -1
                && snapshot.auditedCargoSlots().isEmpty()
                && snapshot.currentBoxMissing().isEmpty();
    }

    public static KitVariantExchangePlan.Opportunity opportunity(
            Map<Integer, ItemStack> shellSlots, String itemId) {
        Profile profile = profile(itemId);
        if (profile == null || shellSlots == null
                || shellSlots.size() != profile.physicalSlots()) return null;
        List<KitVariantExchangePlan.SlotStack> physical = shellSlots.entrySet().stream()
                .map(e -> new KitVariantExchangePlan.SlotStack(e.getKey(), e.getValue()))
                .toList();
        var opportunity = KitVariantExchangePlan.shellOpportunity(
                physical, itemId, profile.quantity(), profile.allowedSlots());
        return opportunity != null
                && opportunity.slotsAfter() == profile.allowedSlots()
                && itemId.equals(ItemIdentifier.getItemId(
                        opportunity.mergeTargetStack()))
                ? opportunity : null;
    }
}
