package com.zenith.plugin.stashmanager.kit;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

/** Compatibility facade for forks using the older apple source API. */
@Deprecated
public final class KitAppleSourceGate {
    private KitAppleSourceGate() { }

    public enum Phase { NAMED, REGULAR }

    private static KitItemSourcePolicy policy(Phase phase) {
        return phase == Phase.NAMED ? KitItemSourcePolicy.NAMED_APPLES
                : phase == Phase.REGULAR ? KitItemSourcePolicy.REGULAR_APPLES : null;
    }

    public static ItemStack choose(List<ItemStack> observed, Phase phase, int requested) {
        KitItemSourcePolicy policy = policy(phase);
        return policy == null ? null : policy.choose(observed, requested);
    }

    public static boolean accepts(ItemStack stack, Phase phase, ItemStack exemplar) {
        KitItemSourcePolicy policy = policy(phase);
        return policy != null && policy.accepts(stack, exemplar);
    }

    public static boolean inPhase(ItemStack stack, Phase phase) {
        KitItemSourcePolicy policy = policy(phase);
        return policy != null && policy.acceptsCandidate(stack);
    }

    public static boolean maySkipInaccessibleNamedHopper(Phase phase, String failureReason,
            String targetBlock, boolean solidBlockAbove, boolean visibleFromBot,
            int targetMovedStacks, boolean targetOpenAttempted) {
        KitItemSourcePolicy policy = policy(phase);
        return policy != null && policy.maySkipInaccessibleSource(failureReason, targetBlock,
                solidBlockAbove, visibleFromBot, targetMovedStacks, targetOpenAttempted);
    }
}
