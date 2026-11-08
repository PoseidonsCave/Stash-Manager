package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Selects a physical apple variant only after a source's actual stacks are visible. */
public final class KitAppleSourceGate {
    private KitAppleSourceGate() { }

    public enum Phase { NAMED, REGULAR }

    private static final class Group {
        private final ItemStack exemplar;
        private int count;

        private Group(ItemStack exemplar) { this.exemplar = exemplar; }
    }

    /** Prefer a group that can fill the batch; otherwise retain the largest observed group. */
    public static ItemStack choose(List<ItemStack> observed, Phase phase, int requested) {
        if (observed == null || phase == null || requested <= 0) return null;
        List<Group> groups = new ArrayList<>();
        for (ItemStack stack : observed) {
            if (!inPhase(stack, phase)) continue;
            Group group = groups.stream()
                    .filter(candidate -> KitAppleExchangePlan.samePhysicalStack(
                            candidate.exemplar, stack))
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

    public static boolean accepts(ItemStack stack, Phase phase, ItemStack exemplar) {
        return inPhase(stack, phase) && exemplar != null
                && KitAppleExchangePlan.samePhysicalStack(stack, exemplar);
    }

    public static boolean inPhase(ItemStack stack, Phase phase) {
        return stack != null && stack.getAmount() > 0 && phase != null
                && "enchanted_golden_apple".equals(ItemIdentifier.getItemId(stack))
                && (KitAppleExchangePlan.named(stack) == (phase == Phase.NAMED));
    }

    /**
     * An inaccessible source cannot establish that named stock is absent. It may
     * nevertheless be skipped for the preference-only named pass so that a live
     * regular-apple search can attempt a guarded, component-uniform exchange.
     * The regular pass must still stop on any inaccessible source.
     */
    public static boolean maySkipInaccessibleNamedHopper(Phase phase, String failureReason,
            String targetBlock, boolean solidBlockAbove, boolean visibleFromBot,
            int targetMovedStacks, boolean targetOpenAttempted) {
        return phase == Phase.NAMED
                && "walk_timeout".equals(failureReason)
                && targetBlock != null && targetBlock.endsWith("hopper")
                && solidBlockAbove && !visibleFromBot
                && targetMovedStacks == 0 && !targetOpenAttempted;
    }
}
