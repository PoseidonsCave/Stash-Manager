package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only boundary for a borrowed box into which all loose stock was returned.
 * The arithmetic is evidence of balance, not evidence that old stock belongs to the kit.
 */
public final class KitHeldBorrowedBoxPolicy {
    private KitHeldBorrowedBoxPolicy() { }

    public record Plan(int sourceRaw, String sourceItemId,
                       int journaledSourceUnits, int combinedSourceUnits,
                       int retrievedUnits, int keptUnits, int requestedUnits,
                       String itemId, String variantHash,
                       Map<String, Integer> ownedContents) {
        public Plan { ownedContents = Map.copyOf(ownedContents); }
    }

    public record Result(Plan plan, String blocker) {
        public boolean accepted() { return plan != null && blocker == null; }
    }

    public enum Phase { COMBINED, TASK_EXTRACTED, EXTRACTED, SPLIT, IN_FLIGHT, CONFLICT }

    public static Plan fromWitness(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.heldBoxRebase() == null
                || snapshot.sourceBoxesToReturn().size() != 1) return null;
        return fromWitness(snapshot.heldBoxRebase(), snapshot.packedContents());
    }

    public static Plan fromWitness(KitFulfillmentJournalStore.HeldBoxRebase held,
                                   Map<String, Integer> packedContents) {
        if (held == null || packedContents == null) return null;
        return new Plan(-1, held.sourceItemId(), held.originalSourceUnits(),
                held.combinedSourceUnits(), held.retrievedUnits(), held.keptUnits(),
                held.retrievedUnits() + held.keptUnits(), held.itemId(),
                held.variantHash(), packedContents);
    }

    public static boolean verifiedScheduled(KitFulfillmentJournalStore.Snapshot snapshot,
                                            Map<Integer, ItemStack> inventory,
                                            int ownedRaw, int retainedRaw) {
        if (snapshot == null || snapshot.heldBoxRebase() == null
                || !"BLOCKED".equals(snapshot.state())
                || !"resume_held_box_rebase".equals(snapshot.blocker())
                || !"NEED_CARGO".equals(snapshot.resumeState())
                || snapshot.kitWorksite() != null
                || snapshot.borrowedSourceWorksite() != null) return false;
        Plan plan = fromWitness(snapshot);
        if (plan == null || snapshot.sourceBoxesToReturn().getFirst().contents()
                .getOrDefault(plan.itemId(), -1) != plan.combinedSourceUnits()) return false;
        if (observe(plan, inventory, ownedRaw) != Phase.COMBINED) return false;
        int sourceRaw = -1;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = inventory.get(raw);
            if (stack != null && stack.getAmount() > 0
                    && plan.sourceItemId().equals(ItemIdentifier.getItemId(stack))) {
                if (sourceRaw >= 0) return false;
                sourceRaw = raw;
            }
        }
        if (sourceRaw < 0 || sourceRaw == ownedRaw || sourceRaw == retainedRaw) return false;
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == ownedRaw || raw == sourceRaw || raw == retainedRaw) continue;
            ItemStack stack = inventory.get(raw);
            if (stack != null && stack.getAmount() > 0
                    && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                            .contains("shulker_box")) return false;
        }
        return true;
    }

    public static Result inspect(KitFulfillmentJournalStore.Snapshot snapshot,
                                 Map<Integer, ItemStack> inventory,
                                 int ownedRaw, int attestedKeptUnits) {
        return inspect(snapshot, inventory, ownedRaw, -1, attestedKeptUnits);
    }

    public static Result inspect(KitFulfillmentJournalStore.Snapshot snapshot,
                                 Map<Integer, ItemStack> inventory,
                                 int ownedRaw, int retainedRaw, int attestedKeptUnits) {
        if (!KitResumePolicy.canAuditUnverifiedBorrowedCargo(snapshot)
                || inventory == null || ownedRaw < 9 || ownedRaw > 44
                || attestedKeptUnits < 1 || attestedKeptUnits > 64
                || snapshot.variantRepair() != null || snapshot.protectedLoose() != null
                || snapshot.sourceBoxesToReturn().getFirst().sourcePosition() == null
                || snapshot.retainedShell() != null && (retainedRaw < 9
                        || retainedRaw > 44 || retainedRaw == ownedRaw))
            return reject("checkpoint or attestation boundary is invalid");
        int retrieved = KitPartialBorrowedCargoGuard.observedUnits(
                snapshot.blocker(), snapshot.currentQuantity());
        if (snapshot.currentQuantity() != 128 || retrieved <= 64
                || snapshot.currentQuantity() - retrieved != attestedKeptUnits)
            return reject("attested kept units differ from the saved retrieval mismatch");
        var source = snapshot.sourceBoxesToReturn().getFirst();
        if (source.contents().size() != 1) return reject("borrowed box has mixed journaled contents");
        int after = source.contents().getOrDefault(snapshot.currentItem(), -1);
        if (after < 0) return reject("journaled source does not hold the task item");
        int combined;
        try { combined = Math.addExact(after, snapshot.currentQuantity()); }
        catch (ArithmeticException e) { return reject("source balance overflows"); }
        if (combined > 27 * 64) return reject("combined stock exceeds shulker capacity");
        ItemStack shell = inventory.get(ownedRaw);
        if (shell == null || shell.getAmount() != 1
                || !ItemIdentifier.baseItemId(ItemIdentifier.getItemId(shell))
                        .contains("shulker_box")
                || !snapshot.packedContents().equals(ItemIdentifier.readShulkerContents(shell)))
            return reject("owned kit shell differs from the checkpoint");
        int sourceRaw = -1;
        String variant = null;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = inventory.get(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            String item = ItemIdentifier.getItemId(stack);
            if (snapshot.currentItem().equals(item))
                return reject("loose task item remains outside the borrowed box");
            if (raw == ownedRaw || !source.itemId().equals(item)) continue;
            Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
            if (!Map.of(snapshot.currentItem(), combined).equals(contents)) continue;
            if (stack.getAmount() != 1 || sourceRaw >= 0)
                return reject("combined borrowed box is not unique");
            variant = physicalVariant(stack, snapshot.currentItem(), combined);
            if (variant == null) return reject("combined box physical stacks or variant differ");
            sourceRaw = raw;
        }
        if (sourceRaw < 0) return reject("combined borrowed box was not found");
        if (sourceRaw == retainedRaw) return reject("reserved shell and source box overlap");
        for (int raw = 9; raw <= 44; raw++) {
            if (raw == ownedRaw || raw == sourceRaw || raw == retainedRaw) continue;
            ItemStack stack = inventory.get(raw);
            if (stack != null && stack.getAmount() > 0
                    && ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                            .contains("shulker_box"))
                return reject("unassigned shulker remains in inventory");
        }
        return new Result(new Plan(sourceRaw, source.itemId(), after, combined, retrieved,
                attestedKeptUnits, snapshot.currentQuantity(), snapshot.currentItem(), variant,
                snapshot.packedContents()),
                null);
    }

    /** Stable inventory observations only. A missing box is unsettled, never a credited move. */
    public static Phase observe(Plan plan, Map<Integer, ItemStack> inventory,
                                int ownedRaw) {
        if (plan == null || inventory == null || ownedRaw < 9 || ownedRaw > 44)
            return Phase.CONFLICT;
        ItemStack shell = inventory.get(ownedRaw);
        if (shell == null || shell.getAmount() != 1
                || !ItemIdentifier.baseItemId(ItemIdentifier.getItemId(shell))
                        .contains("shulker_box")
                || !plan.ownedContents().equals(ItemIdentifier.readShulkerContents(shell)))
            return Phase.CONFLICT;
        int sourceCount = -1;
        int sources = 0;
        List<Integer> loose = new ArrayList<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = inventory.get(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            String item = ItemIdentifier.getItemId(stack);
            if (plan.itemId().equals(item)) {
                if (!plan.variantHash().equals(KitVariantFingerprint.canonicalVariant(stack)))
                    return Phase.CONFLICT;
                loose.add(stack.getAmount());
            }
            if (raw == ownedRaw || !plan.sourceItemId().equals(item)) continue;
            Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
            int count = contents.getOrDefault(plan.itemId(), -1);
            if (contents.size() != 1 || count != plan.combinedSourceUnits()
                    && count != plan.journaledSourceUnits()
                    && count != plan.combinedSourceUnits() - plan.retrievedUnits())
                return Phase.CONFLICT;
            if (!plan.variantHash().equals(physicalVariant(stack, plan.itemId(), count)))
                return Phase.CONFLICT;
            sources++;
            sourceCount = count;
        }
        if (sources == 0) return Phase.IN_FLIGHT;
        if (sources != 1) return Phase.CONFLICT;
        loose.sort(Integer::compareTo);
        if (sourceCount == plan.combinedSourceUnits())
            return loose.isEmpty() ? Phase.COMBINED : Phase.CONFLICT;
        if (sourceCount == plan.combinedSourceUnits() - plan.retrievedUnits()) {
            List<Integer> task = new ArrayList<>(List.of(64, plan.retrievedUnits() - 64));
            task.sort(Integer::compareTo);
            return loose.equals(task) ? Phase.TASK_EXTRACTED : Phase.CONFLICT;
        }
        if (sourceCount != plan.journaledSourceUnits()) return Phase.CONFLICT;
        if (loose.equals(List.of(64, 64)) && plan.requestedUnits() == 128)
            return Phase.EXTRACTED;
        List<Integer> split = new ArrayList<>(List.of(
                plan.keptUnits(), plan.retrievedUnits() - 64, 64));
        split.sort(Integer::compareTo);
        return loose.equals(split) ? Phase.SPLIT : Phase.CONFLICT;
    }

    private static String physicalVariant(ItemStack box, String itemId, int expected) {
        var stacks = ItemIdentifier.readShulkerPhysicalSlotStacks(box);
        if (stacks.isEmpty()) return null;
        String variant = null;
        int sum = 0;
        for (ItemStack stack : stacks.values()) {
            if (stack == null || stack.getAmount() < 1 || stack.getAmount() > 64
                    || !itemId.equals(ItemIdentifier.getItemId(stack))) return null;
            String next = KitVariantFingerprint.canonicalVariant(stack);
            if (next == null || variant != null && !variant.equals(next)) return null;
            variant = next;
            sum += stack.getAmount();
        }
        return sum == expected ? variant : null;
    }

    private static Result reject(String blocker) { return new Result(null, blocker); }
}
