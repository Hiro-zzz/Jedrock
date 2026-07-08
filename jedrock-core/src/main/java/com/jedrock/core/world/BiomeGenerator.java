package com.jedrock.core.world;

/**
 * Deterministic biome assignment — the biome-map counterpart to {@link TerrainGenerator}. Two broad,
 * low-frequency value-noise fields (read as "temperature" and "humidity") select one of the four
 * {@link Biome}s, giving large blobby regions rather than per-block scatter. Same seed ⇒ same biome
 * map, computed on demand and stored nowhere (until baked into {@link BiomeStorage}).
 *
 * <p>The noise is the same value-noise TerrainGenerator uses, kept separate here so height and biome
 * stay independent; the two "temperature"/"humidity" fields come from the seed and a derived seed.
 */
public final class BiomeGenerator {

    private final long tempSeed;
    private final long humidSeed;
    private final double scale; // blocks per biome-noise cell (large ⇒ broad biome regions)

    public BiomeGenerator(long seed) {
        this(seed, 220.0);
    }

    public BiomeGenerator(long seed, double scale) {
        this.tempSeed = seed;
        this.humidSeed = seed ^ 0x9E3779B97F4A7C15L;
        this.scale = scale;
    }

    /** The biome at a column — a pure function of {@code (x, z)} and the seed. */
    public Biome biomeAt(int x, int z) {
        double temperature = noise(tempSeed, x / scale, z / scale);
        double humidity = noise(humidSeed, x / scale, z / scale);
        if (temperature < 0.5) {
            return humidity < 0.5 ? Biome.TAIGA : Biome.FOREST;   // cold: dry taiga / wet forest
        }
        return humidity < 0.5 ? Biome.SAVANNA : Biome.PLAINS;     // warm: dry savanna / wet plains
    }

    // ===== Value noise (smooth, deterministic, allocation-free); seed-parameterized. =====

    private double noise(long seed, double x, double z) {
        int x0 = fastFloor(x);
        int z0 = fastFloor(z);
        double fx = fade(x - x0);
        double fz = fade(z - z0);

        double n00 = lattice(seed, x0, z0);
        double n10 = lattice(seed, x0 + 1, z0);
        double n01 = lattice(seed, x0, z0 + 1);
        double n11 = lattice(seed, x0 + 1, z0 + 1);

        return lerp(lerp(n00, n10, fx), lerp(n01, n11, fx), fz);
    }

    private static double lattice(long seed, int x, int z) {
        long h = seed;
        h = h * 6364136223846793005L + (x * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 29);
        h = h * 0xBF58476D1CE4E5B9L + (z * 0x94D049BB133111EBL);
        h ^= (h >>> 32);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
