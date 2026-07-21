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
     * Legacy Minecraft biome id (0..255) at a column. Protocol-agnostic: each edition's chunk
     * serializer maps it to its own wire form (a biome-id byte on Java / PE 1.1.5, a grass-tint colour
     * on PE 0.14). Defaults to {@code 1} (plains) so a minimal {@link World} needs no biome data.
     */
    default int getBiome(int x, int z) {
        return 1; // plains
    }

    /**
     * Bulk-read a chunk's 16×16 biome map into {@code out} (length 256), ordered {@code out[(z<<4)|x]}
     * — the chunk-serialization companion to {@link #fillSection}. The default loops {@link #getBiome};
     * storage-backed worlds override it with a single lookup.
     */
    default void fillBiomes(int chunkX, int chunkZ, byte[] out) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[(z << 4) | x] = (byte) getBiome(baseX + x, baseZ + z);
            }
        }
    }

    /**
     * The Y of the highest non-air block in a column, or {@code -1} for an all-air column. The default
     * scans down from the top with {@link #getBlockId}; a storage-backed world overrides it with its
     * surface-height cache. Handy for placing something safely on top of the terrain.
     */
    default int getHighestBlockY(int x, int z) {
        for (int y = 255; y >= 0; y--) {
            if (getBlockId(x, y, z) != Blocks.AIR) {
                return y;
            }
        }
        return -1;
    }

    /**
     * Spawn location for this world.
     */
    Location getSpawnLocation();

    /**
     * Move this world's spawn point. Default is a no-op (a minimal / test world has a fixed spawn); a
     * real world overrides it to store the new point (used for future joins and {@code /spawn}).
     */
    default void setSpawnLocation(Location location) {}
}
