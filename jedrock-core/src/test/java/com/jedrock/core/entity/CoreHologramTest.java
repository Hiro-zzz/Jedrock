package com.jedrock.core.entity;

import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hologram's own bookkeeping — the line stack and its entity ids — with no server or network in sight.
 * (The relays these trigger are exercised on the wire by the per-edition encoding tests.)
 */
class CoreHologramTest {

    private static final CoreWorld WORLD = new CoreWorld("holo", Dimension.OVERWORLD, 1L);

    /** A hologram built without a server: every mutation below either avoids the relay or is not called. */
    private static CoreHologram hologram(String... lines) {
        return new CoreHologram(WORLD, new Location(WORLD, 8.0, 70.0, 8.0, 0f, 0f), null, lines);
    }

    @Test
    void eachLineGetsItsOwnEntityId() {
        CoreHologram h = hologram("one", "two", "three");

        assertEquals(List.of("one", "two", "three"), h.getLines(), "lines, top to bottom");
        assertEquals(3, h.getLineIds().length, "one entity per line");
        long[] ids = h.getLineIds();
        assertNotEquals(ids[0], ids[1], "ids are distinct");
        assertNotEquals(ids[1], ids[2], "ids are distinct");
        assertNotEquals(h.getEntityId(), ids[0], "the hologram's own id is not a line's");
    }

    @Test
    void linesHangDownwardsFromTheAnchor() {
        CoreHologram h = hologram("top", "middle", "bottom");

        assertEquals(70.0, h.lineLocation(0).y(), 1e-9, "line 0 sits at the anchor");
        assertEquals(70.0 - CoreHologram.LINE_SPACING, h.lineLocation(1).y(), 1e-9, "one spacing below");
        assertEquals(70.0 - 2 * CoreHologram.LINE_SPACING, h.lineLocation(2).y(), 1e-9, "two below");
        assertEquals(8.0, h.lineLocation(2).x(), 1e-9, "the stack is vertical: x is unchanged");
        assertEquals(8.0, h.lineLocation(2).z(), 1e-9, "the stack is vertical: z is unchanged");
    }

    @Test
    void setLineRejectsALineThatIsNotThere() {
        CoreHologram h = hologram("only");

        assertThrows(IndexOutOfBoundsException.class, () -> h.setLine(1, "nope"), "past the end");
        assertThrows(IndexOutOfBoundsException.class, () -> h.setLine(-1, "nope"), "before the start");
    }

    @Test
    void getLinesIsASnapshotTheCallerCannotMutate() {
        CoreHologram h = hologram("one");

        assertThrows(UnsupportedOperationException.class, () -> h.getLines().add("two"));
    }
}
