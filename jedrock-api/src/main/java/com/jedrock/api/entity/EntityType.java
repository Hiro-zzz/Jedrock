package com.jedrock.api.entity;

import java.util.Locale;

/**
 * Canonical, protocol-agnostic puppet entity types. Deliberately a small set of mobs that exist across
 * <em>all</em> supported editions (JE 1.12.2 / 1.8, PE 1.1.5 / 0.14), so a puppet renders everywhere. The
 * network layer maps each to its per-edition numeric id (the entity counterpart of the block palette); the
 * {@code api} never sees a wire id. Extend as the tested cross-edition set grows.
 */
public enum EntityType {
    /**
     * A human-shaped puppet rendered as a <em>player avatar</em> (an NPC), not a mob. It uses the same
     * cross-edition avatar machinery real players do (a tab / player-list entry plus a spawn-player packet)
     * rather than the spawn-mob path — so it has no mob id (see {@code EntityTypeIds}).
     */
    PLAYER,
    // Mobs. Every one of these exists on all four target editions; the network layer holds the
    // per-edition ids (EntityTypeIds) and 0.14's narrower safe set (Pe014Entities).
    ZOMBIE,
    PIG,
    CHICKEN,
    COW,
    SKELETON,
    CREEPER,
    SHEEP,
    WOLF,
    VILLAGER,
    MOOSHROOM,
    SQUID,
    BAT,
    OCELOT,
    SNOW_GOLEM,
    SPIDER,
    CAVE_SPIDER,
    SILVERFISH,
    ENDERMAN,
    SLIME,
    ZOMBIE_PIGMAN,
    GHAST,
    MAGMA_CUBE,
    BLAZE,
    /**
     * A dropped-item entity — the one type whose body <em>is</em> an item or a block, rendered as a small
     * floating model. It is the decoration primitive: unlike a real block it can sit at a fractional
     * position, hang in mid-air, overlap another, and carry a floating label. Like {@link #PLAYER} it has
     * no mob id; every edition spawns it with its own dedicated packet (see {@code EntityTypeIds}).
     */
    ITEM,
    /**
     * A falling-block entity — the one type whose body is a <b>full-size</b> block, where {@link #ITEM}
     * renders the same block as a small model. The other decoration primitive, and the fussier one: a
     * client animates this type itself, so it is pinned with whatever "don't move" lever each edition
     * has (JE 1.8 has none — see the handler).
     */
    FALLING_BLOCK,
    /**
     * A line of floating text and nothing else — a body taken away until only its label is left. The
     * same trick a hologram line uses (an invisible marker armor stand on Java, an item entity with no
     * item on Bedrock), but as an ordinary entity a script owns, moves and ticks like the rest. Its
     * text is its {@linkplain com.jedrock.api.entity.PuppetEntity#setNameTag name tag}.
     */
    TEXT;

    /** Whether this type renders as a player avatar (the NPC path) rather than a mob. */
    public boolean isPlayer() {
        return this == PLAYER;
    }

    /** Whether this type renders an item / block rather than a creature (the prop path). */
    public boolean isItem() {
        return this == ITEM;
    }

    /** Whether this type renders a full-size block (the falling-block prop path). */
    public boolean isFallingBlock() {
        return this == FALLING_BLOCK;
    }

    /** Whether this type is a floating line of text (the label path). */
    public boolean isText() {
        return this == TEXT;
    }

    /**
     * Whether this type is a creature spawned through the spawn-mob path — everything that is not a
     * player avatar or a prop. Only these have a per-edition mob id.
     */
    public boolean isMob() {
        return !isPlayer() && !isItem() && !isFallingBlock() && !isText();
    }

    /** Lower-case canonical name (e.g. {@code "zombie"}), for display and command parsing. */
    public String canonicalName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a canonical name (case-insensitive), or {@code null} if it isn't a known type. */
    public static EntityType fromString(String name) {
        if (name == null) {
            return null;
        }
        for (EntityType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
