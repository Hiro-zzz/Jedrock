package com.jedrock.api.event;

/**
 * The order in which listeners see an event. Lower priorities run first, so a {@link #HIGHEST} listener
 * gets the last word on what happens and {@link #MONITOR} sees the final, settled outcome.
 *
 * <p>The mental model (Bukkit's, which plugin authors already know): earlier priorities <em>propose</em>,
 * later ones <em>decide</em>. A protection plugin cancels at {@link #HIGH}; an override that must win
 * un-cancels at {@link #HIGHEST}; a logger reads the result at {@link #MONITOR} and must not change it.
 */
public enum EventPriority {

    /** Runs first — the least authoritative; anything later can overrule it. */
    LOWEST,
    LOW,
    /** The default when a listener registers without asking for one. */
    NORMAL,
    HIGH,
    /** Runs last among deciders — the final say on cancellation and mutable fields. */
    HIGHEST,
    /**
     * Runs after every decider, purely to observe the settled event. A monitor must not mutate it or flip
     * cancellation — by the time it runs, the outcome is fixed and something may already have acted on it.
     */
    MONITOR
}
