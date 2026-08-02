package com.jedrock.core.world;

import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time of day — scenery, not simulation.
 *
 * <p>The part worth pinning is that nothing on this side ticks: the server holds a number and a moment,
 * and reading the time later answers what the <em>clients</em> are showing, because they are the ones
 * animating the sky. Freezing has to pin the clock where they have it rather than where it was last set,
 * or the sun would jump backwards the instant it stopped — which is the bug this arrangement invites.
 */
class WorldTimeTest {

    private CoreWorld world() {
        return new CoreWorld("clock", Dimension.OVERWORLD, 1L);
    }

    @Test
    void aFreshWorldStartsAtSunrise() {
        assertEquals(0L, world().getTime());
        assertTrue(world().isDaylightCycle(), "the sun moves unless somebody stops it");
    }

    @Test
    void settingItWrapsIntoTheDay() {
        CoreWorld world = world();
        world.setDaylightCycle(false); // freeze, so the reads below are exact rather than racing the clock

        world.setTime(6000);
        assertEquals(6000L, world.getTime());

        world.setTime(24000);
        assertEquals(0L, world.getTime(), "a whole day later is the same o'clock");

        world.setTime(-1000);
        assertEquals(23000L, world.getTime(), "and before sunrise is late the night before");
    }

    @Test
    void afrozenClockDoesNotMove() throws InterruptedException {
        CoreWorld world = world();
        world.setTime(18000);
        world.setDaylightCycle(false);

        long first = world.getTime();
        Thread.sleep(120); // more than two ticks' worth
        assertEquals(first, world.getTime(), "nothing advances it, because nothing here ticks");
    }

    @Test
    void freezingPinsWhatTheClientsAreShowing() {
        CoreWorld world = world();
        world.setTime(6000);

        // Running, the answer is 6000 plus however long ago that was — freezing must keep that, not
        // snap back to the 6000 that was last sent.
        long running = world.getTime();
        world.setDaylightCycle(false);

        assertTrue(world.getTime() >= running,
                "the sun must not jump backwards at the moment it stops");
        assertTrue(world.getTime() < running + 100, "…nor forwards");
    }

    @Test
    void resumingLeavesItWhereItWas() {
        CoreWorld world = world();
        world.setTime(12000);
        world.setDaylightCycle(false);
        long frozen = world.getTime();

        world.setDaylightCycle(true);

        assertTrue(world.getTime() >= frozen && world.getTime() < frozen + 100);
        assertTrue(world.isDaylightCycle());
    }

    @Test
    void aWorldWithNobodyInItStillKeepsTime() {
        CoreWorld world = world();
        world.setTime(9000);
        assertFalse(world.getPlayers().iterator().hasNext(), "nobody is watching");
        assertTrue(world.getTime() >= 9000, "and it costs nothing to have kept it");
    }
}
