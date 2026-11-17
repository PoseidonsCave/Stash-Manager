package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Read-only proof that the already-held kit shell is the exact apple-budget exchange result. */
public final class KitAppleBudgetVariantEvidence {
    private KitAppleBudgetVariantEvidence() { }

    public record Proof(int shellSlot, int displacedSlot, String resultShellHash) { }

    public static Proof prove(KitFulfillmentJournalStore.Snapshot checkpoint,
                              Map<Integer, ItemStack> player) {
        if (checkpoint == null || player == null || checkpoint.variantRepair() == null
                || checkpoint.kitWorksite() == null && checkpoint.variantRepair()
                    .resultShellHash() == null
                || !"enchanted_golden_apple".equals(checkpoint.currentItem())
                || checkpoint.currentQuantity() != 128
                || checkpoint.packedContents().getOrDefault("end_crystal", 0) != 256
                || checkpoint.sourceBoxesToReturn().size() != 2) return null;
        var repair = checkpoint.variantRepair();
        if (repair.replacementOnly() || repair.cargoRawSlot() != -1
                || repair.fingerprintVersion() != 2 || repair.amount() != 10
                || !"end_crystal".equals(repair.itemId())
                || repair.outgoingSlot() == repair.mergeTargetSlot()
                || repair.outgoingSlot() < 0 || repair.outgoingSlot() >= 27
                || repair.mergeTargetSlot() < 0 || repair.mergeTargetSlot() >= 27
                || repair.auditedIncomingRawSlot() < 9
                || repair.auditedIncomingRawSlot() > 35
                || occupied(player.get(repair.auditedIncomingRawSlot()))) return null;
        int alreadyPacked = checkpoint.packedContents().getOrDefault(
                "enchanted_golden_apple", 0);
        if (alreadyPacked != 0 && alreadyPacked != 128) return null;
        if (alreadyPacked == 0 && (checkpoint.auditedCargoSlots().size() != 2
                || checkpoint.auditedCargoSlots().values().stream()
                    .anyMatch(amount -> amount == null || amount != 64)
                || checkpoint.auditedCargoSlots().keySet().stream()
                    .anyMatch(raw -> occupied(player.get(raw))))) return null;
        if (alreadyPacked == 128 && !checkpoint.auditedCargoSlots().isEmpty()) return null;

        Map<String, Integer> expected = new HashMap<>(checkpoint.packedContents());
        expected.put("enchanted_golden_apple", 128);
        int displacedRaw = -1;
        ItemStack displaced = null;
        for (int raw = 9; raw <= 35; raw++) {
            ItemStack stack = player.get(raw);
            if (!occupied(stack) || stack.getAmount() != 10
                    || !"end_crystal".equals(ItemIdentifier.getItemId(stack))
                    || !repair.outgoingHash().equals(KitVariantFingerprint.variant(
                            stack, repair.fingerprintVersion()))) continue;
            if (displacedRaw >= 0) return null;
            displacedRaw = raw;
            displaced = stack;
        }
        if (displacedRaw < 9) return null;

        Proof found = null;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack shell = player.get(raw);
            if (!occupied(shell) || shell.getAmount() != 1
                    || !ItemIdentifier.baseItemId(ItemIdentifier.getItemId(shell))
                        .endsWith("shulker_box")
                    || !expected.equals(ItemIdentifier.readShulkerContents(shell))) continue;
            Map<Integer, ItemStack> live = ItemIdentifier.readShulkerPhysicalSlotStacks(shell);
            if (live.size() != 27) continue;
            Map<Integer, ItemStack> before = new HashMap<>(live);
            int appleStacks = 0;
            for (var entry : live.entrySet()) {
                ItemStack stack = entry.getValue();
                if (!"enchanted_golden_apple".equals(ItemIdentifier.getItemId(stack))) continue;
                if (stack.getAmount() != 64 || !repair.cargoHash().equals(
                        KitVariantFingerprint.variant(stack, repair.fingerprintVersion()))) {
                    appleStacks = -1;
                    break;
                }
                before.remove(entry.getKey());
                appleStacks++;
            }
            if (appleStacks != 2 || before.size() != 25
                    || before.containsKey(repair.outgoingSlot())) continue;
            ItemStack merged = before.get(repair.mergeTargetSlot());
            if (!occupied(merged) || merged.getAmount() <= repair.amount()
                    || !"end_crystal".equals(ItemIdentifier.getItemId(merged))) continue;
            ItemStack anchor = new ItemStack(merged.getId(),
                    merged.getAmount() - repair.amount(), merged.getDataComponents());
            if (!repair.sourceHash().equals(KitVariantFingerprint.variant(
                    anchor, repair.fingerprintVersion()))) continue;
            before.put(repair.mergeTargetSlot(), anchor);
            before.put(repair.outgoingSlot(), displaced);
            if (!repair.shellHash().equals(KitVariantFingerprint.shell(
                    before, repair.fingerprintVersion()))) continue;
            String result = KitVariantFingerprint.shell(live, repair.fingerprintVersion());
            if (result == null || repair.resultShellHash() != null
                    && !repair.resultShellHash().equals(result) || found != null) return null;
            found = new Proof(raw, displacedRaw, result);
        }
        return found;
    }

    private static boolean occupied(ItemStack stack) {
        return stack != null && stack.getAmount() > 0;
    }
}
