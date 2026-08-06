package com.jedrock.api.item;

import java.util.Locale;

/**
 * Canonical enchantments — the set <b>every</b> target version of this server can render, which is the
 * pre-1.9 one: the twenty-five below. Frost walker, mending and the curses are deliberately absent, being
 * 1.9-era additions that MCPE 0.14 and JE 1.8 predate; the same rule {@link com.jedrock.api.world.Sound}
 * follows.
 *
 * <p><b>There is no id here, and that is the point.</b> Where status effects are numbered identically by
 * all four editions, enchantments are not — and not by an offset either. Java has sharpness at 16 and
 * Bedrock at 9; Java orders respiration, aqua affinity, thorns where Bedrock has thorns, respiration,
 * aqua affinity. A single shared number would quietly hand somebody thorns when they asked for
 * respiration, so the mapping lives per edition in the network layer ({@code EnchantmentIds}) and a
 * canonical enchantment is just this name.
 *
 * <p><b>What the server does about one is a separate question from what it renders.</b> All of these
 * reach the client, glint and read correctly. Only some change what the server does — see the readme, or
 * {@link #isHonoured()} for the short answer — because this server simulates no durability, no
 * projectiles, no fire and no knockback, and an enchantment for something that doesn't exist can only
 * ever be decoration.
 */
public enum Enchantment {

    /** Less damage from everything. Honoured. */
    PROTECTION,
    /** Less damage from fire — decorative here, there being no fire. */
    FIRE_PROTECTION,
    /** Less fall damage. Honoured: falls are real and the server bills them. */
    FEATHER_FALLING,
    /** Less damage from explosions — decorative, there being none. */
    BLAST_PROTECTION,
    /** Less damage from projectiles — decorative, there being none. */
    PROJECTILE_PROTECTION,
    /** Breathe underwater longer — decorative, drowning being unsimulated. */
    RESPIRATION,
    /** Mine at full speed underwater — the client's own arithmetic, so it works by arriving. */
    AQUA_AFFINITY,
    /** Hurt whoever hits you. Honoured. */
    THORNS,
    /** Walk faster in water — the client's own movement. */
    DEPTH_STRIDER,
    /** More melee damage. Honoured. */
    SHARPNESS,
    /** More damage to the undead — honoured as sharpness is, there being no undead to distinguish. */
    SMITE,
    /** More damage to arthropods — likewise. */
    BANE_OF_ARTHROPODS,
    /** Knock the target back — decorative: this server simulates no physics, so nothing is knocked. */
    KNOCKBACK,
    /** Set the target alight — decorative, there being no fire. */
    FIRE_ASPECT,
    /** Better mob drops — decorative, mobs here being puppets that drop nothing. */
    LOOTING,
    /** Mine faster. Nothing to do: legacy clients time their own digging from the item's NBT. */
    EFFICIENCY,
    /** Drop the block itself — which this server already always does, so it changes nothing. */
    SILK_TOUCH,
    /** Wear out more slowly — decorative, there being no durability. */
    UNBREAKING,
    /** More from a mined block. Honoured: the server is what hands the drop out. */
    FORTUNE,
    /** Stronger arrows — decorative, there being no projectiles. */
    POWER,
    /** Arrows knock back — decorative, likewise. */
    PUNCH,
    /** Flaming arrows — decorative, likewise. */
    FLAME,
    /** Arrows for free — decorative, likewise. */
    INFINITY,
    /** Better fishing loot — decorative, there being no fishing. */
    LUCK_OF_THE_SEA,
    /** Faster bites — decorative, likewise. */
    LURE;

    /** The name as somebody types it: lower-case with underscores ({@code fire_aspect}). */
    public String getKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the <b>server</b> acts on this one, as opposed to merely sending it for the client to draw.
     *
     * <p>Honest rather than aspirational: an enchantment is honoured here only where the core already owns
     * the decision it would change — melee damage, damage taken, a fall, and what a mined block drops.
     * Everything else is for a system this server deliberately doesn't have.
     */
    public boolean isHonoured() {
        return switch (this) {
            case SHARPNESS, SMITE, BANE_OF_ARTHROPODS,
                 PROTECTION, FEATHER_FALLING, THORNS, FORTUNE -> true;
            default -> false;
        };
    }

    /** The enchantment that key names, or {@code null}. Lenient about case and surrounding space. */
    public static Enchantment fromString(String key) {
        if (key == null) {
            return null;
        }
        String text = key.trim().toUpperCase(Locale.ROOT);
        for (Enchantment e : values()) {
            if (e.name().equals(text)) {
                return e;
            }
        }
        return null;
    }
}
