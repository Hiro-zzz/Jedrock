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
        assertEquals(Blocks.GRASS, world.getBlockId(5, surface, 9), "grass on top");
        assertEquals(Blocks.DIRT, world.getBlockId(5, surface - 1, 9), "dirt just below");
        assertEquals(Blocks.STONE, world.getBlockId(5, surface - 10, 9), "stone deep down");
        assertEquals(Blocks.STONE, world.getBlockId(5, 0, 9), "stone at bedrock level");
    }

    @Test
    void spawnStandsOnTheSurface() {
        Location spawn = world.getSpawnLocation();
        int surface = world.surfaceHeight(0, 0);
        // Feet one block above the grass, so the client lands instead of suffocating.
        assertEquals(surface + 1, spawn.getBlockY());
        assertEquals(Blocks.GRASS, world.getBlockId(0, surface, 0));
        assertEquals(Blocks.AIR, world.getBlockId(0, surface + 1, 0));
    }

    @Test
    void editsOverrideGeneratedTerrain() {
        int surface = world.surfaceHeight(3, 3);
        world.setBlockId(3, surface + 5, 3, Blocks.STONE); // place a floating block
        assertEquals(Blocks.STONE, world.getBlockId(3, surface + 5, 3));
    }
}
