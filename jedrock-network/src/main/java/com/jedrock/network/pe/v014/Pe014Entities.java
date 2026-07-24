package com.jedrock.network.pe.v014;

import com.jedrock.api.entity.EntityType;

import java.util.EnumSet;
import java.util.Set;

/**
 * The mobs MCPE <b>0.14</b> (protocol 45) is known to have — {@link Pe014Blocks}' counterpart for
 * entities. The canonical {@link EntityType} set spans everything the newer editions render, but 0.14
 * is a 2016 client that predates several of them, and an id it doesn't know is at best ignored and at
 * worst a crash (which is exactly how its block palette behaves).
 *
 * <p>So the 0.14 session spawns only what is listed here; anything else is simply not shown to those
 * players, the same graceful degradation the rest of the 0.14 path uses (unknown blocks become air,
 * sounds it predates fall back to the nearest id). A mob missing for one edition beats a crashed
 * client, and beats silently substituting a different creature.
 *
 * <p>Deliberately conservative: this list holds the mobs present in Pocket Edition well before 0.14 —
 * the originals plus the 0.12 Nether update. <b>Grow it only against a real 0.14 client</b>, the same
 * rule the block palette carries.
 */
public final class Pe014Entities {

    private Pe014Entities() {}

    private static final Set<EntityType> SUPPORTED = EnumSet.of(
            // The originals
            EntityType.CHICKEN, EntityType.COW, EntityType.PIG, EntityType.SHEEP,
            EntityType.WOLF, EntityType.MOOSHROOM, EntityType.SQUID, EntityType.OCELOT,
            EntityType.VILLAGER,
            EntityType.ZOMBIE, EntityType.CREEPER, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.ENDERMAN, EntityType.SLIME,
            // The 0.12 Nether update
            EntityType.ZOMBIE_PIGMAN, EntityType.GHAST, EntityType.MAGMA_CUBE, EntityType.BLAZE);

    /** Whether a 0.14 client can be shown this mob. Props and player avatars take their own paths. */
    public static boolean supports(EntityType type) {
        return SUPPORTED.contains(type);
    }
}
