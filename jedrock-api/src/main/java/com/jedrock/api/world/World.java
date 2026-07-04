package com.jedrock.api.world;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * World abstraction. Extremely lightweight view of a dimension/level.
 */
public interface World {

    String getName();

    UUID getUniqueId();

    Dimension getDimension();

    /**
     * Players currently in this world. Should be cheap to obtain.
     */
    Collection<Player> getPlayers();

    /**
     * Entities in world (living + non-living).
     */
    Collection<Entity> getEntities();

    /**
     * Basic block access - implementations may use lazy chunk loading.
     */
    BlockState getBlockAt(int x, int y, int z);

    void setBlockAt(int x, int y, int z, BlockState state);

    /**
     * Fast, allocation-free block access using canonical block ids (0 = air).
     * Protocol layers map these ids to their own palette when serializing chunks,
     * so Java and Bedrock render the same world.
     */
    int getBlockId(int x, int y, int z);

    void setBlockId(int x, int y, int z, int blockId);

    /**
     * Bulk-read one 16³ section into a caller-provided array — the chunk-serialization hot path,
     * built to avoid the per-block overhead (virtual call, height-cache boxing, one map lookup each)
     * of calling {@link #getBlockId} 4096 times.
     *
     * <p>{@code out} must have length 4096; on return {@code out[(y << 8) | (z << 4) | x]} holds the
     * canonical block id at section-local {@code (x, y, z)} ∈ [0, 16). Returns {@code true} if any
     * block is non-air, so an all-air section can be skipped by the caller.
     *
     * <p>The default implementation simply loops {@link #getBlockId}; {@link com.jedrock.api.world}
     * implementations backed by real storage should override it with a single section fetch and one
     * height evaluation per column.
     */
    default boolean fillSection(int chunkX, int sectionY, int chunkZ, short[] out) {
        int baseX = chunkX << 4, baseY = sectionY << 4, baseZ = chunkZ << 4;
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = getBlockId(baseX + x, baseY + y, baseZ + z);
                    out[(y << 8) | (z << 4) | x] = (short) id;
                    if (id != Blocks.AIR) {
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /**
     * Spawn location for this world.
     */
    Location getSpawnLocation();
}
