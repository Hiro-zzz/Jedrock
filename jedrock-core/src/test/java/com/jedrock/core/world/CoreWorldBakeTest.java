package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1: baking freezes the finite world into storage and switches serving off the generator. */
class CoreWorldBakeTest {

    private static final long SEED = 42L;
    // A block well outside a 4×4-chunk bake (chunks [-2..1] → blocks [-32..31]).
    private static final int OUT_X = 160, OUT_Z = 160;

    @Test
    void bakeFreezesTerrainAndBoundsTheWorld() {
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        int surface = world.surfaceHeight(0, 0);

        assertFalse(world.isGenerated(), "not generated before bake");
        world.bake(4, false); // bare terrain — no decoration over the asserted column

        assertTrue(world.isGenerated(), "generated after bake");
        assertTrue(world.isDirty(), "bake marks the world dirty (needs an initial save)");
        assertTrue(world.loadedSections() > 0, "sections were baked");

        // Terrain is now frozen in storage, identical to what the generator produced.
        assertEquals(Blocks.GRASS, Blocks.idOf(world.getBlockId(0, surface, 0)), "grass on top");
        assertEquals(Blocks.DIRT, Blocks.idOf(world.getBlockId(0, surface - 1, 0)), "dirt below");
        assertEquals(Blocks.STONE, Blocks.idOf(world.getBlockId(0, 0, 0)), "stone deep");
        assertEquals(Blocks.AIR, world.getBlockId(0, surface + 1, 0), "air above");

        // The world is finite: outside the baked region there is no terrain fall-through — just air.
        assertEquals(Blocks.AIR, world.getBlockId(OUT_X, 60, OUT_Z), "outside the bake is void");
    }

    @Test
    void editsOnABakedWorldStoreRealAir() {
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        int surface = world.surfaceHeight(0, 0);
        world.bake(4, false);

        // Breaking a baked block stores genuine air (no REMOVED sentinel) and stays gone.
        world.setBlockId(0, surface, 0, Blocks.AIR);
        assertEquals(Blocks.AIR, world.getBlockId(0, surface, 0), "broken baked block is air");
        assertEquals(Blocks.DIRT, Blocks.idOf(world.getBlockId(0, surface - 1, 0)), "layer below intact");

        // Placing outside the baked region still works (storage grows on demand).
        int placed = Blocks.state(Blocks.STONE, 0);
        world.setBlockId(OUT_X, 60, OUT_Z, placed);
        assertEquals(placed, world.getBlockId(OUT_X, 60, OUT_Z), "placed block outside bake persists");
    }

    @Test
    void bakeMigratesPreBakeOverlayEdits() {
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        int surface = world.surfaceHeight(0, 0);

        // Edits made while still in overlay mode (generated == false).
        int redWool = Blocks.state(Blocks.WOOL, 14);
        world.setBlockId(0, surface + 5, 0, redWool); // place a floating block
        world.setBlockId(0, surface, 0, Blocks.AIR);  // break a natural surface block

        world.bake(4, false);

        assertTrue(world.isGenerated());
        assertEquals(redWool, world.getBlockId(0, surface + 5, 0), "placed block survived the bake");
        assertEquals(Blocks.AIR, world.getBlockId(0, surface, 0), "broken block stayed broken");
        assertEquals(Blocks.DIRT, Blocks.idOf(world.getBlockId(0, surface - 1, 0)), "terrain below baked in");
    }

    @Test
    void bakeIsIdempotent() {
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        world.bake(4);
        int sections = world.loadedSections();
        world.bake(4); // second call must not double-bake or change anything
        assertEquals(sections, world.loadedSections(), "re-baking is a no-op");
    }

    @Test
    void bakedWorldSurvivesSaveLoadAndServesFromStorage(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        int surface = world.surfaceHeight(0, 0);
        world.bake(4, false);
        world.save(file);
        assertFalse(world.isDirty(), "save clears the dirty flag");

        CoreWorld reloaded = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        reloaded.load(file);

        assertTrue(reloaded.isGenerated(), "loaded world is already generated");
        assertEquals(Blocks.GRASS, Blocks.idOf(reloaded.getBlockId(0, surface, 0)), "baked grass restored");
        assertEquals(world.getBiome(0, 0), reloaded.getBiome(0, 0), "baked biome restored");
        // Generated flag means it serves storage, not terrain — outside the bake is still void.
        assertEquals(Blocks.AIR, reloaded.getBlockId(OUT_X, 60, OUT_Z), "still finite after reload");
    }

    @Test
    void bakeFreezesBiomes() {
        CoreWorld world = new CoreWorld("bake", Dimension.OVERWORLD, SEED);
        int generatorBiome = world.getBiome(0, 0); // pre-bake: from the generator
        world.bake(4);

        // After bake the column reads the frozen map, identical to the generator's answer.
        assertEquals(generatorBiome, world.getBiome(0, 0), "baked biome matches the generator");

        // fillBiomes agrees with getBiome per column.
        byte[] out = new byte[256];
        world.fillBiomes(0, 0, out);
        assertEquals(world.getBiome(0, 0) & 0xFF, out[0] & 0xFF, "fillBiomes matches getBiome");

        // Outside the baked region the biome is the default (plains).
        assertEquals(1, world.getBiome(OUT_X, OUT_Z), "unbaked column is default plains");
    }
}
