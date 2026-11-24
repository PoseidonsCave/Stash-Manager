package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSameContentReturnAuditTest {
    private static final String OWNED_HASH = "a".repeat(64);
    private static final String SOURCE_HASH = "b".repeat(64);
    private static final KitShulkerLedger.Identity BOX =
            new KitShulkerLedger.Identity("shulker_box", Map.of("totem_of_undying", 9));
    private static final KitSameContentReturnAudit.Witness WITNESS =
            new KitSameContentReturnAudit.Witness(36, 44, OWNED_HASH, SOURCE_HASH);

    @Test
    void pinsThePackedShellAfterTheHotbarSwap() {
        assertEquals(WITNESS, KitSameContentReturnAudit.parse(
                KitSameContentReturnAudit.marker(WITNESS)));
        assertTrue(KitSameContentReturnAudit.beforeDeposit(WITNESS,
                Map.of(36, BOX, 44, BOX), BOX,
                Map.of(36, OWNED_HASH, 44, SOURCE_HASH)));
        assertFalse(KitSameContentReturnAudit.beforeDeposit(WITNESS,
                Map.of(36, BOX, 44, BOX), BOX,
                Map.of(36, SOURCE_HASH, 44, OWNED_HASH)));
        assertFalse(KitSameContentReturnAudit.beforeDeposit(WITNESS,
                Map.of(36, BOX, 44, BOX, 40, BOX), BOX,
                Map.of(36, OWNED_HASH, 44, SOURCE_HASH, 40, SOURCE_HASH)));
        assertTrue(KitSameContentReturnAudit.afterDeposit(WITNESS,
                Map.of(36, BOX), BOX, Map.of(36, OWNED_HASH)));
        assertFalse(KitSameContentReturnAudit.afterDeposit(WITNESS,
                Map.of(44, BOX), BOX, Map.of(44, SOURCE_HASH)));
    }

    @Test
    void rejectsMalformedOrUnpinnedMarkers() {
        assertNull(KitSameContentReturnAudit.parse("resume_source_return"));
        assertNull(KitSameContentReturnAudit.parse(
                "resume_source_return_pinned:36:44:bad:hash"));
        assertNull(KitSameContentReturnAudit.parse(
                "resume_source_return_pinned:36:36:" + OWNED_HASH + ':' + SOURCE_HASH));
    }
}
