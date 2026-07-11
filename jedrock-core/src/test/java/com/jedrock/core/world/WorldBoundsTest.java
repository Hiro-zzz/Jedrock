package com.jedrock.core.world;

import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The finite world's edge: inclusive min, exclusive max, void beyond (the invisible-wall test). */
class WorldBoundsTest {

    private final CoreWorld world = new CoreWorld("bounds", Dimension.OVERWORLD, 1L);

    @Test
    void boundsMatchThe48ChunkWorld() {
        assertEquals(-384, world.minBound(), "48 chunks centred on origin → min block -384");
        assertEquals(384, world.maxBound(), "exclusive max block 384");
    }

    @Test
    void originAndInteriorAreInside() {
        assertTrue(world.isInsideBounds(0, 0));
        assertTrue(world.isInsideBounds(0.5, 0.5), "spawn column");
        assertTrue(world.isInsideBounds(100, -100));
    }

    @Test
    void edgesAreInclusiveMinExclusiveMax() {
        assertTrue(world.isInsideBounds(world.minBound(), world.minBound()), "min corner is inside");
        assertTrue(world.isInsideBounds(383.999, 383.999), "just inside the far edge");
        assertFalse(world.isInsideBounds(world.maxBound(), 0), "max is exclusive (x)");
        assertFalse(world.isInsideBounds(0, world.maxBound()), "max is exclusive (z)");
        assertFalse(world.isInsideBounds(world.minBound() - 0.001, 0), "just past the near edge");
    }

    @Test
    void farOutsideIsVoid() {
        assertFalse(world.isInsideBounds(10_000, 0));
        assertFalse(world.isInsideBounds(0, -10_000));
    }

    @Test
    void voidStartsBelowTheFloorWithGrace() {
        assertFalse(world.isInVoid(0), "standing on the world floor is not the void");
        assertFalse(world.isInVoid(-4), "a few blocks of grace below y=0");
        assertTrue(world.isInVoid(CoreWorld.VOID_LEVEL), "exactly at the void level counts");
        assertTrue(world.isInVoid(-50), "deep below the world is the void");
    }
}
