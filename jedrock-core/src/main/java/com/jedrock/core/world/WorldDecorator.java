package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;

/**
 * Bake-time decoration of the finite world: three deterministic passes — carve simple caves, dig
 * lakes, then plant trees — run once after the terrain + biome bake, with {@code generated == true},
 * so they read and write through the same storage the client will see. Nothing is simulated at
 * runtime; this only shapes the frozen "decoration".
 *
 * <p>Every placement is a pure function of the seed and position (position-hashed, no sequential
 * RNG), so a seed always yields the same world. The one order-sensitive spot is overlapping tree
 * canopies (a leaf is placed only into air); the pass order is fixed, so it stays reproducible.
 * Because the whole finite world is in storage at bake time, features cross chunk borders freely —
 * no populate-ordering dance.
 */
public final class WorldDecorator {

    private WorldDecorator() {}

    // ===== Tunables =====
    private static final int CAVE_FLOOR = 6;            // keep a solid floor below this
    private static final int CAVE_TOP = 52;             // caves stay well under the surface
    private static final double CAVE_SCALE_XZ = 17.0;
    private static final double CAVE_SCALE_Y = 11.0;
    private static final double CAVE_THRESHOLD = 0.80;  // higher ⇒ fewer, thinner caves

    private static final double LAKE_CHANCE = 0.05;     // per chunk
    private static final int SPAWN_KEEP_OUT = 8;        // keep lakes off the spawn column

    // Salts decorrelate the per-feature position hashes.
    private static final int SALT_TREE = 0x2717;
    private static final int SALT_LAKE = 0x51A4;
    private static final int SALT_LAKE_X = 0x77A1;
    private static final int SALT_LAKE_Z = 0x1B9D;
    private static final int SALT_LAKE_R = 0x33C7;

    private static final int GRASS = Blocks.state(Blocks.GRASS, 0);
    private static final int DIRT = Blocks.state(Blocks.DIRT, 0);
    private static final int WATER = Blocks.state(Blocks.WATER, 0);

    /** Decorate the baked world over the chunk range {@code [loChunk, hiChunk)} on each axis. */
    public static void decorate(CoreWorld world, long seed, int loChunk, int hiChunk) {
        int loBlock = loChunk << 4;
        int hiBlock = hiChunk << 4; // exclusive
        carveCaves(world, seed, loBlock, hiBlock);
        digLakes(world, seed, loChunk, hiChunk, loBlock, hiBlock);
        plantTrees(world, seed, loBlock, hiBlock);
    }

    // ===== Caves: a 3D-noise "cheese" carve, below the surface. =====

    private static void carveCaves(CoreWorld world, long seed, int loBlock, int hiBlock) {
        // Runs first, so underground is still solid terrain everywhere — no need to read each cell
        // before carving; just carve where the noise says so (setBlockId(AIR) clears it).
        for (int x = loBlock; x < hiBlock; x++) {
            for (int z = loBlock; z < hiBlock; z++) {
                int top = Math.min(world.surfaceHeight(x, z) - 5, CAVE_TOP);
                for (int y = CAVE_FLOOR; y <= top; y++) {
                    double n = noise3(seed, x / CAVE_SCALE_XZ, y / CAVE_SCALE_Y, z / CAVE_SCALE_XZ);
                    if (n > CAVE_THRESHOLD) {
                        world.setBlockId(x, y, z, Blocks.AIR);
                    }
                }
            }
        }
    }

    // ===== Lakes: shallow ponds at the surface, one candidate per chunk. =====

    private static void digLakes(CoreWorld world, long seed, int loChunk, int hiChunk,
                                 int loBlock, int hiBlock) {
        for (int cx = loChunk; cx < hiChunk; cx++) {
            for (int cz = loChunk; cz < hiChunk; cz++) {
                if (unit(hash(seed, cx, cz, SALT_LAKE)) >= LAKE_CHANCE) {
                    continue;
                }
                int centerX = (cx << 4) + (int) Math.floorMod(hash(seed, cx, cz, SALT_LAKE_X), 16);
                int centerZ = (cz << 4) + (int) Math.floorMod(hash(seed, cx, cz, SALT_LAKE_Z), 16);
                if (Math.abs(centerX) <= SPAWN_KEEP_OUT && Math.abs(centerZ) <= SPAWN_KEEP_OUT) {
                    continue; // don't drown spawn
                }
                int r = 3 + (int) Math.floorMod(hash(seed, cx, cz, SALT_LAKE_R), 3); // 3..5
                int waterY = world.surfaceHeight(centerX, centerZ) - 1;
                if (waterY < 2) {
                    continue;
                }
                digLake(world, centerX, centerZ, r, waterY, loBlock, hiBlock);
            }
        }
    }

    private static void digLake(CoreWorld world, int centerX, int centerZ, int r, int waterY,
                                int loBlock, int hiBlock) {
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                int x = centerX + dx, z = centerZ + dz;
                if (x < loBlock || x >= hiBlock || z < loBlock || z >= hiBlock) {
                    continue; // keep the pond inside the finite world
                }
                int surface = world.surfaceHeight(x, z);
                for (int y = waterY + 1; y <= surface; y++) {
                    world.setBlockId(x, y, z, Blocks.AIR); // remove the mound above the waterline
                }
                world.setBlockId(x, waterY, z, WATER);
                world.setBlockId(x, waterY - 1, z, WATER);
            }
        }
    }

    // ===== Trees: per-column, biome-weighted density. =====

    private static void plantTrees(CoreWorld world, long seed, int loBlock, int hiBlock) {
        for (int x = loBlock; x < hiBlock; x++) {
            for (int z = loBlock; z < hiBlock; z++) {
                if (Math.abs(x) <= SPAWN_KEEP_OUT && Math.abs(z) <= SPAWN_KEEP_OUT) {
                    continue; // keep the spawn area clear so a player never spawns inside a trunk
                }
                int biome = world.getBiome(x, z);
                double density = treeDensity(biome);
                if (density <= 0 || unit(hash(seed, x, z, SALT_TREE)) >= density) {
                    continue;
                }
                int surface = world.surfaceHeight(x, z);
                // Only on a grass surface a lake/cave didn't turn to water or air, with canopy headroom.
                if (surface + 8 > 255 || world.getBlockId(x, surface, z) != GRASS) {
                    continue;
                }
                plantTree(world, x, surface, z, biome, hash(seed, x, z, SALT_TREE));
            }
        }
    }

    /** Per-column tree probability by biome (0 ⇒ never). All four biomes are grass-surfaced. */
    private static double treeDensity(int biomeId) {
        return switch (biomeId) {
            case 4 -> 0.035;   // forest — dense
            case 5 -> 0.022;   // taiga
            case 35 -> 0.006;  // savanna — sparse
            case 1 -> 0.004;   // plains — sparse
            default -> 0.0;
        };
    }

    private static void plantTree(CoreWorld world, int x, int surface, int z, int biomeId, long h) {
        boolean spruce = biomeId == 5; // taiga
        int logState = Blocks.state(Blocks.LOG, spruce ? 1 : 0);
        int leafState = Blocks.state(Blocks.LEAVES, spruce ? 1 : 0);
        int height = 4 + (int) Math.floorMod(h >>> 40, 3); // 4..6 logs

        world.setBlockId(x, surface, z, DIRT); // dirt foot under the trunk
        int trunkTop = surface + height;
        for (int y = surface + 1; y <= trunkTop; y++) {
            world.setBlockId(x, y, z, logState);
        }
        // Canopy: two wide leaf discs around the top of the trunk, two narrow ones above it.
        leafDisc(world, x, z, trunkTop - 2, 2, leafState);
        leafDisc(world, x, z, trunkTop - 1, 2, leafState);
        leafDisc(world, x, z, trunkTop, 1, leafState);
        leafDisc(world, x, z, trunkTop + 1, 1, leafState);
    }

    /** A square leaf layer of radius {@code r} with trimmed corners; only fills air (keeps trunks). */
    private static void leafDisc(CoreWorld world, int cx, int cz, int y, int r, int leafState) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r) {
                    continue; // trim corners → rounded canopy
                }
                if (world.getBlockId(cx + dx, y, cz + dz) == Blocks.AIR) {
                    world.setBlockId(cx + dx, y, cz + dz, leafState);
                }
            }
        }
    }

    // ===== Deterministic hashing / noise (position-based, order-independent). =====

    private static long hash(long seed, int a, int b, int salt) {
        long h = seed + salt * 0x9E3779B97F4A7C15L;
        h ^= (long) a * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h ^= (long) b * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        h *= 0x2545F4914F6CDD1DL;
        return h;
    }

    /** A hash mapped to [0, 1). */
    private static double unit(long h) {
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static double noise3(long seed, double x, double y, double z) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);

        double c000 = lattice(seed, x0, y0, z0), c100 = lattice(seed, x0 + 1, y0, z0);
        double c010 = lattice(seed, x0, y0 + 1, z0), c110 = lattice(seed, x0 + 1, y0 + 1, z0);
        double c001 = lattice(seed, x0, y0, z0 + 1), c101 = lattice(seed, x0 + 1, y0, z0 + 1);
        double c011 = lattice(seed, x0, y0 + 1, z0 + 1), c111 = lattice(seed, x0 + 1, y0 + 1, z0 + 1);

        double x00 = lerp(c000, c100, fx), x10 = lerp(c010, c110, fx);
        double x01 = lerp(c001, c101, fx), x11 = lerp(c011, c111, fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static double lattice(long seed, int x, int y, int z) {
        long h = seed;
        h = h * 6364136223846793005L + x * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h = h * 0xBF58476D1CE4E5B9L + y * 0x94D049BB133111EBL;
        h ^= (h >>> 32);
        h = h * 0xD6E8FEB86659FD93L + z * 0xF1357AEA2E62A9C5L;
        h ^= (h >>> 32);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
