package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact live-window assertions for a whole-stack variant exchange. A failed
 * assertion means stop for audit; it must never trigger a replayed click.
 */
public final class KitVariantExchangeEvidence {
    private final Map<Integer, ItemStack> shellBefore;
    private final Map<Integer, ItemStack> playerBefore;
    private final Map<Integer, ItemStack> shellAfterRemoval;
    private final Map<Integer, ItemStack> playerAfterRemoval;
    private final Map<Integer, ItemStack> shellAfterAddition;
    private final Map<Integer, ItemStack> playerAfterAddition;

    private KitVariantExchangeEvidence(Map<Integer, ItemStack> shellBefore,
                                       Map<Integer, ItemStack> playerBefore,
                                       Map<Integer, ItemStack> shellAfterRemoval,
                                       Map<Integer, ItemStack> playerAfterRemoval,
                                       Map<Integer, ItemStack> shellAfterAddition,
                                       Map<Integer, ItemStack> playerAfterAddition) {
        this.shellBefore = Map.copyOf(shellBefore);
        this.playerBefore = Map.copyOf(playerBefore);
        this.shellAfterRemoval = Map.copyOf(shellAfterRemoval);
        this.playerAfterRemoval = Map.copyOf(playerAfterRemoval);
        this.shellAfterAddition = Map.copyOf(shellAfterAddition);
        this.playerAfterAddition = Map.copyOf(playerAfterAddition);
    }

    public static KitVariantExchangeEvidence capture(KitVariantExchangePlan.Plan plan,
                                                      Map<Integer, ItemStack> expectedShell,
                                                      Map<Integer, ItemStack> liveShell,
                                                      Map<Integer, ItemStack> player,
                                                      int incomingRawSlot,
                                                      int displacedRawSlot) {
        return capture(plan, expectedShell, liveShell, player, incomingRawSlot,
                displacedRawSlot, false);
    }

    public static KitVariantExchangeEvidence capture(KitVariantExchangePlan.Plan plan,
                                                      Map<Integer, ItemStack> expectedShell,
                                                      Map<Integer, ItemStack> liveShell,
                                                      Map<Integer, ItemStack> player,
                                                      int incomingRawSlot,
                                                      int displacedRawSlot,
                                                      boolean replacementOnly) {
        if (plan == null || expectedShell == null || liveShell == null || player == null
                || (expectedShell.size() != 27
                    && !(expectedShell.size() == 26 && !replacementOnly
                        && plan.slotsAfter() == 25)
                    && !(replacementOnly && packedStackRepairShape(plan, expectedShell.size())))
                || !validShell(expectedShell)
                || !sameSlots(expectedShell, liveShell) || !validPlayer(player)
                || incomingRawSlot < 9 || incomingRawSlot > 44
                || displacedRawSlot < 9 || displacedRawSlot > 35
                || incomingRawSlot == displacedRawSlot
                || player.containsKey(displacedRawSlot)
                || !same(liveShell.get(plan.outgoingSlot()), plan.outgoingStack())
                || !same(liveShell.get(plan.mergeTargetSlot()), plan.mergeTargetStack())
                || !sameVariant(player.get(incomingRawSlot), plan.replacementExemplar())
                || !valid(player.get(incomingRawSlot))
                || player.get(incomingRawSlot).getAmount() != plan.amount()
                || (!replacementOnly && plan.mergeTargetStack().getAmount()
                        + plan.amount() > plan.maxStack())) return null;

        Map<Integer, ItemStack> removedShell = new HashMap<>(liveShell);
        removedShell.remove(plan.outgoingSlot());
        Map<Integer, ItemStack> removedPlayer = new HashMap<>(player);
        removedPlayer.put(displacedRawSlot, plan.outgoingStack());

        Map<Integer, ItemStack> addedShell = new HashMap<>(removedShell);
        if (replacementOnly) {
            addedShell.put(plan.outgoingSlot(), new ItemStack(
                    plan.replacementExemplar().getId(), plan.amount(),
                    plan.replacementExemplar().getDataComponents()));
        } else {
            addedShell.put(plan.mergeTargetSlot(), new ItemStack(
                    plan.mergeTargetStack().getId(),
                    plan.mergeTargetStack().getAmount() + plan.amount(),
                    plan.mergeTargetStack().getDataComponents()));
        }
        Map<Integer, ItemStack> addedPlayer = new HashMap<>(removedPlayer);
        addedPlayer.remove(incomingRawSlot);
        return new KitVariantExchangeEvidence(liveShell, player, removedShell,
                removedPlayer, addedShell, addedPlayer);
    }

    private static boolean packedStackRepairShape(KitVariantExchangePlan.Plan plan,
            int physicalSlots) {
        if (plan.replacementExemplar() == null) return false;
        var profile = KitPackedStackCapacityRepair.profile(
                ItemIdentifier.getItemId(plan.replacementExemplar()));
        return profile != null && physicalSlots == profile.physicalSlots()
                && plan.slotsAfter() == profile.allowedSlots();
    }

    public boolean matchesBefore(Map<Integer, ItemStack> shell,
                                 Map<Integer, ItemStack> player) {
        return sameSlots(shellBefore, shell) && sameSlots(playerBefore, player);
    }

    public boolean matchesAfterRemoval(Map<Integer, ItemStack> shell,
                                       Map<Integer, ItemStack> player) {
        return sameSlots(shellAfterRemoval, shell)
                && sameSlots(playerAfterRemoval, player);
    }

    public boolean matchesAfterAddition(Map<Integer, ItemStack> shell,
                                        Map<Integer, ItemStack> player) {
        return sameSlots(shellAfterAddition, shell)
                && sameSlots(playerAfterAddition, player);
    }

    /** Aggregate contents alone cannot prove the recovered box is the exchanged one. */
    public boolean matchesRecoveredShell(ItemStack shulker) {
        return shulker != null && shulker.getAmount() == 1
                && sameSlots(shellAfterAddition,
                        ItemIdentifier.readShulkerPhysicalSlotStacks(shulker));
    }

    /** Physical identity, independent of whether pickup reuses the old raw slot. */
    public int uniqueRecoveredSlot(Map<Integer, ItemStack> player) {
        if (player == null) return -1;
        int found = -1;
        for (var entry : player.entrySet()) {
            int raw = entry.getKey();
            if (raw < 9 || raw > 44 || !matchesRecoveredShell(entry.getValue())) continue;
            if (found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    /** The exchange's 26 exact stacks plus the separately audited cargo stack. */
    public boolean matchesRecoveredWithCargo(ItemStack shulker, ItemStack cargo) {
        if (cargo == null) return false;
        return matchesRecoveredWithCargo(shulker, List.of(cargo));
    }

    /** Every added cargo stack must be present exactly once outside the exchanged shell. */
    public boolean matchesRecoveredWithCargo(ItemStack shulker, List<ItemStack> cargo) {
        if (shulker == null || shulker.getAmount() != 1 || cargo == null
                || cargo.isEmpty() || cargo.stream().anyMatch(stack -> !valid(stack))) return false;
        Map<Integer, ItemStack> observed =
                ItemIdentifier.readShulkerPhysicalSlotStacks(shulker);
        if (observed.size() != shellAfterAddition.size() + cargo.size()) return false;
        for (var entry : shellAfterAddition.entrySet()) {
            if (!same(entry.getValue(), observed.get(entry.getKey()))) return false;
        }
        List<ItemStack> unmatched = new java.util.ArrayList<>(cargo);
        for (var entry : observed.entrySet()) {
            if (!shellAfterAddition.containsKey(entry.getKey())) {
                int match = -1;
                for (int i = 0; i < unmatched.size(); i++) {
                    if (same(unmatched.get(i), entry.getValue())) { match = i; break; }
                }
                if (match < 0) return false;
                unmatched.remove(match);
            }
        }
        return unmatched.isEmpty();
    }

    private static boolean validShell(Map<Integer, ItemStack> slots) {
        for (var entry : slots.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0 || entry.getKey() >= 27
                    || !valid(entry.getValue())) return false;
        }
        return true;
    }

    private static boolean validPlayer(Map<Integer, ItemStack> slots) {
        for (var entry : slots.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 9 || entry.getKey() > 44
                    || !valid(entry.getValue())) return false;
        }
        return true;
    }

    private static boolean valid(ItemStack stack) {
        return stack != null && stack.getId() > 0 && stack.getAmount() > 0;
    }

    private static boolean sameSlots(Map<Integer, ItemStack> expected,
                                     Map<Integer, ItemStack> observed) {
        if (observed == null || !expected.keySet().equals(observed.keySet())) return false;
        for (var entry : expected.entrySet()) {
            if (!same(entry.getValue(), observed.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean same(ItemStack expected, ItemStack observed) {
        return valid(expected) && valid(observed)
                && expected.getId() == observed.getId()
                && expected.getAmount() == observed.getAmount()
                && Objects.equals(expected.getDataComponents(), observed.getDataComponents());
    }

    private static boolean sameVariant(ItemStack expected, ItemStack observed) {
        return valid(expected) && valid(observed)
                && expected.getId() == observed.getId()
                && Objects.equals(expected.getDataComponents(), observed.getDataComponents());
    }
}
