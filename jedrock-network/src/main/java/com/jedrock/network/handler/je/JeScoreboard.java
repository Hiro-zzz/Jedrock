package com.jedrock.network.handler.je;

import java.util.ArrayList;
import java.util.List;

/**
 * A per-connection sidebar scoreboard for one Java client. Holds the entries it has shown so a
 * {@link #set} only sends what changed — no flicker from tearing the objective down and rebuilding it
 * each update. The version-specific packet bytes are supplied by a {@link Wire} the handler implements;
 * everything about <em>which</em> packets to send lives here, shared by 1.8 and 1.12.2.
 *
 * <p>The illusion: the sidebar shows lines of text, top to bottom. Ordering is carried by a per-line
 * score (higher = higher up), which the pre-1.13 client renders as a small red number on the right —
 * the vanilla scoreboard look, unavoidable without the 1.13 number-format. Two identical lines would
 * collide as score-holder names, so each carries an invisible trailing colour code unique to its row.
 */
final class JeScoreboard {

    /** The single objective this sidebar uses; short and unlikely to clash with anything else. */
    static final String OBJECTIVE = "jd-sb";

    /** Sidebar entries cap: one invisible disambiguator per row, drawn from the 16 colour codes. */
    static final int MAX_LINES = 16;

    /** Max length of a score-holder name on the wire (1.8); the invisible suffix must fit inside it. */
    private static final int MAX_ENTRY = 40;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** The version-specific packet writes. The scoreboard decides when; the handler decides how. */
    interface Wire {
        void objectiveCreate(String objective, String title);
        void objectiveUpdateTitle(String objective, String title);
        void objectiveRemove(String objective);
        void displaySidebar(String objective);
        void scoreSet(String entry, String objective, int value);
        void scoreRemove(String entry, String objective);
    }

    private final Wire wire;
    private boolean created;
    private String title = "";
    /** The entry strings currently on screen, top to bottom — the disambiguated line texts. */
    private List<String> entries = new ArrayList<>();

    JeScoreboard(Wire wire) {
        this.wire = wire;
    }

    /**
     * Show {@code lines} (top to bottom) under {@code title}, sending only the difference from what is
     * already up. Creates the objective on first use, retitles it when the title changes, adds and
     * removes score entries as the lines change, and never touches a row that stayed the same.
     */
    void set(String title, List<String> lines) {
        if (!created) {
            wire.objectiveCreate(OBJECTIVE, title);
            wire.displaySidebar(OBJECTIVE);
            created = true;
            this.title = title;
        } else if (!title.equals(this.title)) {
            wire.objectiveUpdateTitle(OBJECTIVE, title);
            this.title = title;
        }

        List<String> next = disambiguate(lines);
        // Remove entries that are gone.
        for (String old : entries) {
            if (!next.contains(old)) {
                wire.scoreRemove(old, OBJECTIVE);
            }
        }
        // Set the score of every current entry: top line gets the highest number, so it sorts to the top.
        for (int i = 0; i < next.size(); i++) {
            wire.scoreSet(next.get(i), OBJECTIVE, next.size() - i);
        }
        entries = next;
    }

    /** Tear the sidebar down (removing the objective drops every score with it). */
    void clear() {
        if (created) {
            wire.objectiveRemove(OBJECTIVE);
            created = false;
            title = "";
            entries = new ArrayList<>();
        }
    }

    /**
     * Turn the caller's lines into unique score-holder names: cap the count, trim each to fit the wire
     * limit once its invisible suffix is added, and append a trailing colour code (row index → one of 16)
     * so two identical lines don't collapse into one entry. The suffix renders as nothing.
     */
    private static List<String> disambiguate(List<String> lines) {
        int count = Math.min(lines.size(), MAX_LINES);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = lines.get(i) == null ? "" : lines.get(i);
            if (text.length() > MAX_ENTRY - 2) {
                text = text.substring(0, MAX_ENTRY - 2); // leave room for the 2-char suffix
            }
            out.add(text + "§" + HEX[i]);
        }
        return out;
    }
}
