package com.jedrock.api.entity;

import java.util.Locale;

/**
 * Canonical status effects — speed, strength, invisibility and the rest of the legacy set.
 *
 * <p>Unlike {@link com.jedrock.api.world.Sound}, the editions do not disagree about the <em>numbering</em>
 * here: all four targets use the same legacy effect ids, so the id lives on the constant and each
 * protocol differs only in how it writes one (a byte on Java and 0.14, a zigzag varint on 1.1.5). What
 * they do disagree about is which effects exist at all — MCPE 0.14 knows sixteen of these — and that is
 * the network layer's business, not this enum's.
 *
 * <p><b>An effect is scenery, in the same sense the weather and the clock are.</b> The server says
 * "speed II for thirty seconds" once and the <em>client</em> draws the swirl, tints the screen and moves
 * faster; nothing on this side ticks to make that happen. Because movement here is client-authoritative,
 * a speed effect is genuinely fast rather than a picture of fast. The core steps in only where it already
 * owns the answer — how far a player may plausibly move, and how much damage a hit does — and everything
 * else is the client's own rendering.
 *
 * <p>Levitation (24) is deliberately absent: it arrived in 1.9 and half the targets here predate it.
 */
public enum Effect {

    /** Move faster. Widens the movement allowance the server will believe. */
    SPEED(1),
    /** Move slower. */
    SLOWNESS(2),
    /** Break blocks faster. Cosmetic here — break timing is the client's. */
    HASTE(3),
    /** Break blocks slower. Cosmetic here, for the same reason. */
    MINING_FATIGUE(4),
    /** Deal more melee damage. Honoured when a hit is resolved. */
    STRENGTH(5),
    /** Heal immediately. Applied by the server, since health is the one thing it is authoritative for. */
    INSTANT_HEALTH(6),
    /** Hurt immediately. Applied by the server, for the same reason. */
    INSTANT_DAMAGE(7),
    /** Jump higher. Widens the movement allowance with {@link #SPEED}. */
    JUMP_BOOST(8),
    /** The wobbling screen. Purely the client's. */
    NAUSEA(9),
    /** Regenerate health. Cosmetic here — see the class doc: the server does not tick health. */
    REGENERATION(10),
    /** Take less damage. Honoured when damage is applied. */
    RESISTANCE(11),
    /** Immunity to fire. Cosmetic here — there is no fire in this world model. */
    FIRE_RESISTANCE(12),
    /** Breathe underwater. Cosmetic here — drowning isn't simulated. */
    WATER_BREATHING(13),
    /** Vanish. The one effect that changes what other players are sent. */
    INVISIBILITY(14),
    /** The dark screen. Purely the client's. */
    BLINDNESS(15),
    /** See in the dark. Purely the client's. */
    NIGHT_VISION(16),
    /** Drain hunger. Cosmetic here — there is no hunger. */
    HUNGER(17),
    /** Deal less melee damage. Honoured when a hit is resolved. */
    WEAKNESS(18),
    /** Damage over time. Cosmetic here — ticking health would be simulation. */
    POISON(19),
    /** Damage over time, darker. Cosmetic, as poison is. */
    WITHER(20),
    /** More maximum health. Cosmetic here — the health model is a fixed twenty. */
    HEALTH_BOOST(21),
    /** Absorption hearts. Cosmetic, as health boost is. */
    ABSORPTION(22),
    /** Keeps hunger full. Cosmetic here — there is no hunger. */
    SATURATION(23);

    private final int id;

    Effect(int id) {
        this.id = id;
    }

    /** The legacy numeric id, shared by every edition this server speaks. */
    public int getId() {
        return id;
    }

    /** The name as somebody types it: lower-case with underscores ({@code jump_boost}). */
    public String getKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The effect that key names, or {@code null}. Lenient about case and surrounding space. */
    public static Effect fromString(String key) {
        if (key == null) {
            return null;
        }
        String text = key.trim().toUpperCase(Locale.ROOT);
        for (Effect effect : values()) {
            if (effect.name().equals(text)) {
                return effect;
            }
        }
        return null;
    }

    /** The effect with this legacy id, or {@code null} — the inbound direction, for a raw id. */
    public static Effect fromId(int id) {
        for (Effect effect : values()) {
            if (effect.id == id) {
                return effect;
            }
        }
        return null;
    }

    /**
     * Whether this one lands at an instant rather than lasting — instant health and damage are a change
     * to a number, not a state to hold, so they are applied and never stored.
     */
    public boolean isInstant() {
        return this == INSTANT_HEALTH || this == INSTANT_DAMAGE;
    }
}
