package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;

/**
 * The nether: one enormous cavern between a netherrack floor and a netherrack roof, with a lava sea in
 * its low ground and a bedrock cap top and bottom.
 *
 * <p>It is built the way the overworld is — a height field, evaluated on demand, frozen once by the
 * bake — only with <em>two</em> height fields instead of one. The floor rolls through the lava line, so
 * the same noise that makes hills here makes shores: a column whose floor sits under {@link #LAVA_SEA}
 * is a lava lake, one above it is walkable ground. Nothing is carved and nothing is simulated; the
 * cavern is simply the air left between the two fields.
 *
 * <p>The world is <b>128 tall</b>, which is not a stylistic choice: MCPE 0.14 has no taller world, and
 * a 128-tall nether is the one shape every target edition renders identically.
 */
public final class NetherGenerator implements WorldGenerator {

    /** Highest buildable y — the world is 128 cells tall, y ∈ [0, 127]. */
    public static final int MAX_Y = 127;

    /** Open cavern at or below this y is lava; above it, air. */
    public static final int LAVA_SEA = 31;

    /** Salts that decorrelate the floor and ceiling fields from each other and from any overworld seed. */
    private static final long SALT_FLOOR = 0x4E45544845525F46L;   // "NETHER_F"
    private static final long SALT_CEILING = 0x4E45544845525F43L; // "NETHER_C"

    private static final int BEDROCK = Blocks.state(Blocks.BEDROCK, 0);
    private static final int NETHERRACK = Blocks.state(Blocks.NETHERRACK, 0);
    private static final int LAVA = Blocks.state(Blocks.LAVA, 0);

    /** Legacy biome id for the nether ("Hell"), understood by every target edition. */
    public static final int BIOME_HELL = 8;

    /** Floor: rolls through the lava line, so the sea has shores. */
    private final TerrainGenerator floor;
    /** Ceiling: broader and flatter, well clear of the floor at every column. */
    private final TerrainGenerator ceiling;

    private final long seed;

    public NetherGenerator(long seed) {
        this.seed = seed;
        this.floor = new TerrainGenerator(seed ^ SALT_FLOOR, 32, 10, 40.0);    // 22..42, straddling the sea
        this.ceiling = new TerrainGenerator(seed ^ SALT_CEILING, 90, 10, 56.0); // 80..100
    }

    /** Pack the two heights into one descriptor: floor in the low 16 bits, ceiling in the next 16. */
    @Override
    public long column(int x, int z) {
        long f = floor.surfaceHeight(x, z) & 0xFFFFL;
        long c = ceiling.surfaceHeight(x, z) & 0xFFFFL;
        return f | (c << 16);
    }

    @Override
    public int blockAt(int y, long column) {
        if (y < 0 || y > MAX_Y) {
            return Blocks.AIR;
        }
        // The cap comes first: bedrock is what makes the nether a closed box, floor and roof alike.
        if (y == 0 || y == MAX_Y) {
            return BEDROCK;
        }
        int f = (int) (column & 0xFFFF);
        int c = (int) ((column >>> 16) & 0xFFFF);
        if (y <= f || y >= c) {
            return NETHERRACK;
        }
        return y <= LAVA_SEA ? LAVA : Blocks.AIR;
    }

    @Override
    public int surfaceHeight(int x, int z) {
        return floor.surfaceHeight(x, z);
    }

    /**
     * A player stands on the floor — unless the floor is under the lava sea, in which case they stand on
     * the platform the decorator lays over it. The two agree by construction: both take the floor and
     * lift it clear of the sea, so a spawn point is computed before the bake and is still solid after it.
     */
    @Override
    public int spawnHeight(int x, int z) {
        return platformY(x, z) + 1;
    }

    /** The y of the solid block a spawn platform occupies at this column (its top surface). */
    int platformY(int x, int z) {
        return Math.max(floor.surfaceHeight(x, z), LAVA_SEA + 1);
    }

    /** The y of the lowest netherrack cell of the roof — the decorator hangs glowstone just under it. */
    int ceilingHeight(int x, int z) {
        return ceiling.surfaceHeight(x, z);
    }

    @Override
    public int biomeAt(int x, int z) {
        return BIOME_HELL;
    }

    @Override
    public int maxY() {
        return MAX_Y;
    }

    @Override
    public void decorate(CoreWorld world, long seed, int loChunk, int hiChunk) {
        NetherDecorator.decorate(world, this, seed, loChunk, hiChunk);
    }

    /** The seed this generator was built from — the decorator hashes positions against it. */
    long seed() {
        return seed;
    }
}
