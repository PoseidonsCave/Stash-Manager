package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
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
        if (plan == null || expectedShell == null || liveShell == null || player == null
                || expectedShell.size() != 27 || !validShell(expectedShell)
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
                || plan.mergeTargetStack().getAmount() + plan.amount()
                        > plan.maxStack()) return null;

        Map<Integer, ItemStack> removedShell = new HashMap<>(liveShell);
        removedShell.remove(plan.outgoingSlot());
        Map<Integer, ItemStack> removedPlayer = new HashMap<>(player);
        removedPlayer.put(displacedRawSlot, plan.outgoingStack());

        Map<Integer, ItemStack> addedShell = new HashMap<>(removedShell);
        addedShell.put(plan.mergeTargetSlot(), new ItemStack(
                plan.mergeTargetStack().getId(),
                plan.mergeTargetStack().getAmount() + plan.amount(),
                plan.mergeTargetStack().getDataComponents()));
        Map<Integer, ItemStack> addedPlayer = new HashMap<>(removedPlayer);
        addedPlayer.remove(incomingRawSlot);
        return new KitVariantExchangeEvidence(liveShell, player, removedShell,
                removedPlayer, addedShell, addedPlayer);
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

    /** The exchange's 26 exact stacks plus the separately audited cargo stack. */
    public boolean matchesRecoveredWithCargo(ItemStack shulker, ItemStack cargo) {
        if (shulker == null || shulker.getAmount() != 1 || !valid(cargo)) return false;
        Map<Integer, ItemStack> observed =
                ItemIdentifier.readShulkerPhysicalSlotStacks(shulker);
        if (observed.size() != shellAfterAddition.size() + 1) return false;
        for (var entry : shellAfterAddition.entrySet()) {
            if (!same(entry.getValue(), observed.get(entry.getKey()))) return false;
        }
        int extra = 0;
        for (var entry : observed.entrySet()) {
            if (!shellAfterAddition.containsKey(entry.getKey())) {
                if (!same(cargo, entry.getValue())) return false;
                extra++;
            }
        }
        return extra == 1;
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
