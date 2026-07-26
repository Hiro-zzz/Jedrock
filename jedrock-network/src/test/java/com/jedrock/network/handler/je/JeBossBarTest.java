package com.jedrock.network.handler.je;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss-bar sequencing: add once, then only the deltas that changed, then remove — the wire has one
 * add and incremental updates, so this is what keeps a per-tick refresh from re-adding the bar.
 */
class JeBossBarTest {

    private static final class RecordingWire implements JeBossBar.Wire {
        final List<String> calls = new ArrayList<>();
        @Override public void add(String t, float p, int c) { calls.add("add:" + t + "/" + p + "/" + c); }
        @Override public void updateHealth(float p) { calls.add("health:" + p); }
        @Override public void updateTitle(String t) { calls.add("title:" + t); }
        @Override public void updateStyle(int c) { calls.add("style:" + c); }
        @Override public void remove() { calls.add("remove"); }
    }

    @Test
    void firstSetAddsTheBar() {
        RecordingWire wire = new RecordingWire();
        new JeBossBar(wire).set("Boss", 1.0f, 5);
        assertEquals(List.of("add:Boss/1.0/5"), wire.calls);
    }

    @Test
    void aLaterSetUpdatesHealthAndTitleButNotStyleWhenTheColourIsUnchanged() {
        RecordingWire wire = new RecordingWire();
        JeBossBar bar = new JeBossBar(wire);
        bar.set("Boss", 1.0f, 5);
        wire.calls.clear();

        bar.set("Boss", 0.5f, 5);

        assertEquals(List.of("health:0.5", "title:Boss"), wire.calls, "no re-add, no style change");
    }

    @Test
    void aColourChangeAlsoUpdatesTheStyle() {
        RecordingWire wire = new RecordingWire();
        JeBossBar bar = new JeBossBar(wire);
        bar.set("Boss", 1.0f, 5);
        wire.calls.clear();

        bar.set("Boss", 0.5f, 2);

        assertTrue(wire.calls.contains("style:2"), wire.calls.toString());
    }

    @Test
    void clearRemovesAndLetsTheNextSetReAdd() {
        RecordingWire wire = new RecordingWire();
        JeBossBar bar = new JeBossBar(wire);
        bar.set("Boss", 1.0f, 5);
        bar.clear();
        assertEquals("remove", wire.calls.get(wire.calls.size() - 1));

        wire.calls.clear();
        bar.set("Boss", 1.0f, 5);
        assertEquals("add:Boss/1.0/5", wire.calls.get(0), "after clear, the bar is added again");
    }

    @Test
    void clearWithoutABarDoesNothing() {
        RecordingWire wire = new RecordingWire();
        new JeBossBar(wire).clear();
        assertTrue(wire.calls.isEmpty());
    }
}
