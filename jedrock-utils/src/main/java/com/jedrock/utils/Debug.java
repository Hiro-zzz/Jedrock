package com.jedrock.utils;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Optional extended-debug switch. Off by default, so {@code LOGGER.debug(...)} calls cost nothing in
 * the hot path (the supplier is never invoked). It can be turned on globally, or scoped to logger-name
 * substrings so only chosen subsystems get verbose — e.g. {@code pe}, {@code chunk}, {@code keepalive}.
 *
 * <p>Initialised from the {@code -Djedrock.debug} system property: unset/{@code off} = disabled;
 * {@code all}/{@code true} = every subsystem; a comma list like {@code pe,chunk} = only loggers whose
 * (lower-cased) name contains one of those tags. Also reconfigurable at runtime, e.g. from the console.
 */
public final class Debug {

    private static volatile boolean enabled;
    /** Lower-cased logger-name substrings to allow; empty (while enabled) = every subsystem. */
    private static volatile Set<String> categories = Set.of();

    private Debug() {}

    static {
        configure(System.getProperty("jedrock.debug"));
    }

    /** @return whether debug output should be emitted for a logger with this name. */
    public static boolean enabled(String loggerName) {
        if (!enabled) {
            return false;
        }
        Set<String> cats = categories;
        if (cats.isEmpty()) {
            return true;
        }
        String name = loggerName.toLowerCase();
        for (String c : cats) {
            if (name.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether any debug output is enabled at all. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Reconfigure from a spec: {@code null}/blank/{@code off}/{@code none}/{@code false} disables all;
     * {@code all}/{@code true}/{@code *} enables every subsystem; otherwise a comma-separated list of
     * logger-name tags to allow.
     */
    public static void configure(String spec) {
        String s = spec == null ? "" : spec.trim().toLowerCase();
        switch (s) {
            case "", "off", "none", "false" -> {
                enabled = false;
                categories = Set.of();
            }
            case "all", "true", "*" -> {
                enabled = true;
                categories = Set.of();
            }
            default -> {
                Set<String> cats = new CopyOnWriteArraySet<>();
                for (String part : s.split(",")) {
                    String p = part.trim();
                    if (!p.isEmpty()) {
                        cats.add(p);
                    }
                }
                categories = cats;
                enabled = !cats.isEmpty();
            }
        }
    }

    /** @return a human-readable description of the current state ({@code off}, {@code all}, or the tag list). */
    public static String describe() {
        if (!enabled) {
            return "off";
        }
        Set<String> cats = categories;
        return cats.isEmpty() ? "all" : String.join(",", cats);
    }
}
