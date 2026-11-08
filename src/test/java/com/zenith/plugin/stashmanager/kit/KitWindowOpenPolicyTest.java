package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static com.zenith.plugin.stashmanager.kit.KitWindowOpenPolicy.Result.RETRY_CLICK;
import static com.zenith.plugin.stashmanager.kit.KitWindowOpenPolicy.Result.WAIT_FOR_CONTENTS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitWindowOpenPolicyTest {
    @Test
    void waitsForPendingClickAndServerOpenGraceBeforeRetrying() {
        assertTrue(KitWindowOpenPolicy.shouldIssueClick(RETRY_CLICK, 1, -1, 80, false));
        assertFalse(KitWindowOpenPolicy.shouldIssueClick(RETRY_CLICK, 12, 1, 80, false));
        assertFalse(KitWindowOpenPolicy.shouldIssueClick(RETRY_CLICK, 81, 1, 80, true));
        assertTrue(KitWindowOpenPolicy.shouldIssueClick(RETRY_CLICK, 81, 1, 80, false));
        assertFalse(KitWindowOpenPolicy.shouldIssueClick(WAIT_FOR_CONTENTS, 81, 1, 80, false));
    }
}
