package com.jedrock.core.command;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;

import java.util.Map;

/**
 * The parsed arguments of one command invocation — the typed values an {@link ArgCommand}'s body reads
 * instead of re-parsing a {@code String[]}. Built by the parser after every declared {@link CommandArg}
 * has been validated, so a getter here never fails on a value that made it this far; a missing optional
 * argument is the one thing to check for, with {@link #has}.
 */
public final class CommandContext {

    private final Map<String, Object> values;

    CommandContext(Map<String, Object> values) {
        this.values = values;
    }

    /** Whether an (optional) argument was supplied. Always {@code true} for a required one. */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** The raw parsed value, or {@code null} if the argument was absent. */
    public Object get(String name) {
        return values.get(name);
    }

    /** A {@link ArgType#PLAYER} argument. */
    public Player getPlayer(String name) {
        return (Player) values.get(name);
    }

    /** A {@link ArgType#INTEGER} argument, or {@code fallback} if it was absent. */
    public int getInt(String name, int fallback) {
        Object v = values.get(name);
        return v == null ? fallback : (Integer) v;
    }

    /** A {@link ArgType#NUMBER} argument, or {@code fallback} if it was absent. */
    public double getDouble(String name, double fallback) {
        Object v = values.get(name);
        return v == null ? fallback : (Double) v;
    }

    /** A {@link ArgType#BOOLEAN} argument, or {@code fallback} if it was absent. */
    public boolean getBoolean(String name, boolean fallback) {
        Object v = values.get(name);
        return v == null ? fallback : (Boolean) v;
    }

    /** A {@link ArgType#GAME_MODE} argument. */
    public GameMode getGameMode(String name) {
        return (GameMode) values.get(name);
    }

    /** A {@link ArgType#WORD}, {@link ArgType#GREEDY} or {@link ArgType#choice} argument, or {@code null}. */
    public String getString(String name) {
        return (String) values.get(name);
    }
}
