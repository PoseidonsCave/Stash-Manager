package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Plans a whole-stack replacement using only live physical stacks. This does not
 * authorize a withdrawal, an inventory click, or a checkpoint transition.
 */
public final class KitVariantExchangePlan {
    private KitVariantExchangePlan() { }

    public record SlotStack(int slot, ItemStack stack) { }

    /** The source slot is scoped to the live opened source window, not player inventory. */
    public record Plan(int outgoingSlot, ItemStack outgoingStack,
                       int mergeTargetSlot, ItemStack mergeTargetStack,
                       int sourceSlot, ItemStack replacementExemplar,
                       int amount, int maxStack, int slotsAfter) { }

    /** A shell-only opportunity is not proof that replacement stock exists. */
    public record Opportunity(int outgoingSlot, ItemStack outgoingStack,
                              int mergeTargetSlot, ItemStack mergeTargetStack,
                              int amount, int maxStack, int slotsAfter) {
        public Plan withLiveSource(int sourceSlot, ItemStack source) {
            if (sourceSlot < 0 || source == null || source.getAmount() < amount
                    || !same(source, mergeTargetStack)) return null;
            return new Plan(outgoingSlot, outgoingStack, mergeTargetSlot,
                    mergeTargetStack, sourceSlot, source, amount, maxStack, slotsAfter);
        }
    }

    public static Opportunity shellOpportunity(List<SlotStack> shell, String itemId,
                                                int requiredUnits, int allowedSlots) {
        if (shell == null || itemId == null || requiredUnits <= 0 || allowedSlots <= 0
                || shell.size() != allowedSlots + 1) return null;
        ItemData data = ItemRegistry.REGISTRY.get(itemId);
        int max = data == null ? 0 : data.stackSize();
        if (max < 2) return null;
        Set<Integer> seen = new HashSet<>();
        int total = 0;
        for (SlotStack slot : shell) {
            if (slot == null || slot.slot() < 0 || slot.slot() >= 27
                    || !seen.add(slot.slot()) || !validPhysical(slot.stack())) return null;
            if (itemId.equals(ItemIdentifier.getItemId(slot.stack())))
                total += slot.stack().getAmount();
        }
        if (total != requiredUnits) return null;
        Opportunity selected = null;
        for (SlotStack outgoing : shell) {
            if (!itemId.equals(ItemIdentifier.getItemId(outgoing.stack()))) continue;
            for (SlotStack target : shell) {
                if (outgoing.slot() == target.slot() || same(outgoing.stack(), target.stack())
                        || !itemId.equals(ItemIdentifier.getItemId(target.stack()))
                        || target.stack().getAmount() + outgoing.stack().getAmount() > max)
                    continue;
                List<ItemStack> result = new ArrayList<>();
                for (SlotStack slot : shell)
                    if (slot.slot() != outgoing.slot()) result.add(slot.stack());
                result.add(new ItemStack(target.stack().getId(),
                        outgoing.stack().getAmount(), target.stack().getDataComponents()));
                int slotsAfter = requiredSlots(result);
                if (slotsAfter > allowedSlots) continue;
                Opportunity candidate = new Opportunity(outgoing.slot(), outgoing.stack(),
                        target.slot(), target.stack(), outgoing.stack().getAmount(), max,
                        slotsAfter);
                if (selected == null || candidate.amount() < selected.amount()) {
                    selected = candidate;
                } else if (candidate.amount() == selected.amount()
                        && (candidate.outgoingSlot() != selected.outgoingSlot()
                            || !same(candidate.mergeTargetStack(),
                                    selected.mergeTargetStack()))) {
                    return null; // No arbitrary tie between equally small moves.
                }
            }
        }
        return selected;
    }

    /**
     * Source stacks must come from a live opened source window, not the stale
     * scan index or a shulker tooltip. This only selects a candidate plan.
     */
    public static Plan oneStack(List<SlotStack> shell, List<SlotStack> liveSources,
                                String itemId, int requiredUnits, int allowedSlots) {
        if (shell == null || liveSources == null || itemId == null || requiredUnits <= 0
                || allowedSlots <= 0 || shell.size() != allowedSlots + 1) return null;
        ItemData data = ItemRegistry.REGISTRY.get(itemId);
        int maxStack = data == null ? 0 : data.stackSize();
        if (maxStack < 2) return null;
        int total = 0;
        Set<Integer> seenSlots = new HashSet<>();
        for (SlotStack slot : shell) {
            if (slot == null || slot.slot() < 0 || slot.slot() >= 27
                    || !seenSlots.add(slot.slot())
                    || !validPhysical(slot.stack())) return null;
            if (itemId.equals(ItemIdentifier.getItemId(slot.stack()))) {
                if (slot.stack().getAmount() > maxStack) return null;
                total += slot.stack().getAmount();
            }
        }
        if (total != requiredUnits) return null;
        Set<Integer> seenSourceSlots = new HashSet<>();
        for (SlotStack source : liveSources) {
            if (source == null || source.slot() < 0 || source.slot() >= 54
                    || !seenSourceSlots.add(source.slot())
                    || !valid(source.stack(), itemId, maxStack)) return null;
        }
        Plan best = null;
        boolean ambiguous = false;
        for (SlotStack outgoing : shell) {
            if (!itemId.equals(ItemIdentifier.getItemId(outgoing.stack()))) continue;
            for (SlotStack source : liveSources) {
                if (source.stack().getAmount() < outgoing.stack().getAmount()
                        || same(source.stack(), outgoing.stack())) continue;
                // Require one live merge target with enough headroom. This avoids
                // assuming that a later, unjournaled shell compaction will succeed.
                SlotStack mergeTarget = null;
                for (SlotStack slot : shell) {
                    if (slot.slot() != outgoing.slot()
                            && same(slot.stack(), source.stack())
                            && slot.stack().getAmount() + outgoing.stack().getAmount() <= maxStack
                            && (mergeTarget == null || slot.slot() < mergeTarget.slot())) {
                        mergeTarget = slot;
                    }
                }
                if (mergeTarget == null) continue;
                int amount = outgoing.stack().getAmount();
                List<ItemStack> result = new ArrayList<>();
                for (SlotStack slot : shell) {
                    if (slot.slot() != outgoing.slot()) result.add(slot.stack());
                }
                result.add(new ItemStack(source.stack().getId(), amount,
                        source.stack().getDataComponents()));
                int slotsAfter = requiredSlots(result);
                if (slotsAfter > allowedSlots) continue;
                Plan candidate = new Plan(outgoing.slot(), outgoing.stack(),
                        mergeTarget.slot(), mergeTarget.stack(), source.slot(),
                        source.stack(), amount, maxStack, slotsAfter);
                if (best == null || amount < best.amount()) {
                    best = candidate;
                    ambiguous = false;
                } else if (amount == best.amount()
                        && (candidate.outgoingSlot() != best.outgoingSlot()
                            || !same(candidate.replacementExemplar(),
                                    best.replacementExemplar()))) {
                    ambiguous = true;
                } else if (amount == best.amount()
                        && candidate.sourceSlot() < best.sourceSlot()) {
                    best = candidate;
                }
            }
        }
        return ambiguous ? null : best;
    }

    private static int requiredSlots(List<ItemStack> stacks) {
        List<ItemStack> groups = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        for (ItemStack stack : stacks) {
            int index = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (same(stack, groups.get(i))) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                groups.add(stack);
                totals.add(stack.getAmount());
            } else {
                totals.set(index, totals.get(index) + stack.getAmount());
            }
        }
        int slots = 0;
        for (int i = 0; i < totals.size(); i++) {
            ItemData data = ItemRegistry.REGISTRY.get(groups.get(i).getId());
            if (data == null || data.stackSize() < 1) return Integer.MAX_VALUE;
            slots += (totals.get(i) + data.stackSize() - 1) / data.stackSize();
        }
        return slots;
    }

    private static boolean validPhysical(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        return data != null && stack.getAmount() <= data.stackSize();
    }

    private static boolean valid(ItemStack stack, String itemId, int maxStack) {
        return stack != null && stack.getAmount() > 0 && stack.getAmount() <= maxStack
                && itemId.equals(ItemIdentifier.getItemId(stack));
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getId() == b.getId()
                && Objects.equals(a.getDataComponents(), b.getDataComponents());
    }
}
