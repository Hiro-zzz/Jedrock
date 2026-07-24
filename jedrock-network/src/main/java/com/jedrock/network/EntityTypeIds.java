package com.jedrock.network;

import com.jedrock.api.entity.EntityType;

/**
 * The entity-type "palette" — the entity counterpart of the block palette (the two-headed monster's entity
 * tax). Maps a canonical {@link EntityType} to each edition's numeric entity id:
 *
 * <ul>
 *   <li><b>Java</b>: the classic pre-1.13 SpawnMob type ids, shared by 1.8 and 1.12.2 (flattening to string
 *       ids only arrived in 1.13).</li>
 *   <li><b>Bedrock</b>: the legacy MCPE entity ids, shared by 1.1.5 and 0.14 (anchored on PocketMine-MP
 *       {@code 1.7dev-27}: Zombie {@code NETWORK_ID = 32}).</li>
 * </ul>
 *
 * Kept in one place so the mapping is a single source of truth, verifiable against wiki.vg (Java) and PMMP
 * (Bedrock).
 */
public final class EntityTypeIds {

    private EntityTypeIds() {}

    /** The classic Java Edition numeric mob id (1.8 / 1.12.2 SpawnMob type field). */
    public static int javaId(EntityType type) {
        return switch (type) {
            case CREEPER -> 50;
            case SKELETON -> 51;
            case ZOMBIE -> 54;
            case PIG -> 90;
            case COW -> 92;
            case CHICKEN -> 93;
            case PLAYER, ITEM, FALLING_BLOCK -> throw notAMob(type);
        };
    }

    /**
     * Java Edition's Spawn Object type for an {@link EntityType#ITEM} prop — item stacks are objects, not
     * mobs, so they ride a different packet (1.8 {@code 0x0E} / 1.12.2 {@code 0x00}). Same id in both.
     */
    public static final int JAVA_OBJECT_ITEM_STACK = 2;

    /**
     * Java Edition's Spawn Object type for a falling block. Its object-data int carries the block as
     * {@code id | (meta << 12)} — the packing ViaVersion's 1.13 converter reads back the other way.
     */
    public static final int JAVA_OBJECT_FALLING_BLOCK = 70;

    /** The legacy MCPE entity id of a falling block (PMMP {@code FallingSand}, same at 0.14 and 1.1.5). */
    public static final int BEDROCK_FALLING_BLOCK = 66;

    /** The legacy MCPE entity id (PE 1.1.5 / 0.14 AddEntity type field). */
    public static int bedrockId(EntityType type) {
        return switch (type) {
            case CHICKEN -> 10;
            case COW -> 11;
            case PIG -> 12;
            case ZOMBIE -> 32;
            case CREEPER -> 33;
            case SKELETON -> 34;
            // A dropped item is a real Bedrock entity type, but it spawns through AddItemEntity (which
            // carries the item) rather than AddEntity, so callers never need this id.
            case ITEM -> 64;
            case FALLING_BLOCK -> BEDROCK_FALLING_BLOCK;
            case PLAYER -> throw notAMob(type);
        };
    }

    /** {@link EntityType#PLAYER} renders via the player-avatar path, not spawn-mob, so it has no mob id. */
    private static IllegalArgumentException notAMob(EntityType type) {
        return new IllegalArgumentException(type + " does not render as a mob (it spawns through its own "
                + "packet — the avatar path for a player, the item-entity path for a prop), so it has no "
                + "spawn-mob id");
    }
}
