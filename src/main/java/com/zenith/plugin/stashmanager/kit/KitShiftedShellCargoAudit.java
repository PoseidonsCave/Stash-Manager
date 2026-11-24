package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Rebind a stopped retrieval only when all three carried boxes and loose cargo are exact. */
public final class KitShiftedShellCargoAudit {
    private KitShiftedShellCargoAudit() { }

    public record Plan(int ownedSlot, int sourceSlot,
                       KitFulfillmentJournalStore.RetainedShell retainedShell) { }

    public static boolean eligible(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null) return false;
        boolean interrupted = "WAIT_CARGO".equals(snapshot.state())
                && snapshot.blocker() == null && snapshot.resumeState() == null;
        boolean reviewedAfterRestart = "BLOCKED".equals(snapshot.state())
                && "uncertain_wait_cargo".equals(snapshot.blocker())
                && "WAIT_CARGO".equals(snapshot.resumeState())
                && snapshot.resumeNotBeforeEpochMilli() == 0;
        return (interrupted || reviewedAfterRestart)
                && snapshot.borrowedSourceWorksite() == null && snapshot.kitWorksite() == null
                && snapshot.retainedShell() != null
                && snapshot.sourceBoxesToReturn() != null
                && snapshot.sourceBoxesToReturn().size() == 1
                && snapshot.sourceBoxesToReturn().getFirst().sourcePosition() != null
                && snapshot.variantRepair() == null && snapshot.protectedLoose() == null
                && snapshot.heldBoxRebase() == null
                && snapshot.auditedCargoSlot() == -1
                && snapshot.auditedCargoSlots().isEmpty()
                && snapshot.writtenOffSources().isEmpty()
                && snapshot.currentItem() != null && snapshot.currentQuantity() > 0
                && snapshot.recipe().getOrDefault(snapshot.currentItem(), 0)
                    - snapshot.packedContents().getOrDefault(snapshot.currentItem(), 0)
                        >= snapshot.currentQuantity()
                && snapshot.completedBoxes() + snapshot.incompleteBoxes()
                    < snapshot.requestedBoxes();
    }

    /** Returns no plan if a slot, item identity, count, or the complete box set differs. */
    public static Plan inspect(KitFulfillmentJournalStore.Snapshot snapshot,
                               Map<Integer, ItemStack> inventory,
                               Map<Integer, Integer> pinnedCargo) {
        if (!eligible(snapshot) || inventory == null || pinnedCargo == null) return null;
        var source = snapshot.sourceBoxesToReturn().getFirst();
        var sourceIdentity = new KitShulkerLedger.Identity(source.itemId(), source.contents());
        Map<Integer, KitShulkerLedger.Identity> boxes = new HashMap<>();
        Map<Integer, KitCargoAudit.Stack> stacks = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = inventory.get(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            stacks.put(raw, new KitCargoAudit.Stack(itemId, stack.getAmount()));
            if (ItemIdentifier.isShulkerBox(stack)) {
                if (stack.getAmount() != 1) return null;
                boxes.put(raw, new KitShulkerLedger.Identity(itemId,
                        ItemIdentifier.readShulkerContents(stack)));
            }
        }
        if (boxes.size() != 3) return null;
        int owned = KitShulkerLedger.ownedSlot(boxes, snapshot.ownedShellSlot(),
                snapshot.packedContents(), sourceIdentity);
        // This path is for the observed three-way shuffle, not a general cargo audit.
        if (owned != snapshot.retainedShell().rawSlot()
                || owned == snapshot.ownedShellSlot()) return null;
        int retained = KitRetainedShell.uniqueSlot(snapshot.retainedShell(), inventory, owned);
        if (retained < 9 || retained == snapshot.retainedShell().rawSlot()) return null;
        int sourceSlot = snapshot.ownedShellSlot();
        if (!sourceIdentity.equals(boxes.get(sourceSlot))
                || !boxes.containsKey(retained)
                || !KitCargoAudit.verifiedBatch(stacks, snapshot.currentItem(),
                        snapshot.currentQuantity(), owned, pinnedCargo)
                || !KitInventoryAppleOwnership.matches(snapshot.displacedAppleSlots(),
                        inventory)) return null;
        return new Plan(owned, sourceSlot,
                new KitFulfillmentJournalStore.RetainedShell(retained,
                        snapshot.retainedShell().itemId(),
                        snapshot.retainedShell().componentsHash()));
    }
}
