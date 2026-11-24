package com.zenith.plugin.stashmanager.kit;

/** Only a complete scan or a single-target partial can source a physical kit build. */
public final class KitScanAdmission {
    private KitScanAdmission() { }

    public static boolean allows(String status, int found, int indexed, int failed) {
        if (status == null || found < 1 || indexed < 1 || failed < 0) return false;
        if ("complete".equalsIgnoreCase(status)) {
            return failed == 0 && indexed == found;
        }
        return "partial".equalsIgnoreCase(status) && found >= 100
                && failed == 1 && indexed == found - 1;
    }
}
