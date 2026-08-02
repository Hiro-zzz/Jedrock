package com.jedrock.core.item;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cooldown clock, on a clock the test owns — which is why it is passed in rather than read. */
class ItemCooldownsTest {

    private static final long MS = 1_000_000L;

    private final ItemCooldowns cooldowns = new ItemCooldowns();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void anItemNeverUsedIsReady() {
        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 0L));
    }

    @Test
    void anItemWithNoCooldownIsAlwaysReadyEvenJustAfterUse() {
        cooldowns.start(alice, "bomb", 0L);
        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 0L, 1L));
    }

    @Test
    void theWaitCountsDownAndThenEnds() {
        cooldowns.start(alice, "bomb", 0L);

        assertEquals(5_000L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 0L));
        assertEquals(3_000L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 2_000 * MS));
        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 5_000 * MS));
        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 9_999 * MS), "and stays ended");
    }

    @Test
    void oneCooldownIsOnePlayersOwn() {
        cooldowns.start(alice, "bomb", 0L);

        assertTrue(cooldowns.remainingMillis(alice, "bomb", 5_000L, 0L) > 0);
        assertEquals(0L, cooldowns.remainingMillis(bob, "bomb", 5_000L, 0L));
    }

    @Test
    void oneItemsWaitIsNotAnothers() {
        cooldowns.start(alice, "bomb", 0L);

        assertEquals(0L, cooldowns.remainingMillis(alice, "wand", 5_000L, 0L));
    }

    @Test
    void clearingEndsItEarly() {
        cooldowns.start(alice, "bomb", 0L);
        cooldowns.clear(alice, "bomb");

        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 0L));
    }

    @Test
    void aPlayerWhoLeavesIsForgotten() {
        cooldowns.start(alice, "bomb", 0L);
        cooldowns.start(bob, "bomb", 0L);
        assertEquals(2, cooldowns.trackedPlayers());

        cooldowns.forget(alice);

        assertEquals(1, cooldowns.trackedPlayers(), "a cooldown for somebody who has gone is a leak");
        assertEquals(0L, cooldowns.remainingMillis(alice, "bomb", 5_000L, 0L));
        assertTrue(cooldowns.remainingMillis(bob, "bomb", 5_000L, 0L) > 0, "and only that one");
    }
}
