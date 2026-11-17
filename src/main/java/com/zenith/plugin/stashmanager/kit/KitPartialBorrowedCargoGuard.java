package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fail-closed audit of an operator-attested kept stack beside partial borrowed-box cargo. */
public final class KitPartialBorrowedCargoGuard {
    private KitPartialBorrowedCargoGuard() { }

    public record Result(KitFulfillmentJournalStore.ProtectedLoose witness, String blocker) {
        public boolean accepted() { return witness != null && blocker == null; }
    }

    public static Result inspect(KitFulfillmentJournalStore.Snapshot snapshot,
                                 Map<Integer, ItemStack> inventory, int ownedRaw,
                                 int sourceRaw, int keptRaw, int keptCount,
                                 Map<Integer, Integer> cargo) {
        if (!KitResumePolicy.canAuditUnverifiedBorrowedCargo(snapshot)
                || inventory == null || cargo == null || cargo.isEmpty()
                || ownedRaw < 9 || ownedRaw > 44 || sourceRaw < 9 || sourceRaw > 44
                || keptRaw < 9 || keptRaw > 44 || keptCount < 1 || keptCount > 64
                || keptRaw == ownedRaw || keptRaw == sourceRaw || ownedRaw == sourceRaw) {
            return reject("checkpoint or split-slot boundary is invalid");
        }
        int observed = observedUnits(snapshot.blocker(), snapshot.currentQuantity());
        if (observed < 1 || observed >= snapshot.currentQuantity()
                || keptCount != snapshot.currentQuantity() - observed
                || snapshot.sourceBoxesToReturn().getFirst().contents().size() != 1
                || snapshot.sourceBoxesToReturn().getFirst().contents()
                        .getOrDefault(snapshot.currentItem(), 0) < 1) {
            return reject("attested kept amount does not match the saved mismatch");
        }
        ItemStack kept = inventory.get(keptRaw);
        if (!exact(kept, snapshot.currentItem(), keptCount))
            return reject("kept slot is not the exact attested stack");
        String variant = KitVariantFingerprint.canonicalVariant(kept);
        if (variant == null) return reject("kept stack variant is unavailable");
        int cargoTotal = 0;
        Set<Integer> admitted = new HashSet<>(cargo.keySet());
        admitted.add(keptRaw);
        for (var entry : cargo.entrySet()) {
            Integer raw = entry.getKey();
            Integer count = entry.getValue();
            if (raw == null || raw < 9 || raw > 44 || raw == keptRaw
                    || raw == ownedRaw || raw == sourceRaw || count == null
                    || count < 1 || count > 64
                    || !exact(inventory.get(raw), snapshot.currentItem(), count)
                    || !variant.equals(KitVariantFingerprint.canonicalVariant(
                            inventory.get(raw)))) {
                return reject("cargo slot, count, or variant differs");
            }
            cargoTotal += count;
        }
        if (cargo.size() < 2 || cargoTotal != observed)
            return reject("pinned cargo does not equal the observed partial batch");
        for (var entry : inventory.entrySet()) {
            ItemStack stack = entry.getValue();
            if (!admitted.contains(entry.getKey()) && stack != null
                    && stack.getAmount() > 0
                    && snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))) {
                return reject("unassigned same-item inventory remains");
            }
        }
        var source = snapshot.sourceBoxesToReturn().getFirst();
        ItemStack sourceBox = inventory.get(sourceRaw);
        if (sourceBox == null || sourceBox.getAmount() != 1
                || !source.itemId().equals(ItemIdentifier.getItemId(sourceBox))
                || !source.contents().equals(ItemIdentifier.readShulkerContents(sourceBox))) {
            return reject("borrowed source box does not match the journal");
        }
        var sourceStacks = ItemIdentifier.readShulkerPhysicalSlotStacks(sourceBox);
        if (sourceStacks.isEmpty() || sourceStacks.values().stream().anyMatch(stack ->
                !snapshot.currentItem().equals(ItemIdentifier.getItemId(stack))
                || !variant.equals(KitVariantFingerprint.canonicalVariant(stack)))) {
            return reject("borrowed source contains a different physical variant");
        }
        return new Result(new KitFulfillmentJournalStore.ProtectedLoose(
                keptRaw, snapshot.currentItem(), keptCount, variant,
                snapshot.currentQuantity()), null);
    }

    public static int observedUnits(String blocker, int expected) {
        String prefix = "kit_retrieved_cargo_unverified:";
        if (blocker == null || !blocker.startsWith(prefix)) return -1;
        String[] counts = blocker.substring(prefix.length()).split("/", -1);
        if (counts.length != 2) return -1;
        try {
            int observed = Integer.parseInt(counts[0]);
            int savedExpected = Integer.parseInt(counts[1]);
            return observed > 0 && observed < expected && savedExpected == expected
                    ? observed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Recheck the persisted keep witness immediately before any audited pack resumes. */
    public static boolean verifiedScheduled(KitFulfillmentJournalStore.Snapshot snapshot,
                                            Map<Integer, ItemStack> inventory) {
        if (snapshot == null || inventory == null || snapshot.protectedLoose() == null
                || !"resume_audited_inventory_cargo".equals(snapshot.blocker())
                || !"WAIT_CARGO".equals(snapshot.resumeState())
                || snapshot.sourceBoxesToReturn() == null
                || snapshot.sourceBoxesToReturn().size() != 1) return false;
        var kept = snapshot.protectedLoose();
        if (!verifiedActive(kept, snapshot.auditedCargoSlots(), inventory)) return false;
        var source = snapshot.sourceBoxesToReturn().getFirst();
        int matchedSources = 0;
        for (var entry : inventory.entrySet()) {
            ItemStack box = entry.getValue();
            if (box == null || box.getAmount() != 1
                    || !source.itemId().equals(ItemIdentifier.getItemId(box))
                    || !source.contents().equals(ItemIdentifier.readShulkerContents(box))) continue;
            matchedSources++;
            var sourceStacks = ItemIdentifier.readShulkerPhysicalSlotStacks(box);
            if (sourceStacks.isEmpty() || sourceStacks.values().stream().anyMatch(stack ->
                    !kept.itemId().equals(ItemIdentifier.getItemId(stack))
                    || !kept.variantHash().equals(
                            KitVariantFingerprint.canonicalVariant(stack)))) return false;
        }
        return matchedSources == 1;
    }

    public static boolean verifiedActive(KitFulfillmentJournalStore.ProtectedLoose kept,
                                         Map<Integer, Integer> cargo,
                                         Map<Integer, ItemStack> inventory) {
        if (kept == null || kept.itemId() == null || !verifiedKept(kept, inventory)
                || cargo == null || cargo.isEmpty()) return false;
        for (var entry : cargo.entrySet()) {
            ItemStack stack = inventory.get(entry.getKey());
            if (entry.getValue() == null || !exact(stack, kept.itemId(), entry.getValue())
                    || !kept.variantHash().equals(
                            KitVariantFingerprint.canonicalVariant(stack))) return false;
        }
        for (var entry : inventory.entrySet()) {
            if (entry.getKey() == kept.rawSlot() || cargo.containsKey(entry.getKey())) continue;
            ItemStack stack = entry.getValue();
            if (stack != null && stack.getAmount() > 0
                    && kept.itemId().equals(ItemIdentifier.getItemId(stack))) return false;
        }
        return true;
    }

    public static boolean verifiedKept(KitFulfillmentJournalStore.ProtectedLoose kept,
                                       Map<Integer, ItemStack> inventory) {
        if (kept == null || inventory == null || kept.variantHash() == null) return false;
        ItemStack stack = inventory.get(kept.rawSlot());
        return exact(stack, kept.itemId(), kept.amount())
                && kept.variantHash().equals(KitVariantFingerprint.canonicalVariant(stack));
    }

    private static boolean exact(ItemStack stack, String itemId, int count) {
        return stack != null && stack.getAmount() == count
                && itemId.equals(ItemIdentifier.getItemId(stack));
    }

    private static Result reject(String reason) { return new Result(null, reason); }
}
