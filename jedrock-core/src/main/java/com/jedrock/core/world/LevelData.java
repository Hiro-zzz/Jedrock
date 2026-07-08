package com.jedrock.core.world;

/**
 * Immutable header metadata for a persisted world — everything the level file records <em>besides</em>
 * the block sections themselves (which live in the {@link BlockStorage} the file also carries).
 *
 * <p>Written uncompressed at the front of the file by {@link LevelIO} so it can be read cheaply
 * without inflating the whole world. {@code generated} marks a world whose one-time bake has already
 * run (see the world-generation roadmap); in the persistence-only Phase 0 it stays {@code false}.
 */
public record LevelData(
        int formatVersion,
        long seed,
        int boundsChunksX,
        int boundsChunksZ,
        boolean generated,
        double spawnX, double spawnY, double spawnZ,
        float spawnYaw, float spawnPitch) {
}
