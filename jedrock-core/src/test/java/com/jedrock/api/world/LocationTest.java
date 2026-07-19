package com.jedrock.api.world;

import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The Location convenience helpers scripts lean on: distance, offset, and the different-world guard. */
class LocationTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);
    private final CoreWorld other = new CoreWorld("other", Dimension.OVERWORLD);

    @Test
    void distanceAndSquaredAreConsistent() {
        Location a = new Location(world, 0, 0, 0);
        Location b = new Location(world, 3, 0, 4);
        assertEquals(25.0, a.distanceSquared(b), 1e-9);
        assertEquals(5.0, a.distance(b), 1e-9, "3-4-5 triangle");
    }

    @Test
    void distanceAcrossWorldsIsRejected() {
        Location a = new Location(world, 0, 0, 0);
        Location b = new Location(other, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> a.distance(b));
        assertThrows(IllegalArgumentException.class, () -> a.distanceSquared(b));
    }

    @Test
    void addAndWithPositionKeepWorldAndFacing() {
        Location a = new Location(world, 10, 64, 10, 90f, 45f);

        Location up = a.add(0, 5, 0);
        assertEquals(69.0, up.y(), 1e-9);
        assertEquals(10.0, up.x(), 1e-9);
        assertSame(world, up.world());
        assertEquals(90f, up.yaw());
        assertEquals(45f, up.pitch());

        Location moved = a.withPosition(1, 2, 3);
        assertEquals(1.0, moved.x(), 1e-9);
        assertEquals(2.0, moved.y(), 1e-9);
        assertEquals(3.0, moved.z(), 1e-9);
        assertEquals(90f, moved.yaw(), "facing preserved");
    }
}
