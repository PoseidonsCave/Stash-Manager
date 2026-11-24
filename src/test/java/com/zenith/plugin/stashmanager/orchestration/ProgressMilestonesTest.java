package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressMilestonesTest {
    @Test
    void emitsOnlyAtQuarterMilestones() {
        var before = ProgressMilestones.afterProgress(249, 1000, 25);
        assertFalse(before.crossed());

        var first = ProgressMilestones.afterProgress(250, 1000, before.nextMilestonePercent());
        assertTrue(first.crossed());
        assertEquals(25, first.milestonePercent());
        assertEquals(50, first.nextMilestonePercent());

        var second = ProgressMilestones.afterProgress(500, 1000, first.nextMilestonePercent());
        assertTrue(second.crossed());
        assertEquals(50, second.milestonePercent());

        var third = ProgressMilestones.afterProgress(750, 1000, second.nextMilestonePercent());
        assertTrue(third.crossed());
        assertEquals(75, third.milestonePercent());
        assertEquals(100, third.nextMilestonePercent());

        assertFalse(ProgressMilestones.afterProgress(1000, 1000, 100).crossed());
    }

    @Test
    void coalescesSeveralCrossedMilestonesIntoOneAlert() {
        var crossing = ProgressMilestones.afterProgress(810, 1000, 25);
        assertTrue(crossing.crossed());
        assertEquals(75, crossing.milestonePercent());
        assertEquals(100, crossing.nextMilestonePercent());
    }

    @Test
    void reconstructsTheNextMilestoneAfterRestore() {
        assertEquals(25, ProgressMilestones.nextAfterCompleted(0, 1000));
        assertEquals(50, ProgressMilestones.nextAfterCompleted(250, 1000));
        assertEquals(75, ProgressMilestones.nextAfterCompleted(501, 1000));
        assertEquals(100, ProgressMilestones.nextAfterCompleted(999, 1000));
    }
}
