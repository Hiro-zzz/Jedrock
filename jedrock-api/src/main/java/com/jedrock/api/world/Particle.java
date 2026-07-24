package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic particles — chosen so each has a genuine counterpart on every edition
 * (JE 1.8 / 1.12.2 share one numeric id table; PE 1.1.5 and 0.14 each have their own type table,
 * carried by a LevelEvent with the particle bit set). One exception is documented on the constant.
 *
 * <p>JE draws a whole burst from one packet (count + offsets); the PE eras draw one particle per
 * packet, so a burst is sent as {@code count} packets with random offsets — keep counts modest.
 */
public enum Particle {

    /** The small white "poof" cloud (a mob death, an egg break). */
    POOF,
    /** The huge explosion bloom. */
    HUGE_EXPLOSION,
    /** An underwater bubble. */
    BUBBLE,
    /** A water splash. */
    SPLASH,
    /** The critical-hit sparks. */
    CRIT,
    /** Small grey smoke. */
    SMOKE,
    /** Large grey smoke. */
    LARGE_SMOKE,
    /** A water drip. */
    DRIP_WATER,
    /** A lava drip. */
    DRIP_LAVA,
    /** The villager storm-cloud (anger). */
    VILLAGER_ANGRY,
    /** The villager green sparkle (happiness). */
    VILLAGER_HAPPY,
    /** A floating note. (PE 0.14 predates it — nearest is the redstone-dust speck.) */
    NOTE,
    /** The purple portal swirl. */
    PORTAL,
    /** The enchantment-table glyphs. */
    ENCHANTMENT,
    /** A small flame (torch fire). */
    FLAME,
    /** A lava ember pop. */
    LAVA,
    /** The red dust of powered redstone. */
    REDSTONE,
    /** The snowball break puff. */
    SNOWBALL_POOF,
    /** A slime blob speck. */
    SLIME,
    /** A floating heart (taming, breeding). */
    HEART
}
