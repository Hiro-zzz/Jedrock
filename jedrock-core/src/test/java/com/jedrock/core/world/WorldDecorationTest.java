package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bake decoration passes (caves / lakes / trees) run, produce their features, and are deterministic. */
class WorldDecorationTest {

    @Test
    void bakeProducesTreesLakesAndCaves() {
        CoreWorld world = new CoreWorld("deco", Dimension.OVERWORLD, 0x5EED1EAFL);
        world.bake(16); // 16×16 chunks, decorated

        int logs = 0, leaves = 0, water = 0, caveAir = 0;
        for (int x = -128; x < 128; x++) {
            for (int z = -128; z < 128; z++) {
                int surface = world.surfaceHeight(x, z);
                for (int y = 6; y <= surface + 8 && y <= 255; y++) {
                    int id = Blocks.idOf(world.getBlockId(x, y, z));
                    if (id == Blocks.LOG) logs++;
                    else if (id == Blocks.LEAVES) leaves++;
                    else if (id == Blocks.WATER) water++;
                    else if (id == Blocks.AIR && y <= surface - 6) caveAir++;
                }
            }
        }
        assertTrue(logs > 0, "trees were planted (logs=" + logs + ")");
        assertTrue(leaves > 0, "trees have canopies (leaves=" + leaves + ")");
        assertTrue(water > 0, "lakes were dug (water=" + water + ")");
        assertTrue(caveAir > 0, "caves were carved (caveAir=" + caveAir + ")");
    }

    @Test
    void decorationIsDeterministic() {
        CoreWorld a = new CoreWorld("a", Dimension.OVERWORLD, 777L);
        CoreWorld b = new CoreWorld("b", Dimension.OVERWORLD, 777L);
        a.bake(8);
        b.bake(8);
        for (int x = -64; x < 64; x += 3) {
            for (int z = -64; z < 64; z += 3) {
                int surface = a.surfaceHeight(x, z);
                for (int y = 6; y <= surface + 8; y += 2) {
                    assertEquals(a.getBlockId(x, y, z), b.getBlockId(x, y, z),
                            "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void spawnAreaIsKeptClear() {
        // The keep-out zone means spawn is neither drowned by a lake nor blocked by a tree trunk.
        CoreWorld world = new CoreWorld("spawn", Dimension.OVERWORLD, 0x5EED1EAFL);
        world.bake(8);
        int surface = world.surfaceHeight(0, 0);
        assertNotEquals(Blocks.WATER, Blocks.idOf(world.getBlockId(0, surface, 0)), "spawn surface is not a lake");
        assertEquals(Blocks.AIR, world.getBlockId(0, surface + 1, 0), "player spawns in open air, not a trunk");
        assertEquals(Blocks.AIR, world.getBlockId(0, surface + 2, 0), "headroom above spawn is clear");
    }
}
