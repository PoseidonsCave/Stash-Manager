package com.zenith.plugin.stashmanager.kit;

import java.util.Map;

/** A one-return ownership witness when source and packed shell have equal item totals. */
public final class KitSameContentReturnAudit {
    private static final String PREFIX = "resume_source_return_pinned:";

    private KitSameContentReturnAudit() { }

    public record Witness(int ownedRaw, int sourceRaw, String ownedHash, String sourceHash) {
        public Witness {
            if (ownedRaw < 9 || ownedRaw > 44 || sourceRaw < 9 || sourceRaw > 44
                    || ownedRaw == sourceRaw || !sha256(ownedHash) || !sha256(sourceHash)
                    || ownedHash.equals(sourceHash))
                throw new IllegalArgumentException("invalid same-content return witness");
        }
    }

    public static String marker(Witness witness) {
        return PREFIX + witness.ownedRaw() + ':' + witness.sourceRaw() + ':'
                + witness.ownedHash() + ':' + witness.sourceHash();
    }

    public static Witness parse(String marker) {
        if (marker == null || !marker.startsWith(PREFIX)) return null;
        String[] parts = marker.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 4) return null;
        try {
            return new Witness(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    parts[2], parts[3]);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Exact two-box inventory and both full component hashes, before any deposit. */
    public static boolean beforeDeposit(Witness witness,
                                        Map<Integer, KitShulkerLedger.Identity> boxes,
                                        KitShulkerLedger.Identity identity,
                                        Map<Integer, String> hashes) {
        return witness != null && boxes != null && identity != null && hashes != null
                && boxes.size() == 2 && hashes.size() == 2
                && identity.equals(boxes.get(witness.ownedRaw()))
                && identity.equals(boxes.get(witness.sourceRaw()))
                && witness.ownedHash().equals(hashes.get(witness.ownedRaw()))
                && witness.sourceHash().equals(hashes.get(witness.sourceRaw()));
    }

    /** Exporter has removed the pinned source; chest verification is still required. */
    public static boolean afterDeposit(Witness witness,
                                       Map<Integer, KitShulkerLedger.Identity> boxes,
                                       KitShulkerLedger.Identity identity,
                                       Map<Integer, String> hashes) {
        return witness != null && boxes != null && identity != null && hashes != null
                && boxes.size() == 1 && hashes.size() == 1
                && identity.equals(boxes.get(witness.ownedRaw()))
                && witness.ownedHash().equals(hashes.get(witness.ownedRaw()));
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
