package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic sounds — the set every edition can honour (natively or by a documented
 * nearest neighbour). The server never plays audio; it asks each client to, in its own dialect — the
 * illusionist model applied to ears.
 *
 * <p>Per edition: <b>JE</b> (1.8 and 1.12.2) uses the Named Sound Effect packet with each era's sound
 * name. <b>PE 1.1.5</b> uses the LevelEvent 1000-series sound ids, or LevelSoundEvent for the few
 * sounds (explode, level-up, note) that live only there. <b>PE 0.14</b> has the smallest palette
 * (LevelEvent only) — sounds it predates fall back to the closest available id, so a call always makes
 * <i>a</i> sound rather than silence.
 */
public enum Sound {

    /** A UI / stone-button click. */
    CLICK,
    /** A wooden door opening. */
    DOOR,
    /** A fire being extinguished / water hissing on something hot. */
    FIZZ,
    /** An arrow leaving a bow. */
    BOW,
    /** An enderman-style teleport whoosh. */
    TELEPORT,
    /** An anvil landing a blow. */
    ANVIL_USE,
    /** An anvil breaking. */
    ANVIL_BREAK,
    /** A TNT / creeper explosion. (PE 0.14: nearest is the door-crash bang.) */
    EXPLODE,
    /** The level-up chime. (PE 0.14: nearest is a click.) */
    LEVELUP,
    /** The item-pickup pop. (PE 0.14: nearest is a click.) */
    POP,
    /** The experience-orb ding. (PE 0.14: nearest is a click.) */
    ORB,
    /** A note-block pling. (PE 0.14: nearest is a click.) */
    NOTE
}
