package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;

/**
 * Bake-time decoration for the nether — the counterpart to {@link WorldDecorator}, run once over the
 * frozen cavern with {@code generated == true}. Four passes, in a fixed order: a solid spawn platform,
 * glowstone hanging from the roof, soul sand along the lava shore, and ore embedded in the floor.
 *
 * <p>Like the overworld's, every placement is a pure function of the seed and position — no sequential
 * RNG — so a seed always yields the same nether, and features cross chunk borders freely because the
 * whole finite world is in storage by the time this runs.
 */
public final class NetherDecorator {

    private NetherDecorator() {}

    // ===== Tunables =====
    /** Half-width of the netherrack pad laid at spawn, so the join point is never over lava. */
    private static final int SPAWN_PAD_RADIUS = 3;
    /** Headroom cleared above the spawn pad. */
    private static final int SPAWN_HEADROOM = 5;

    private static final double GLOWSTONE_CHANCE = 0.34;   // per chunk
    private static final double SOUL_SAND_CHANCE = 0.10;   // per shore column
    private static final int SOUL_SAND_SHORE = 3;          // how far above the sea still counts as shore
    private static final double QUARTZ_CHANCE = 0.020;     // per column
    private static final double GRAVEL_CHANCE = 0.012;     // per column

    // Salts decorrelate the per-feature position hashes (and from the overworld's own salts).
    private static final int SALT_GLOW = 0x6C17;
    private static final int SALT_GLOW_X = 0x2E93;
    private static final int SALT_GLOW_Z = 0x5B31;
    private static final int SALT_SOUL = 0x71D5;
    private static final int SALT_QUARTZ = 0x3F0B;
    private static final int SALT_GRAVEL = 0x19C7;

    private static final int NETHERRACK = Blocks.state(Blocks.NETHERRACK, 0);
    private static final int GLOWSTONE = Blocks.state(Blocks.GLOWSTONE, 0);
    private static final int SOUL_SAND = Blocks.state(Blocks.SOUL_SAND, 0);
    private static final int QUARTZ_ORE = Blocks.state(Blocks.QUARTZ_ORE, 0);
    private static final int GRAVEL = Blocks.state(Blocks.GRAVEL, 0);

    /** Decorate the baked nether over the chunk range {@code [loChunk, hiChunk)} on each axis. */
    public static void decorate(CoreWorld world, NetherGenerator gen, long seed, int loChunk, int hiChunk) {
        int loBlock = loChunk << 4;
        int hiBlock = hiChunk << 4; // exclusive
        buildSpawnPlatform(world, gen, loBlock, hiBlock);
        hangGlowstone(world, gen, seed, loChunk, hiChunk);
        scatterFloor(world, gen, seed, loBlock, hiBlock);
    }

    // ===== The spawn platform: the one placement that is not optional. =====

    /**
     * Lay a netherrack pad at the origin and clear the air above it. The floor there may well be under
     * the lava sea, and {@link NetherGenerator#spawnHeight} already promises a player stands one block
     * above {@link NetherGenerator#platformY} — this is what makes that promise true.
     */
    private static void buildSpawnPlatform(CoreWorld world, NetherGenerator gen, int loBlock, int hiBlock) {
        int padY = gen.platformY(0, 0);
        for (int x = -SPAWN_PAD_RADIUS; x <= SPAWN_PAD_RADIUS; x++) {
            for (int z = -SPAWN_PAD_RADIUS; z <= SPAWN_PAD_RADIUS; z++) {
                if (x < loBlock || x >= hiBlock || z < loBlock || z >= hiBlock) {
                    continue; // a world too small to contain its own spawn pad gets what fits
                }
                world.setBlockId(x, padY, z, NETHERRACK);
                for (int y = padY + 1; y <= padY + SPAWN_HEADROOM && y < NetherGenerator.MAX_Y; y++) {
                    world.setBlockId(x, y, z, Blocks.AIR);
                }
            }
        }
    }

    // ===== Glowstone: clusters on the underside of the roof — the nether's only light. =====

    private static void hangGlowstone(CoreWorld world, NetherGenerator gen, long seed,
                                      int loChunk, int hiChunk) {
        for (int cx = loChunk; cx < hiChunk; cx++) {
            for (int cz = loChunk; cz < hiChunk; cz++) {
                if (WorldDecorator.unit(WorldDecorator.hash(seed, cx, cz, SALT_GLOW)) >= GLOWSTONE_CHANCE) {
                    continue;
                }
                int x = (cx << 4) + (int) Math.floorMod(WorldDecorator.hash(seed, cx, cz, SALT_GLOW_X), 16);
                int z = (cz << 4) + (int) Math.floorMod(WorldDecorator.hash(seed, cx, cz, SALT_GLOW_Z), 16);
                int roof = gen.ceilingHeight(x, z);
                int y = roof - 1; // the first open cell under the netherrack roof
                if (y <= NetherGenerator.LAVA_SEA || world.getBlockId(x, y, z) != Blocks.AIR) {
                    continue;
                }
                blob(world, x, y, z);
            }
        }
    }

    /** A small glowstone clump: the anchor cell, its four neighbours, and one cell hanging below. */
    private static void blob(CoreWorld world, int x, int y, int z) {
        world.setBlockId(x, y, z, GLOWSTONE);
        fillAir(world, x + 1, y, z);
        fillAir(world, x - 1, y, z);
        fillAir(world, x, y, z + 1);
        fillAir(world, x, y, z - 1);
        fillAir(world, x, y - 1, z);
    }

    private static void fillAir(CoreWorld world, int x, int y, int z) {
        if (world.getBlockId(x, y, z) == Blocks.AIR) {
            world.setBlockId(x, y, z, GLOWSTONE);
        }
    }

    // ===== The floor: soul sand along the shore, quartz and gravel in the netherrack. =====

    private static void scatterFloor(CoreWorld world, NetherGenerator gen, long seed,
                                     int loBlock, int hiBlock) {
        for (int x = loBlock; x < hiBlock; x++) {
            for (int z = loBlock; z < hiBlock; z++) {
                int f = gen.surfaceHeight(x, z);
                if (f <= NetherGenerator.LAVA_SEA) {
                    continue; // under the sea — nothing to dress
                }
                // Soul sand only just above the waterline, which is what makes it read as a shore.
                if (f <= NetherGenerator.LAVA_SEA + SOUL_SAND_SHORE
                        && WorldDecorator.unit(WorldDecorator.hash(seed, x, z, SALT_SOUL)) < SOUL_SAND_CHANCE
                        && world.getBlockId(x, f, z) == NETHERRACK) {
                    world.setBlockId(x, f, z, SOUL_SAND);
                    continue;
                }
                if (WorldDecorator.unit(WorldDecorator.hash(seed, x, z, SALT_GRAVEL)) < GRAVEL_CHANCE
                        && world.getBlockId(x, f, z) == NETHERRACK) {
                    world.setBlockId(x, f, z, GRAVEL);
                    continue;
                }
                if (WorldDecorator.unit(WorldDecorator.hash(seed, x, z, SALT_QUARTZ)) < QUARTZ_CHANCE) {
                    // A couple of cells down, so the ore is in the rock rather than sitting on it.
                    int y = f - 1 - (int) Math.floorMod(WorldDecorator.hash(seed, x, z, SALT_QUARTZ) >>> 40, 3);
                    if (y > 0 && world.getBlockId(x, y, z) == NETHERRACK) {
                        world.setBlockId(x, y, z, QUARTZ_ORE);
                    }
                }
            }
        }
    }
}
