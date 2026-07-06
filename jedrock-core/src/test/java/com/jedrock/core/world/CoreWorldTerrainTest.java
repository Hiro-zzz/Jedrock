package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Terrain generation + layering + spawn placement for the procedural world. */
class CoreWorldTerrainTest {

    private final CoreWorld world = new CoreWorld("gen", Dimension.OVERWORLD, 42L);

    @Test
    void sameSeedReproducesSameHeights() {
        TerrainGenerator a = new TerrainGenerator(42L);
        TerrainGenerator b = new TerrainGenerator(42L);
        for (int x = -40; x <= 40; x += 7) {
            for (int z = -40; z <= 40; z += 7) {
                assertEquals(a.surfaceHeight(x, z), b.surfaceHeight(x, z), "column " + x + "," + z);
            }
        }
    }

    @Test
    void columnIsLayeredGrassDirtStoneThenAir() {
        int surface = world.surfaceHeight(5, 9);

        assertEquals(Blocks.AIR, world.getBlockId(5, surface + 1, 9), "air above the surface");
        assertEquals(Blocks.GRASS, Blocks.idOf(world.getBlockId(5, surface, 9)), "grass on top");
        assertEquals(Blocks.DIRT, Blocks.idOf(world.getBlockId(5, surface - 1, 9)), "dirt just below");
        assertEquals(Blocks.STONE, Blocks.idOf(world.getBlockId(5, surface - 10, 9)), "stone deep down");
        assertEquals(Blocks.STONE, Blocks.idOf(world.getBlockId(5, 0, 9)), "stone at bedrock level");
    }

    @Test
    void spawnStandsOnTheSurface() {
        Location spawn = world.getSpawnLocation();
        int surface = world.surfaceHeight(0, 0);
        // Feet one block above the grass, so the client lands instead of suffocating.
        assertEquals(surface + 1, spawn.getBlockY());
        assertEquals(Blocks.GRASS, Blocks.idOf(world.getBlockId(0, surface, 0)));
        assertEquals(Blocks.AIR, world.getBlockId(0, surface + 1, 0));
    }

    @Test
    void editsOverrideGeneratedTerrain() {
        int surface = world.surfaceHeight(3, 3);
        world.setBlockId(3, surface + 5, 3, Blocks.state(Blocks.STONE, 0)); // place a floating block
        assertEquals(Blocks.state(Blocks.STONE, 0), world.getBlockId(3, surface + 5, 3));
    }

    @Test
    void placedBlockPreservesMetadata() {
        int surface = world.surfaceHeight(4, 4);
        int redWool = Blocks.state(Blocks.WOOL, 14); // wool + meta 14 (red)
        world.setBlockId(4, surface + 2, 4, redWool);

        int stored = world.getBlockId(4, surface + 2, 4);
        assertEquals(redWool, stored, "full state round-trips");
        assertEquals(Blocks.WOOL, Blocks.idOf(stored), "id preserved");
        assertEquals(14, Blocks.metaOf(stored), "meta preserved");
    }

    @Test
    void fillSectionMatchesScalarGetBlockId() {
        // Include edits so the overlay paths (placed block + broken-natural sentinel) are exercised.
        int surface = world.surfaceHeight(1, 1);
        world.setBlockId(1, surface + 20, 1, Blocks.state(Blocks.STONE, 0)); // floating placed block
        world.setBlockId(2, surface, 2, Blocks.AIR);        // break a natural block (REMOVED sentinel)

        short[] out = new short[4096];
        // Sections that straddle the surface (3, 4) plus one holding only an edit (higher up).
        for (int sectionY : new int[]{3, 4, (surface + 20) >> 4}) {
            boolean any = world.fillSection(0, sectionY, 0, out);

            boolean expectedAny = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int expected = world.getBlockId(x, (sectionY << 4) | y, z);
                        assertEquals(expected, out[(y << 8) | (z << 4) | x] & 0xFFFF,
                                "cell " + x + "," + ((sectionY << 4) | y) + "," + z);
                        if (expected != Blocks.AIR) expectedAny = true;
                    }
                }
            }
            assertEquals(expectedAny, any, "non-air flag for section " + sectionY);
        }
    }

    @Test
    void breakingNaturalTerrainSticks() {
        int surface = world.surfaceHeight(2, 2);
        assertEquals(Blocks.GRASS, Blocks.idOf(world.getBlockId(2, surface, 2)), "natural grass surface");

        world.setBlockId(2, surface, 2, Blocks.AIR); // break the natural block

        assertEquals(Blocks.AIR, world.getBlockId(2, surface, 2), "broken natural block stays gone");
        assertEquals(Blocks.DIRT, Blocks.idOf(world.getBlockId(2, surface - 1, 2)), "layer below untouched");
    }
}
