package com.jedrock.network.handler.je;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version-neutral sidebar logic: create once, diff on update (no flicker), disambiguate duplicate
 * lines, and tear down cleanly. The version-specific packet bytes are the handlers' business; here the
 * {@link JeScoreboard.Wire} is recorded so the sequence of intents can be asserted.
 */
class JeScoreboardTest {

    /** Records the calls the scoreboard makes, in order. */
    private static final class RecordingWire implements JeScoreboard.Wire {
        final List<String> calls = new ArrayList<>();
        @Override public void objectiveCreate(String o, String t) { calls.add("create:" + t); }
        @Override public void objectiveUpdateTitle(String o, String t) { calls.add("title:" + t); }
        @Override public void objectiveRemove(String o) { calls.add("remove-obj"); }
        @Override public void displaySidebar(String o) { calls.add("display"); }
        @Override public void scoreSet(String e, String o, int v) { calls.add("set:" + e + "=" + v); }
        @Override public void scoreRemove(String e, String o) { calls.add("del:" + e); }
    }

    @Test
    void firstSetCreatesTheObjectiveDisplaysItAndScoresTopDown() {
        RecordingWire wire = new RecordingWire();
        new JeScoreboard(wire).set("Title", List.of("first", "second"));

        // Create + display come first, then each line scored so the top line sorts highest.
        assertEquals("create:Title", wire.calls.get(0));
        assertEquals("display", wire.calls.get(1));
        assertEquals("set:first§0=2", wire.calls.get(2), "top line, highest score");
        assertEquals("set:second§1=1", wire.calls.get(3));
        assertEquals(4, wire.calls.size());
    }

    @Test
    void aSecondSetOnlyTouchesWhatChanged() {
        RecordingWire wire = new RecordingWire();
        JeScoreboard sb = new JeScoreboard(wire);
        sb.set("T", List.of("alpha", "beta"));
        wire.calls.clear();

        // Same title, first line unchanged, second line changed: no create/display, no retitle,
        // the gone entry removed, and the current entries re-scored.
        sb.set("T", List.of("alpha", "gamma"));

        assertTrue(wire.calls.contains("del:beta§1"), wire.calls.toString());
        assertTrue(wire.calls.contains("set:gamma§1=1"), wire.calls.toString());
        assertTrue(wire.calls.stream().noneMatch(s -> s.startsWith("create") || s.equals("display")
                || s.startsWith("title")), "no teardown/retitle: " + wire.calls);
    }

    @Test
    void aChangedTitleRetitlesWithoutRecreating() {
        RecordingWire wire = new RecordingWire();
        JeScoreboard sb = new JeScoreboard(wire);
        sb.set("Old", List.of("x"));
        wire.calls.clear();

        sb.set("New", List.of("x"));

        assertTrue(wire.calls.contains("title:New"), wire.calls.toString());
        assertTrue(wire.calls.stream().noneMatch(s -> s.startsWith("create")), "no recreate");
    }

    @Test
    void duplicateLinesGetDistinctInvisibleSuffixesSoBothShow() {
        RecordingWire wire = new RecordingWire();
        new JeScoreboard(wire).set("T", List.of("same", "same"));

        // The two identical lines become distinct score-holders via their row colour code.
        assertTrue(wire.calls.contains("set:same§0=2"), wire.calls.toString());
        assertTrue(wire.calls.contains("set:same§1=1"), wire.calls.toString());
    }

    @Test
    void moreThanTheMaxLinesAreDropped() {
        RecordingWire wire = new RecordingWire();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < JeScoreboard.MAX_LINES + 5; i++) {
            many.add("line" + i);
        }
        new JeScoreboard(wire).set("T", many);

        long scored = wire.calls.stream().filter(s -> s.startsWith("set:")).count();
        assertEquals(JeScoreboard.MAX_LINES, scored, "capped at the max");
    }

    @Test
    void clearRemovesTheObjectiveAndResetsSoTheNextSetRecreates() {
        RecordingWire wire = new RecordingWire();
        JeScoreboard sb = new JeScoreboard(wire);
        sb.set("T", List.of("a"));
        sb.clear();
        assertEquals("remove-obj", wire.calls.get(wire.calls.size() - 1));

        wire.calls.clear();
        sb.set("T", List.of("a"));
        assertEquals("create:T", wire.calls.get(0), "after clear, the objective is created again");
    }

    @Test
    void clearWithoutAShownSidebarDoesNothing() {
        RecordingWire wire = new RecordingWire();
        new JeScoreboard(wire).clear();
        assertTrue(wire.calls.isEmpty());
    }
}
