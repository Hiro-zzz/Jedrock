package com.jedrock.api.event.player;

/**
 * Why a player is taking damage. Deliberately coarse — the illusionist server has only a handful of real
 * damage sources, and a listener that wants finer detail can read the rest of the event.
 */
public enum DamageCause {

    /** Hit the ground too hard (a fall the server tracked, or a 1.1.5 client's own EntityFall report). */
    FALL,

    /** Dropped past the finite world's floor. */
    VOID,

    /** Struck by another player in melee. */
    ATTACK,

    /** A {@code /kill} command or another administrative source. */
    KILL,

    /** An instant-damage effect — the one damage source that is a status effect rather than an event. */
    MAGIC,

    /** Anything else. */
    GENERIC
}
