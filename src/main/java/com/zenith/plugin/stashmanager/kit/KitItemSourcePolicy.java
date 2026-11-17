package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Rules for selecting one physical stack variant from a live source survey.
 *
 * <p>A policy only selects stock. It never authorizes an inventory click or a
 * checkpoint transition. Those operations retain their own live evidence gates.
 * Forks can add a policy by supplying an item ID, an eligibility rule, and the
 * same-stack rule used by their item.</p>
 */
public record KitItemSourcePolicy(String itemId, Predicate<ItemStack> eligible,
        BiPredicate<ItemStack, ItemStack> sameStack, int stackLimit,
        boolean skipOccludedHopperForPreference) {
    public static final KitItemSourcePolicy NAMED_APPLES = new KitItemSourcePolicy(
            "enchanted_golden_apple", KitAppleExchangePlan::named,
            KitAppleExchangePlan::samePhysicalStack, 64, true);
    public static final KitItemSourcePolicy REGULAR_APPLES = new KitItemSourcePolicy(
            "enchanted_golden_apple", stack -> !KitAppleExchangePlan.named(stack),
            KitAppleExchangePlan::samePhysicalStack, 64, false);
    public static final KitItemSourcePolicy CRYSTALS = new KitItemSourcePolicy(
            "end_crystal", stack -> true, KitVariantFingerprint::sameComponents,
            64, false);

    public KitItemSourcePolicy {
        Objects.requireNonNull(itemId);
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(sameStack);
        if (itemId.isBlank() || stackLimit < 1)
            throw new IllegalArgumentException("item ID and stack limit are required");
    }

    public boolean acceptsCandidate(ItemStack stack) {
        return stack != null && stack.getAmount() > 0
                && itemId.equals(ItemIdentifier.getItemId(stack)) && eligible.test(stack);
    }

    public boolean accepts(ItemStack stack, ItemStack exemplar) {
        return acceptsCandidate(stack) && acceptsCandidate(exemplar)
                && sameStack.test(stack, exemplar);
    }

    /** Prefer a group that fills the request; otherwise choose the largest observed group. */
    public ItemStack choose(List<ItemStack> observed, int requested) {
        if (observed == null || requested < 1) return null;
        List<Group> groups = new ArrayList<>();
        for (ItemStack stack : observed) {
            if (!acceptsCandidate(stack)) continue;
            Group group = groups.stream()
                    .filter(candidate -> accepts(stack, candidate.exemplar))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new Group(stack);
                groups.add(group);
            }
            group.count += stack.getAmount();
        }
        Group best = null;
        for (Group group : groups) {
            if (best == null || Math.min(group.count, requested)
                    > Math.min(best.count, requested)) best = group;
        }
        return best == null ? null : best.exemplar;
    }

    /** Find an already packed variant with room for the whole remaining batch. */
    public ItemStack partialShellVariant(List<ItemStack> shell, int needed) {
        if (shell == null || needed < 1) return null;
        List<ItemStack> matching = shell.stream().filter(this::acceptsCandidate).toList();
        ItemStack selected = null;
        int available = 0;
        for (ItemStack stack : matching) {
            int group = matching.stream().filter(other -> accepts(other, stack))
                    .mapToInt(ItemStack::getAmount).sum();
            int room = (stackLimit - group % stackLimit) % stackLimit;
            if (room >= needed && room > available) {
                selected = stack;
                available = room;
            }
        }
        return selected;
    }

    /** A named-apple preference pass may skip only a source that was never opened. */
    public boolean maySkipInaccessibleSource(String failureReason, String targetBlock,
            boolean solidBlockAbove, boolean visibleFromBot,
            int targetMovedStacks, boolean targetOpenAttempted) {
        return skipOccludedHopperForPreference
                && "walk_timeout".equals(failureReason)
                && targetBlock != null && targetBlock.endsWith("hopper")
                && solidBlockAbove && !visibleFromBot
                && targetMovedStacks == 0 && !targetOpenAttempted;
    }

    private static final class Group {
        private final ItemStack exemplar;
        private int count;

        private Group(ItemStack exemplar) { this.exemplar = exemplar; }
    }
}
