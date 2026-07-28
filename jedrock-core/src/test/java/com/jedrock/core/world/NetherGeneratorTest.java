package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the nether: a closed 128-tall box, a lava sea in its low ground, and a spawn a
 * player survives. The column packing is asserted directly, since it is the one place where a bit-shift
 * mistake would produce a plausible-looking but wrong world.
 */
class NetherGeneratorTest {

    private static final long SEED = 0x1337BEEFL;

    @Test
    void theBoxIsClosedTopAndBottom() {
        NetherGenerator gen = new NetherGenerator(SEED);
        long column = gen.column(0, 0);
        assertEquals(Blocks.state(Blocks.BEDROCK, 0), gen.blockAt(0, column), "floor is bedrock");
        assertEquals(Blocks.state(Blocks.BEDROCK, 0), gen.blockAt(NetherGenerator.MAX_Y, column),
                "roof is bedrock");
        assertEquals(Blocks.AIR, gen.blockAt(NetherGenerator.MAX_Y + 1, column), "nothing above the roof");
        assertEquals(Blocks.AIR, gen.blockAt(-1, column));
    }

    @Test
    void theColumnPacksFloorAndCeilingIndependently() {
        NetherGenerator gen = new NetherGenerator(SEED);
        // Sample enough columns that the two fields must disagree somewhere if they are really two.
        boolean sawDifferentFloors = false;
        int firstFloor = floorOf(gen, 0, 0);
        for (int x = 0; x < 400; x += 7) {
            if (floorOf(gen, x, x) != firstFloor) {
                sawDifferentFloors = true;
            }
            int floor = floorOf(gen, x, x);
            int ceiling = ceilingOf(gen, x, x);
            assertTrue(floor < ceiling, "the cavern must have headroom at (" + x + ", " + x + ")");
            assertTrue(ceiling < NetherGenerator.MAX_Y, "the roof stays under the bedrock cap");
        }
        assertTrue(sawDifferentFloors, "the floor is a height field, not a constant");
    }

    @Test
    void lowGroundIsALavaSeaAndHighGroundIsWalkable() {
        NetherGenerator gen = new NetherGenerator(SEED);
        // A synthetic column below the sea: everything open under the waterline is lava.
        long low = pack(20, 90);
        assertEquals(Blocks.state(Blocks.LAVA, 0), gen.blockAt(25, low));
        assertEquals(Blocks.state(Blocks.LAVA, 0), gen.blockAt(NetherGenerator.LAVA_SEA, low));
        assertEquals(Blocks.AIR, gen.blockAt(NetherGenerator.LAVA_SEA + 1, low));

        // A column above it: the floor is netherrack and the air starts right on top of it.
        long high = pack(40, 90);
        assertEquals(Blocks.state(Blocks.NETHERRACK, 0), gen.blockAt(40, high));
        assertEquals(Blocks.AIR, gen.blockAt(41, high));
        assertEquals(Blocks.state(Blocks.NETHERRACK, 0), gen.blockAt(90, high), "the roof slab");
    }

    @Test
    void spawnStandsClearOfTheLavaSea() {
        for (long seed : new long[]{1L, 2L, 99L, 0x5EED1EAFL}) {
            NetherGenerator gen = new NetherGenerator(seed);
            assertTrue(gen.spawnHeight(0, 0) > NetherGenerator.LAVA_SEA + 1,
                    "spawn must be above the sea for seed " + seed);
            assertEquals(gen.platformY(0, 0) + 1, gen.spawnHeight(0, 0));
        }
    }

    @Test
    void aNetherWorldIsOneTwentyEightTallAndRefusesWritesAboveItsRoof() {
        CoreWorld world = new CoreWorld("nether", Dimension.NETHER, SEED);
        assertEquals(NetherGenerator.MAX_Y, world.maxY());

        world.setBlockId(0, 200, 0, Blocks.state(Blocks.STONE, 0));
        assertEquals(Blocks.AIR, world.getBlockId(0, 200, 0), "the roof is the top of the world");
    }

    @Test
    void aBakedNetherIsMadeOfNetherrackAndHasASolidSpawn() {
        CoreWorld world = new CoreWorld("nether", Dimension.NETHER, SEED);
        world.bake(4);

        var spawn = world.getSpawnLocation();
        int feet = (int) spawn.y();
        assertNotEquals(Blocks.AIR, world.getBlockId(0, feet - 1, 0), "something solid under the feet");
        assertNotEquals(Blocks.state(Blocks.LAVA, 0), world.getBlockId(0, feet - 1, 0), "and it isn't lava");
        assertEquals(Blocks.AIR, world.getBlockId(0, feet, 0), "with headroom to stand in");

        assertEquals(Blocks.state(Blocks.BEDROCK, 0), world.getBlockId(0, 0, 0));
        assertEquals(Blocks.state(Blocks.BEDROCK, 0), world.getBlockId(0, NetherGenerator.MAX_Y, 0));
        assertEquals(NetherGenerator.BIOME_HELL, world.getBiome(0, 0));
    }

    @Test
    void theEndIsNotAWorldTypeWeGenerate() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldGenerator.forDimension(Dimension.END, SEED));
    }

    private static long pack(int floor, int ceiling) {
        return (floor & 0xFFFFL) | ((ceiling & 0xFFFFL) << 16);
    }

    private static int floorOf(NetherGenerator gen, int x, int z) {
        return (int) (gen.column(x, z) & 0xFFFF);
    }

    private static int ceilingOf(NetherGenerator gen, int x, int z) {
        return (int) ((gen.column(x, z) >>> 16) & 0xFFFF);
    }
}
