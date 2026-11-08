package com.zenith.plugin.stashmanager.kit;

/** A window-open packet may precede its contents and player-slot layout. */
public final class KitWindowOpenPolicy {
    public enum Result { RETRY_CLICK, WAIT_FOR_CONTENTS, READY, WRONG_TYPE, TIMED_OUT }

    private KitWindowOpenPolicy() { }

    public static Result evaluate(int liveId, int contentId, int slots,
                                  int ticks, int clickTimeout, int contentGrace,
                                  int... acceptedSlots) {
        if (liveId > 0) {
            if (contentId == liveId && slots > 0) {
                for (int accepted : acceptedSlots) {
                    if (slots == accepted) return Result.READY;
                }
                return Result.WRONG_TYPE;
            }
            return ticks > clickTimeout + contentGrace
                    ? Result.TIMED_OUT : Result.WAIT_FOR_CONTENTS;
        }
        return ticks > clickTimeout ? Result.TIMED_OUT : Result.RETRY_CLICK;
    }

    /** Do not overlap a pending click or re-click before a delayed server GUI can arrive. */
    public static boolean shouldIssueClick(Result decision, int ticks, int lastClickTick,
                                           int retryDelayTicks, boolean clickPending) {
        return decision == Result.RETRY_CLICK && !clickPending
                && (lastClickTick < 0 || ticks - lastClickTick >= retryDelayTicks);
    }
}
