package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zenith.Globals.CACHE;

/** An occupied player slot must be a kept supply or an exactly journal-owned kit slot.
 * Craft-grid inputs are always rejected; equipped armor and offhand are not loose cargo. */
public final class KitInventoryAdmissionPolicy {
    public record Slot(int raw, String itemId, int amount, boolean customPresentation) { }
    public record Result(List<Integer> unexpectedSlots, List<Slot> unexpectedItems) {
        public boolean accepted() { return unexpectedSlots.isEmpty(); }
    }

    private KitInventoryAdmissionPolicy() { }

    public static Result inspectLive(Map<String, Integer> keepRules, Set<Integer> ownedSlots) {
        Container inventory = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (inventory == null || keepRules == null || ownedSlots == null) return null;
        List<Slot> observed = new ArrayList<>();
        for (int raw = 1; raw <= 45; raw++) {
            if ((raw >= 5 && raw <= 8) || raw == 45) continue; // Equipped slots are not loose cargo.
            ItemStack stack = inventory.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            observed.add(new Slot(raw, ItemIdentifier.getItemId(stack), stack.getAmount(),
                    ItemIdentifier.hasCustomPresentation(stack)));
        }
        return assess(keepRules, observed, ownedSlots);
    }

    static Result assess(Map<String, Integer> keepRules, List<Slot> observed,
                         Set<Integer> ownedSlots) {
        if (keepRules == null || observed == null || ownedSlots == null)
            throw new IllegalArgumentException("Inventory admission requires known inputs");
        List<InventoryKeepPolicy.SlotStack> candidateKeeps = new ArrayList<>();
        for (Slot slot : observed) {
            if (slot == null || slot.amount() <= 0 || ownedSlots.contains(slot.raw())
                    || (slot.raw() >= 1 && slot.raw() <= 4) || slot.raw() == 45) continue;
            // A shulker's color is not its identity. No generic keep rule may silently
            // bless an unjournaled source box or a box with unrelated contents.
            if (ItemIdentifier.baseItemId(slot.itemId()).contains("shulker_box")) continue;
            candidateKeeps.add(new InventoryKeepPolicy.SlotStack(
                    slot.raw(), slot.itemId(), slot.amount(), slot.customPresentation()));
        }
        Set<Integer> kept = InventoryKeepPolicy.from(keepRules).protectedSlots(candidateKeeps);
        List<Integer> unexpected = new ArrayList<>();
        List<Slot> unexpectedItems = new ArrayList<>();
        for (Slot slot : observed) {
            if (slot == null || slot.amount() <= 0) continue;
            if ((slot.raw() >= 5 && slot.raw() <= 8) || slot.raw() == 45) continue;
            if ((slot.raw() >= 1 && slot.raw() <= 4)
                    || (!ownedSlots.contains(slot.raw()) && !kept.contains(slot.raw()))) {
                unexpected.add(slot.raw());
                unexpectedItems.add(slot);
            }
        }
        return new Result(List.copyOf(unexpected), List.copyOf(unexpectedItems));
    }
}
