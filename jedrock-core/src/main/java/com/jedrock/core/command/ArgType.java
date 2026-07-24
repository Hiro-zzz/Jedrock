package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The kind of one command argument — the single thing that both <em>parses</em> a typed value out of a
 * token and <em>suggests</em> completions for a partial one. Declaring a command's arguments as a list of
 * these ({@link CommandArg}) is what lets the core parse them once, with uniform error messages, and offer
 * tab-completion without every command re-implementing either.
 *
 * <p>The built-in types are the {@code static} instances below; {@link #choice} builds one from a fixed
 * list of literals. A type is deliberately small — parse or throw, and (optionally) list suggestions — so
 * a new one is a few lines.
 *
 * @param <T> the Java type {@link #parse} yields (what {@link CommandContext} hands back)
 */
public interface ArgType<T> {

    /** Short human label for the usage line, e.g. {@code "player"} or {@code "int"}. */
    String label();

    /**
     * Turn one token into a typed value, or throw with a message the sender will see. The token is never
     * empty (the parser skips absent optional args before calling this).
     */
    T parse(Server server, CommandSender sender, String token) throws ArgParseException;

    /**
     * Suggestions for a partial token, already narrowed to those that start with {@code partial}
     * (case-insensitively). The default is no suggestions — a free-text type has nothing to offer.
     */
    default List<String> complete(Server server, CommandSender sender, String partial) {
        return List.of();
    }

    /** Whether this type consumes the entire rest of the line as one value (a trailing message). */
    default boolean greedy() {
        return false;
    }

    /** Keep only the options that start with {@code partial}, case-insensitively. The completion filter. */
    static List<String> matching(String partial, List<String> options) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    // ===== Built-in types =====

    /** A single token, taken as-is. No suggestions. */
    ArgType<String> WORD = new ArgType<>() {
        @Override public String label() { return "word"; }
        @Override public String parse(Server s, CommandSender c, String t) { return t; }
    };

    /** The rest of the line as one string (a chat message, a reason). Consumes every remaining token. */
    ArgType<String> GREEDY = new ArgType<>() {
        @Override public String label() { return "text..."; }
        @Override public String parse(Server s, CommandSender c, String t) { return t; }
        @Override public boolean greedy() { return true; }
    };

    /** A whole number. */
    ArgType<Integer> INTEGER = new ArgType<>() {
        @Override public String label() { return "int"; }
        @Override public Integer parse(Server s, CommandSender c, String t) throws ArgParseException {
            try {
                return Integer.valueOf(t.trim());
            } catch (NumberFormatException e) {
                throw new ArgParseException("'" + t + "' is not a whole number");
            }
        }
    };

    /** A decimal number (coordinates, speeds). */
    ArgType<Double> NUMBER = new ArgType<>() {
        @Override public String label() { return "number"; }
        @Override public Double parse(Server s, CommandSender c, String t) throws ArgParseException {
            try {
                return Double.valueOf(t.trim());
            } catch (NumberFormatException e) {
                throw new ArgParseException("'" + t + "' is not a number");
            }
        }
    };

    /** {@code true} / {@code false}, and completes to both. */
    ArgType<Boolean> BOOLEAN = new ArgType<>() {
        @Override public String label() { return "true|false"; }
        @Override public Boolean parse(Server s, CommandSender c, String t) throws ArgParseException {
            String v = t.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true")) return Boolean.TRUE;
            if (v.equals("false")) return Boolean.FALSE;
            throw new ArgParseException("'" + t + "' must be true or false");
        }
        @Override public List<String> complete(Server s, CommandSender c, String partial) {
            return matching(partial, List.of("true", "false"));
        }
    };

    /** An online player, by name. Completes to the online roster — the reason tab-complete exists at all. */
    ArgType<Player> PLAYER = new ArgType<>() {
        @Override public String label() { return "player"; }
        @Override public Player parse(Server server, CommandSender c, String t) throws ArgParseException {
            return server.getPlayer(t).orElseThrow(() -> new ArgParseException("Player not found: " + t));
        }
        @Override public List<String> complete(Server server, CommandSender c, String partial) {
            List<String> names = new ArrayList<>();
            for (Player p : server.getPlayers()) {
                names.add(p.getName());
            }
            return matching(partial, names);
        }
    };

    /** A game mode — the same lenient parse the {@code /gamemode} command has always used, plus suggestions. */
    ArgType<GameMode> GAME_MODE = new ArgType<>() {
        @Override public String label() { return "mode"; }
        @Override public GameMode parse(Server s, CommandSender c, String t) throws ArgParseException {
            GameMode mode = GameMode.fromString(t);
            if (mode == null) {
                throw new ArgParseException("Unknown game mode: " + t);
            }
            return mode;
        }
        @Override public List<String> complete(Server s, CommandSender c, String partial) {
            return matching(partial, List.of("survival", "creative", "adventure", "spectator"));
        }
    };

    /**
     * A choice from a fixed set of literal words — completes to them and rejects anything else. The value
     * is the chosen literal. Matching is case-insensitive; the canonical spelling from {@code options} is
     * returned.
     */
    static ArgType<String> choice(String... options) {
        List<String> choices = List.of(options);
        return new ArgType<>() {
            @Override public String label() { return String.join("|", choices); }
            @Override public String parse(Server s, CommandSender c, String t) throws ArgParseException {
                for (String option : choices) {
                    if (option.equalsIgnoreCase(t.trim())) {
                        return option;
                    }
                }
                throw new ArgParseException("'" + t + "' must be one of: " + String.join(", ", choices));
            }
            @Override public List<String> complete(Server s, CommandSender c, String partial) {
                return matching(partial, choices);
            }
        };
    }
}
