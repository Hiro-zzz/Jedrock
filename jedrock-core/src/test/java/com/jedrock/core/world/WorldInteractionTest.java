package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world-interaction API: programmatic edits land in storage, notify the change listener (the
 * server's broadcast hook), respect the finite bounds, and {@code fill} skips unchanged cells.
 */
class WorldInteractionTest {

    private final CoreWorld world = new CoreWorld("interact", Dimension.OVERWORLD, 1L);

    /** One recorded listener callback, packed for easy assertion. */
    private record Change(int x, int y, int z, int state) {}

    private List<Change> record() {
        List<Change> changes = new ArrayList<>();
        world.setChangeListener((x, y, z, state) -> changes.add(new Change(x, y, z, state)));
        return changes;
    }

    @Test
    void setBlockWritesAndNotifies() {
        List<Change> changes = record();
        world.setBlock(1, 64, 2, Blocks.WOOL, 14); // red wool

        assertEquals(Blocks.state(Blocks.WOOL, 14), world.getBlockId(1, 64, 2));
        assertEquals(List.of(new Change(1, 64, 2, Blocks.state(Blocks.WOOL, 14))), changes);
    }

    @Test
    void breakingNotifiesAirNotTheOverlaySentinel() {
        // Pre-bake, air is stored as the REMOVED sentinel — the listener must still see plain air.
        List<Change> changes = record();
        world.setBlockId(3, 60, 3, Blocks.AIR);

        assertEquals(Blocks.AIR, world.getBlockId(3, 60, 3));
        assertEquals(List.of(new Change(3, 60, 3, Blocks.AIR)), changes);
    }

    @Test
    void writesOutsideBoundsAreDroppedSilently() {
        List<Change> changes = record();
        int sections = world.loadedSections();
        world.setBlockId(world.maxBound(), 64, 0, Blocks.state(Blocks.STONE, 0)); // past the edge
        world.setBlockId(0, -1, 0, Blocks.state(Blocks.STONE, 0));                // below the world
        world.setBlockId(0, 256, 0, Blocks.state(Blocks.STONE, 0));               // above the world

        assertEquals(sections, world.loadedSections(), "no storage allocated past the edge");
        assertTrue(changes.isEmpty(), "a dropped write must not be broadcast");
        assertTrue(!world.isDirty(), "a dropped write must not dirty the world");
    }

    @Test
    void fillFillsTheBoxFromAnyCornerOrder() {
        int y = 200; // high above the terrain, so the box starts all-air
        int changed = world.fill(4, y + 2, 4, 2, y, 2, Blocks.state(Blocks.GLASS, 0));

        assertEquals(27, changed, "3×3×3 box");
        assertEquals(Blocks.state(Blocks.GLASS, 0), world.getBlockId(2, y, 2));
        assertEquals(Blocks.state(Blocks.GLASS, 0), world.getBlockId(4, y + 2, 4));
        assertEquals(Blocks.AIR, world.getBlockId(5, y, 2), "outside the box untouched");
    }

    @Test
    void refillSkipsUnchangedCells() {
        int y = 210;
        world.fill(0, y, 0, 2, y, 2, Blocks.state(Blocks.PLANKS, 0));

        List<Change> changes = record();
        int changed = world.fill(0, y, 0, 2, y, 2, Blocks.state(Blocks.PLANKS, 0));

        assertEquals(0, changed, "identical refill changes nothing");
        assertTrue(changes.isEmpty(), "…and broadcasts nothing");
    }
}
