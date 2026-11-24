package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShulkerClassificationTest {
    @Test
    void emptyBoxIsNotAssignedToABulkLane() {
        var result = ShulkerClassification.classify(Map.of());
        assertEquals(ShulkerClassification.Kind.EMPTY, result.kind());
        assertNull(result.storageKey());
    }

    @Test
    void exactSingleVariantIsBulk() {
        var result = ShulkerClassification.classify(Map.of("diamond_pickaxe[fortune]", 18));
        assertEquals(ShulkerClassification.Kind.BULK, result.kind());
        assertEquals("diamond_pickaxe[fortune]", result.storageKey());
    }

    @Test
    void mixedKitIsNeverGuessedFromItsMajorityItem() {
        var result = ShulkerClassification.classify(Map.of(
                "end_crystal", 1728,
                "totem_of_undying", 27));
        assertEquals(ShulkerClassification.Kind.MIXED, result.kind());
        assertNull(result.storageKey());
    }

    @Test
    void fingerprintIsStableAcrossMapOrderAndSensitiveToCounts() {
        Map<String, Integer> first = new LinkedHashMap<>();
        first.put("end_crystal", 64);
        first.put("obsidian", 32);
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("obsidian", 32);
        reversed.put("end_crystal", 64);

        assertEquals(
                ShulkerClassification.classify(first).fingerprint(),
                ShulkerClassification.classify(reversed).fingerprint());
        assertNotEquals(
                ShulkerClassification.classify(first).fingerprint(),
                ShulkerClassification.classify(Map.of("end_crystal", 64, "obsidian", 31)).fingerprint());
    }
}
