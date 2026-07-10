package com.jedrock.api.player;

/**
 * Minimal game mode abstraction.
 */
public enum GameMode {
    SURVIVAL(0),
    CREATIVE(1),
    ADVENTURE(2),
    SPECTATOR(3);

    private final int id;

    GameMode(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static GameMode fromId(int id) {
        return switch (id) {
            case 0 -> SURVIVAL;
            case 1 -> CREATIVE;
            case 2 -> ADVENTURE;
            case 3 -> SPECTATOR;
            default -> SURVIVAL;
        };
    }

    /** Whether flight is freely allowed in this mode (creative / spectator) — drives the fly ability bit. */
    public boolean allowsFlight() {
        return this == CREATIVE || this == SPECTATOR;
    }

    /** Human-friendly name for messages, e.g. {@code "Survival"}. */
    public String displayName() {
        String lower = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Parse a mode from user input — a name ({@code survival}, {@code creative}, {@code adventure},
     * {@code spectator}), the common one-letter shorthands ({@code s} / {@code c} / {@code a} /
     * {@code sp}), or a numeric id ({@code 0}..{@code 3}). Case-insensitive. {@code null} if unrecognised.
     */
    public static GameMode fromString(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "survival", "s", "0" -> SURVIVAL;
            case "creative", "c", "1" -> CREATIVE;
            case "adventure", "a", "2" -> ADVENTURE;
            case "spectator", "sp", "3" -> SPECTATOR;
            default -> null;
        };
    }
}
