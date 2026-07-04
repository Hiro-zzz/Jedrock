package com.jedrock.core.world;

/**
 * Deterministic procedural terrain — the single source of truth both editions serialize from.
 *
 * <p>True to the "world is an illusion" philosophy, this stores nothing: the surface height is a
 * pure function of {@code (x, z)} and a seed, computed on demand with a couple of value-noise
 * octaves. Same seed ⇒ same world, forever, without a heightmap in memory.
 *
 * <p>The generator only decides <em>shape</em> (surface height); {@link CoreWorld} turns a height
 * into blocks (grass / dirt / stone). Because the shape is shared, a Java client and a Bedrock
 * client collide against exactly the same ground — and collision itself is the client's job.
 */
public final class TerrainGenerator {

    private final long seed;
    private final int baseHeight;
    private final int amplitude;
    private final double scale; // blocks per primary noise cell (larger ⇒ broader hills)

    public TerrainGenerator(long seed) {
        this(seed, 62, 8, 48.0);
    }

    public TerrainGenerator(long seed, int baseHeight, int amplitude, double scale) {
        this.seed = seed;
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.scale = scale;
    }

    /**
     * @return the y of the topmost solid block (the grass surface) at this column. Rolling hills
     *         within {@code [baseHeight - amplitude, baseHeight + amplitude]}.
     */
    public int surfaceHeight(int x, int z) {
        // Two octaves: broad hills + finer detail, combined into [0, 1].
        double n = 0.65 * noise(x / scale, z / scale)
                 + 0.35 * noise(x / (scale * 0.5), z / (scale * 0.5));
        return baseHeight + (int) Math.round((n - 0.5) * 2.0 * amplitude);
    }

    // ===== Value noise (smooth, deterministic, allocation-free) =====

    private double noise(double x, double z) {
        int x0 = fastFloor(x);
        int z0 = fastFloor(z);
        double fx = fade(x - x0);
        double fz = fade(z - z0);

        double n00 = lattice(x0, z0);
        double n10 = lattice(x0 + 1, z0);
        double n01 = lattice(x0, z0 + 1);
        double n11 = lattice(x0 + 1, z0 + 1);

        return lerp(lerp(n00, n10, fx), lerp(n01, n11, fx), fz);
    }

    /** Hash a lattice point to a stable pseudo-random value in [0, 1). */
    private double lattice(int x, int z) {
        long h = seed;
        h = h * 6364136223846793005L + (x * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 29);
        h = h * 0xBF58476D1CE4E5B9L + (z * 0x94D049BB133111EBL);
        h ^= (h >>> 32);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t); // smoothstep
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
