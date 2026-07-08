package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Player edits (placements <em>and</em> breaks of natural blocks) survive a save/load cycle. */
class CoreWorldPersistenceTest {

    private static final long SEED = 12345L;

    @Test
    void editsSurviveReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("level.jdw");

        CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD, SEED);
        int surface = world.surfaceHeight(0, 0);

        // Break a natural surface block, and place a block up in the air.
        assertNotEquals(Blocks.AIR, world.getBlockId(0, surface, 0), "surface is solid before break");
        world.setBlockId(0, surface, 0, Blocks.AIR);
        int placed = Blocks.state(Blocks.STONE, 0);
        world.setBlockId(0, surface + 5, 0, placed);

        world.save(file);

        // Fresh instance, same seed: terrain is reproduced procedurally, edits come from the file.
        CoreWorld reloaded = new CoreWorld("world", Dimension.OVERWORLD, SEED);
        reloaded.load(file);

        assertEquals(Blocks.AIR, reloaded.getBlockId(0, surface, 0),
                "a broken natural block stays broken (REMOVED sentinel persisted)");
        assertEquals(placed, reloaded.getBlockId(0, surface + 5, 0), "placed block persisted");
        // An untouched column is still the same procedural terrain.
        int s5 = reloaded.surfaceHeight(5, 5);
        assertEquals(world.getBlockId(5, s5, 5), reloaded.getBlockId(5, s5, 5), "untouched terrain matches");
    }
}
