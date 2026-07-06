package com.jedrock.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blind judge's two cheap checks: movement delta and interaction reach. */
class BlindJudgeTest {

    private final BlindJudge judge = new BlindJudge(true, 7.0, 16.0);

    @Test
    void allowsNormalMovementSteps() {
        assertTrue(judge.allowsMove(0, 64, 0, 0.3, 64, 0.1), "a normal walking step");
        assertTrue(judge.allowsMove(0, 64, 0, 0, 64, 0), "standing still");
        assertTrue(judge.allowsMove(10, 70, 10, 18, 70, 10), "8 blocks — within the generous limit");
    }

    @Test
    void rejectsTeleportSizedJumps() {
        assertFalse(judge.allowsMove(0, 64, 0, 100, 64, 0), "100 blocks in one report");
        assertFalse(judge.allowsMove(0, 64, 0, 0, 200, 0), "flung 136 blocks up");
    }

    @Test
    void allowsEditsWithinReach() {
        // Player at feet (0.5, 64, 0.5); a block right in front is well within reach.
        assertTrue(judge.allowsInteraction(0.5, 64, 0.5, 1, 64, 0));
        assertTrue(judge.allowsInteraction(0.5, 64, 0.5, 0, 68, 0), "a few blocks up, still reachable");
    }

    @Test
    void rejectsEditsBeyondReach() {
        assertFalse(judge.allowsInteraction(0.5, 64, 0.5, 20, 64, 0), "20 blocks away");
        assertFalse(judge.allowsInteraction(0.5, 64, 0.5, 0, 80, 0), "16 blocks up");
    }

    @Test
    void disabledJudgeAllowsEverything() {
        BlindJudge off = new BlindJudge(false, 7.0, 16.0);
        assertTrue(off.allowsMove(0, 64, 0, 9999, 64, 0), "no move check when disabled");
        assertTrue(off.allowsInteraction(0, 64, 0, 9999, 64, 0), "no reach check when disabled");
        assertFalse(off.isEnabled());
    }
}
