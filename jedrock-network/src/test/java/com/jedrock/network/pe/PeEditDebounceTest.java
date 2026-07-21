package com.jedrock.network.pe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.1.5 edit double-fire collapse: one physical action yields one edit, while a stream of deliberate
 * edits all land. Clock is injected (ms → ns) so the windows are exercised precisely. Placement uses both
 * rules (burst + same-cell); break uses same-cell only (burst disabled) so fast-mining distinct cells is
 * never dropped.
 */
class PeEditDebounceTest {

    private static final long MS = 1_000_000L;
    // 100 ms burst window, 500 ms same-cell window — the production placement defaults.
    private static PeEditDebounce debounce() {
        return new PeEditDebounce(100 * MS, 500 * MS);
    }

    @Test
    void firstPlacementIsAlwaysAccepted() {
        assertTrue(debounce().accept(10, 64, 10, 0));
    }

    @Test
    void exactRepeatInSameBurstIsDropped() {
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0), "first shot places");
        assertFalse(d.accept(10, 64, 10, 3 * MS), "the client's echo of the same cell is dropped");
    }

    @Test
    void staircaseBurstAtDifferentCellsIsDropped() {
        // One tap → the client re-runs the place against the block it just drew: a stack of cells,
        // all within a few ms. Only the first should survive.
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0));
        assertFalse(d.accept(10, 65, 10, 2 * MS), "staircase +1");
        assertFalse(d.accept(10, 66, 10, 4 * MS), "staircase +2");
    }

    @Test
    void droppedShotsDoNotExtendTheBurstWindow() {
        // The old debounce reset its timer on every attempt, so a steady stream never escaped the window
        // and only its first block placed. Here the window is measured from the last APPLIED placement,
        // so a burst of drops can't keep pushing the deadline out.
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0));
        assertFalse(d.accept(11, 64, 10, 30 * MS));
        assertFalse(d.accept(12, 64, 10, 60 * MS));
        assertFalse(d.accept(13, 64, 10, 90 * MS));
        // 120 ms after the applied one > 100 ms burst → this deliberate placement gets through.
        assertTrue(d.accept(14, 64, 10, 120 * MS), "past the burst window, a new placement applies");
    }

    @Test
    void continuousBuildingPlacesEveryBlock() {
        // A human dragging out a line places distinct cells slower than the burst window; every one lands.
        PeEditDebounce d = debounce();
        long t = 0;
        for (int x = 0; x < 8; x++) {
            assertTrue(d.accept(x, 64, 20, t), "placement at x=" + x + " should apply");
            t += 130 * MS; // ~7-8 blocks/sec, comfortably above the 100 ms burst window
        }
    }

    @Test
    void sameCellPastBurstButWithinEchoWindowIsDropped() {
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0));
        // 200 ms later: past the 100 ms burst, but a repeat of the SAME cell inside 500 ms is an echo.
        assertFalse(d.accept(10, 64, 10, 200 * MS));
    }

    @Test
    void sameCellAfterEchoWindowPlacesAgain() {
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0));
        // 600 ms later the same cell is a genuine new click (e.g. re-placing after a break) → applies.
        assertTrue(d.accept(10, 64, 10, 600 * MS));
    }

    @Test
    void differentCellPastBurstIsNeverBlockedByEchoRule() {
        PeEditDebounce d = debounce();
        assertTrue(d.accept(10, 64, 10, 0));
        assertTrue(d.accept(20, 64, 20, 150 * MS), "a different cell past the burst always places");
    }

    // --- break mode: burst rule disabled, same-cell echo only ---

    @Test
    void breakCollapsesStartAndContinueOnTheSameCell() {
        // A creative break fires START_BREAK then a stream of CONTINUE_BREAK on the same cell: one edit.
        PeEditDebounce d = PeEditDebounce.forBreak();
        assertTrue(d.accept(5, 70, 5, 0), "START_BREAK removes the block");
        assertFalse(d.accept(5, 70, 5, 4 * MS), "CONTINUE_BREAK on the same cell is dropped");
        assertFalse(d.accept(5, 70, 5, 30 * MS), "further CONTINUE_BREAK still dropped");
    }

    @Test
    void breakNeverDropsADistinctFastMinedCell() {
        // Sweeping the cursor across blocks in creative breaks distinct cells only milliseconds apart —
        // the burst rule (which placement uses) would wrongly eat these, so break must let them all pass.
        PeEditDebounce d = PeEditDebounce.forBreak();
        assertTrue(d.accept(0, 70, 0, 0));
        assertTrue(d.accept(1, 70, 0, 5 * MS), "a different cell 5 ms later still breaks");
        assertTrue(d.accept(2, 70, 0, 10 * MS), "and the next");
        assertTrue(d.accept(3, 70, 0, 15 * MS), "and the next");
    }

    @Test
    void breakSameCellAgainAfterWindowIsANewBreak() {
        PeEditDebounce d = PeEditDebounce.forBreak();
        assertTrue(d.accept(5, 70, 5, 0));
        // Re-mining the same cell after the echo window (e.g. a placed-then-broken block) is genuine.
        assertTrue(d.accept(5, 70, 5, 600 * MS));
    }
}
