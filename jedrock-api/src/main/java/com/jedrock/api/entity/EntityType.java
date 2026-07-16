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
    ZOMBIE,
    PIG,
    CHICKEN,
    COW,
    SKELETON,
    CREEPER;

    /** Whether this type renders as a player avatar (the NPC path) rather than a mob. */
    public boolean isPlayer() {
        return this == PLAYER;
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
