package com.zenith.plugin.stashmanager.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Classifies one physical shulker while retaining exact contents for its fingerprint. */
public record ShulkerClassification(
        Kind kind,
        String storageKey,
        String fingerprint,
        Map<String, Integer> contents) {

    public enum Kind { EMPTY, BULK, MIXED }

    public ShulkerClassification {
        contents = Map.copyOf(contents);
    }

    public static ShulkerClassification classify(Map<String, Integer> rawContents) {
        Map<String, Integer> contents = new TreeMap<>();
        if (rawContents != null) {
            rawContents.forEach((itemId, quantity) -> {
                String storageClass = StorageClassPolicy.exact(itemId);
                if (storageClass != null && quantity != null && quantity > 0) {
                    contents.merge(storageClass, quantity, Integer::sum);
                }
            });
        }

        Kind kind;
        String storageKey;
        if (contents.isEmpty()) {
            kind = Kind.EMPTY;
            storageKey = null;
        } else if (contents.keySet().stream().map(StorageClassPolicy::route).distinct().count() == 1) {
            kind = Kind.BULK;
            storageKey = StorageClassPolicy.route(contents.keySet().iterator().next());
        } else {
            kind = Kind.MIXED;
            storageKey = null;
        }
        return new ShulkerClassification(
                kind, storageKey, fingerprint(contents), new LinkedHashMap<>(contents));
    }

    private static String fingerprint(Map<String, Integer> contents) {
        StringBuilder canonical = new StringBuilder();
        contents.forEach((itemId, quantity) -> canonical
                .append(itemId).append('=').append(quantity).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
