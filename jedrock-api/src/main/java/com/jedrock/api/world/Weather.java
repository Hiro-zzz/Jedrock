package com.jedrock.api.world;

/**
 * The world's weather — pure client-side scenery in the illusionist model: the server keeps one enum
 * and asks each client to draw it (JE Change Game State; PE LevelEvent 3001-series, spoken by both
 * eras). No simulation: it never rains harder, never times out, and never affects gameplay — a script
 * or {@code /weather} changes it, and a late joiner is told the current state on login.
 */
public enum Weather {

    /** Clear skies. */
    CLEAR,
    /** Rain (snow in a cold biome — the client decides by biome). */
    RAIN,
    /** Rain plus the darkened thunderstorm sky. */
    THUNDER
}
