package com.jedrock.core.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who can see whom. The rule is chunk-square distance against the streaming radius, so an avatar is only
 * ever put on a client that has the terrain to stand it on — and every relay about that avatar follows the
 * same set.
 */
class PlayerTrackerTest {

    /** Radius 2 chunks: in range up to chunk 2, retained through chunk 3, dropped past it. */
    private static final int VIEW_CHUNKS = 2;

    private final CoreWorld world = new CoreWorld("t", Dimension.OVERWORLD, 1L);
    private final CoreWorld nether = new CoreWorld("n", Dimension.NETHER, 1L);
    private final PlayerRegistry players = new PlayerRegistry();
    private final PlayerBroadcast broadcast = new PlayerBroadcast(players);
    private final PlayerTracker tracker = new PlayerTracker(players, VIEW_CHUNKS);

    /** Register a player at a block position in {@code where}, without refreshing anything yet. */
    private CorePlayer join(String name, CoreWorld where, double x, double z) {
        Conn conn = new Conn();
        CorePlayer p = new CorePlayer(UUID.randomUUID(), name, conn, where,
                new Location(where, x, 64, z, 0f, 0f), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    private static Conn connOf(CorePlayer p) {
        return (Conn) p.getConnection();
    }

    /** Move a player without going through the bridge — the tracker reads their location, nothing else. */
    private static void placeAt(CorePlayer p, double x, double z) {
        p.setLocation(new Location(p.getWorld(), x, 64, z, 0f, 0f));
    }

    @Test
    void playersInRangeSeeEachOther() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", world, 32, 0); // chunk 2 away — inside the radius

        tracker.refresh(a);

        assertTrue(a.sees(b), "A holds B's avatar");
        assertTrue(b.sees(a), "and B holds A's — the rule is symmetric");
        assertEquals(List.of(b.getEntityId()), connOf(a).shown, "B was spawned for A");
        assertEquals(List.of(a.getEntityId()), connOf(b).shown, "and A for B, from the one refresh");
    }

    @Test
    void aDistantPlayerIsNeverSpawned() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", world, 4000, 0);

        tracker.refresh(a);

        assertFalse(a.sees(b), "too far to be worth an avatar");
        assertFalse(b.sees(a));
        assertTrue(connOf(a).shown.isEmpty(), "and nothing was sent about one");
        assertTrue(connOf(b).shown.isEmpty());
    }

    /** Distance is measured in chunks on the wider axis — the same square window the chunk stream uses. */
    @Test
    void rangeIsTheChunkSquareNotACircle() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer corner = join("C", world, 32, 32); // chunk (2,2): the corner of a radius-2 window

        tracker.refresh(a);

        assertTrue(a.sees(corner), "the window's corner is inside it, as it is for chunks");
    }

    @Test
    void walkingIntoRangeSpawnsAndWalkingOutHides() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", world, 4000, 0);
        tracker.refresh(a);
        assertFalse(a.sees(b));

        placeAt(b, 16, 0); // one chunk away
        tracker.refresh(b);
        assertTrue(a.sees(b), "coming into range spawned the avatar");
        assertEquals(List.of(b.getEntityId()), connOf(a).shown);

        placeAt(b, 4000, 0);
        tracker.refresh(b);
        assertFalse(a.sees(b), "leaving hid it again");
        assertFalse(b.sees(a));
        assertEquals(List.of(b.getEntityId()), connOf(a).hidden, "A was told to drop B's avatar");
        assertEquals(List.of(a.getEntityId()), connOf(b).hidden, "and B to drop A's");
    }

    /**
     * A player treading back and forth over one chunk line must not spawn and despawn on every step, so
     * leaving costs one chunk more than arriving.
     */
    @Test
    void theEdgeDoesNotChatter() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", world, 32, 0); // chunk 2 — linked
        tracker.refresh(a);
        assertTrue(a.sees(b));

        placeAt(b, 48, 0); // chunk 3 — past the spawn radius, inside the hysteresis band
        tracker.refresh(b);
        assertTrue(a.sees(b), "one chunk past the edge keeps what it already had");
        assertTrue(connOf(a).hidden.isEmpty(), "nothing was re-sent");

        placeAt(b, 64, 0); // chunk 4 — past the band
        tracker.refresh(b);
        assertFalse(a.sees(b), "two chunks past, it goes");
    }

    @Test
    void anotherWorldIsNotAnotherDistance() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", nether, 0, 0); // same coordinates, different world

        tracker.refresh(a);

        assertFalse(a.sees(b), "there is no avatar in another world to hold");
        assertTrue(connOf(a).shown.isEmpty());
    }

    @Test
    void forgetHidesTheAvatarOnBothSidesAndClearsTheSet() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer b = join("B", world, 16, 0);
        tracker.refresh(a);
        assertTrue(a.sees(b));

        tracker.forget(a);

        assertFalse(a.sees(b), "A's set is empty");
        assertFalse(b.sees(a), "and A is out of B's");
        assertEquals(List.of(a.getEntityId()), connOf(b).hidden, "B was told to drop A's avatar");
        assertEquals(List.of(b.getEntityId()), connOf(a).hidden);
    }

    /** The point of the whole thing: a relay costs a packet per watcher, not per player online. */
    @Test
    void aRelayOnlyReachesTheClientsHoldingTheAvatar() {
        CorePlayer a = join("A", world, 0, 0);
        CorePlayer near = join("N", world, 16, 0);
        CorePlayer far = join("F", world, 4000, 0);
        tracker.refresh(a);

        broadcast.move(a, 1, 64, 0, 0f, 0f);

        assertEquals(1, connOf(near).moves, "the watcher got the move");
        assertEquals(0, connOf(far).moves, "the distant player got nothing");
        assertEquals(0, connOf(a).moves, "and the mover is not told about themselves");
    }

    /**
     * Two players crossing a chunk line in the same instant refresh the same pair from their own network
     * threads. Sending a client a second spawn for an avatar it already holds is not cosmetic on a 1.1.5
     * client, so the pair transition has to happen exactly once however the two threads interleave.
     */
    @Test
    void aPairRefreshedFromBothSidesAtOnceSpawnsExactlyOnce() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            PlayerRegistry roster = new PlayerRegistry(); // a fresh pair each round, so nothing carries over
            PlayerTracker t = new PlayerTracker(roster, VIEW_CHUNKS);
            Conn ca = new Conn();
            Conn cb = new Conn();
            CorePlayer a = new CorePlayer(UUID.randomUUID(), "A", ca, world,
                    new Location(world, 0, 64, 0, 0f, 0f), GameMode.SURVIVAL);
            CorePlayer b = new CorePlayer(UUID.randomUUID(), "B", cb, world,
                    new Location(world, 16, 64, 0, 0f, 0f), GameMode.SURVIVAL);
            roster.add(a);
            roster.add(b);

            CountDownLatch go = new CountDownLatch(1);
            Thread ta = new Thread(() -> { awaitQuietly(go); t.refresh(a); });
            Thread tb = new Thread(() -> { awaitQuietly(go); t.refresh(b); });
            ta.start();
            tb.start();
            go.countDown();
            ta.join();
            tb.join();

            assertEquals(1, ca.shown.size(), "round " + round + ": A was shown B once, not twice");
            assertEquals(1, cb.shown.size(), "round " + round + ": and B shown A once");
            assertTrue(a.sees(b), "round " + round + ": and the pair is linked");
            assertTrue(b.sees(a), "round " + round);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records the avatar traffic a client was actually sent. */
    private static final class Conn implements PlayerConnection {
        final List<Long> shown = Collections.synchronizedList(new ArrayList<>());
        final List<Long> hidden = Collections.synchronizedList(new ArrayList<>());
        volatile int moves;

        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) {
            shown.add(entityId);
        }
        @Override public void hidePlayer(UUID uuid, long entityId) { hidden.add(entityId); }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
            moves++;
        }

        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendMessage(String message) { }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
