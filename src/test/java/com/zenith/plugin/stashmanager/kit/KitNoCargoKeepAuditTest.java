package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitNoCargoKeepAuditTest {
    private static final String APPLE = "enchanted_golden_apple";

    @Test
    void acceptsEveryDeclaredKeptStackWithExactCounts() {
        var observed = Map.of(12, new KitCargoAudit.Stack(APPLE, 8),
                16, new KitCargoAudit.Stack(APPLE, 64),
                27, new KitCargoAudit.Stack(APPLE, 64),
                37, new KitCargoAudit.Stack("shulker_box", 1));
        assertTrue(KitNoCargoKeepAudit.matches(observed, APPLE, 37,
                Map.of(12, 8, 16, 64, 27, 64)));
    }

    @Test
    void rejectsUndeclaredOrChangedStack() {
        var observed = Map.of(12, new KitCargoAudit.Stack(APPLE, 8),
                16, new KitCargoAudit.Stack(APPLE, 64));
        assertFalse(KitNoCargoKeepAudit.matches(observed, APPLE, 37, Map.of(12, 8)));
        assertFalse(KitNoCargoKeepAudit.matches(observed, APPLE, 37,
                Map.of(12, 8, 16, 63)));
    }

    @Test
    void rejectsShellSlotAndEmptyDeclaration() {
        var observed = Map.of(37, new KitCargoAudit.Stack(APPLE, 1));
        assertFalse(KitNoCargoKeepAudit.matches(observed, APPLE, 37, Map.of(37, 1)));
        assertFalse(KitNoCargoKeepAudit.matches(observed, APPLE, 37, Map.of()));
    }
}
