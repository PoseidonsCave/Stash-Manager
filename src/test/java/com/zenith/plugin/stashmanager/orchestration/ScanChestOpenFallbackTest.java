package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanChestOpenFallbackTest {
    @Test
    void partnerIsOnlyTriedAfterPrimaryHadTimeAndClicks() {
        assertFalse(ScanChestOpenFallback.shouldTryPartner(80, 4, false));
        assertFalse(ScanChestOpenFallback.shouldTryPartner(100, 2, false));
        assertFalse(ScanChestOpenFallback.shouldTryPartner(101, 4, false));
        assertFalse(ScanChestOpenFallback.shouldTryPartner(100, 4, true));
        assertTrue(ScanChestOpenFallback.shouldTryPartner(100, 4, false));
    }

    @Test
    void onlyVerifiedOtherHalfCanBeClicked() {
        BlockPos primary = new BlockPos(10, 64, 20);
        var identity = new DoubleChestIdentity.Resolution(10, 64, 20, true,
                List.of(new int[]{10, 64, 20}, new int[]{11, 64, 20}));
        assertEquals(new BlockPos(11, 64, 20),
                ScanChestOpenFallback.partner(identity, primary).orElseThrow());
        assertTrue(ScanChestOpenFallback.partner(identity,
                new BlockPos(12, 64, 20)).isEmpty());
        assertTrue(ScanChestOpenFallback.partner(
                new DoubleChestIdentity.Resolution(10, 64, 20, false, identity.blocks()),
                primary).isEmpty());
    }

    @Test
    void unverifiedProbeTriesEachReachableHalfOnlyOnce() {
        BlockPos primary = new BlockPos(10, 64, 20);
        BlockPos partner = new BlockPos(11, 64, 20);
        Optional<BlockPos> verifiedPartner = Optional.of(partner);

        assertEquals(primary, ScanChestOpenFallback.unverifiedProbe(
                primary, verifiedPartner, 0, false, pos -> true).orElseThrow());
        assertEquals(partner, ScanChestOpenFallback.unverifiedProbe(
                primary, verifiedPartner, 1, false, pos -> true).orElseThrow());
        assertTrue(ScanChestOpenFallback.unverifiedProbe(
                primary, verifiedPartner, 2, true, pos -> true).isEmpty());
        assertTrue(ScanChestOpenFallback.unverifiedProbe(
                primary, verifiedPartner, 1, true, pos -> true).isEmpty());
    }

    @Test
    void unverifiedProbeSkipsOutOfReachAndUnknownPartner() {
        BlockPos primary = new BlockPos(10, 64, 20);
        BlockPos partner = new BlockPos(11, 64, 20);

        assertEquals(partner, ScanChestOpenFallback.unverifiedProbe(
                primary, Optional.of(partner), 0, false,
                pos -> pos.equals(partner)).orElseThrow());
        assertTrue(ScanChestOpenFallback.unverifiedProbe(
                primary, Optional.empty(), 1, false, pos -> true).isEmpty());
    }
}
