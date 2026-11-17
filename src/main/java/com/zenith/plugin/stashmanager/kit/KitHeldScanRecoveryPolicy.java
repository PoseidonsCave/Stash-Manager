package com.zenith.plugin.stashmanager.kit;

/** Exception for a previously started kit's exact carried-box recovery only. */
public final class KitHeldScanRecoveryPolicy {
    private KitHeldScanRecoveryPolicy() { }

    public static boolean accepts(boolean fullScanTrusted, boolean sourceIndexed,
                                  int found, int indexed, int failed, String completionStatus) {
        if (!sourceIndexed) return false;
        if (fullScanTrusted) return true;
        return found > 0 && indexed > 0 && failed == 1
                && found == indexed + failed
                && "partial".equalsIgnoreCase(completionStatus);
    }
}
