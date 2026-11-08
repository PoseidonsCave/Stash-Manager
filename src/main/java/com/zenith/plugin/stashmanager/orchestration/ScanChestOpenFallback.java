package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;

import java.util.Optional;
import java.util.function.Predicate;

/** One alternate click on a verified partner half after the primary chest ignores clicks. */
public final class ScanChestOpenFallback {
    public static final int MAX_UNVERIFIED_PROBES_PER_STANCE = 2;
    private static final int PARTNER_WAIT_TICKS = 100;
    private static final int PRIMARY_ATTEMPTS = 3;

    private ScanChestOpenFallback() { }

    public static boolean shouldTryPartner(int openTicks, int primaryAttempts, boolean alreadyTried) {
        return !alreadyTried && openTicks >= PARTNER_WAIT_TICKS
                && openTicks % 20 == 0 && primaryAttempts >= PRIMARY_ATTEMPTS;
    }

    public static Optional<BlockPos> partner(DoubleChestIdentity.Resolution identity, BlockPos primary) {
        if (!identity.identityKnown() || identity.blocks().size() != 2) return Optional.empty();
        boolean primaryFound = false;
        BlockPos other = null;
        for (int[] block : identity.blocks()) {
            if (block.length < 3) return Optional.empty();
            if (block[0] == primary.x() && block[1] == primary.y() && block[2] == primary.z())
                primaryFound = true;
            else other = new BlockPos(block[0], block[1], block[2]);
        }
        return primaryFound ? Optional.ofNullable(other) : Optional.empty();
    }

    /** Probe the primary once, then its verified partner once; never loop on the same half. */
    public static Optional<BlockPos> unverifiedProbe(BlockPos primary, Optional<BlockPos> partner,
                                                      int priorAttempts, boolean partnerPreviouslySelected,
                                                      Predicate<BlockPos> withinReach) {
        if (priorAttempts < 0 || priorAttempts >= MAX_UNVERIFIED_PROBES_PER_STANCE
                || (priorAttempts > 0 && partnerPreviouslySelected)) return Optional.empty();
        if (priorAttempts == 0 && withinReach.test(primary)) return Optional.of(primary);
        return partner.filter(withinReach);
    }
}
