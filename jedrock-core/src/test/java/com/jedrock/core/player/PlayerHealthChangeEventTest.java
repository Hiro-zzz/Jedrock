package com.jedrock.core.player;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerHealthChangeEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Health changing, from wherever it changed.
 *
 * <p>Damage had an event and healing had nothing, so anything that wanted to react to the number itself —
 * a regeneration system, a bar over a name tag, a "you are hurt" hint — had to poll. These pin the single
 * point both directions now pass through, including the two ways it could have gone wrong: a listener
 * writing health from inside the event, and a change that isn't one.
 */
class PlayerHealthChangeEventTest {

    private final CoreWorld world = new CoreWorld("hp", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();

    private CorePlayer player() {
        return new CorePlayer(UUID.randomUUID(), "P", new Conn(), world,
                world.getSpawnLocation(), GameMode.SURVIVAL, events);
    }

    @Test
    void healingFiresIt() {
        List<PlayerHealthChangeEvent> seen = new ArrayList<>();
        CorePlayer p = player();
        p.setHealth(5);
        events.register(PlayerHealthChangeEvent.class, seen::add);

        p.setHealth(20);

        assertEquals(1, seen.size());
        assertEquals(5, seen.get(0).getOldHealth());
        assertEquals(20, seen.get(0).getNewHealth());
        assertEquals(false, seen.get(0).isDamage(), "the direction, without comparing two numbers");
    }

    @Test
    void damageFiresItToo() {
        List<PlayerHealthChangeEvent> seen = new ArrayList<>();
        events.register(PlayerHealthChangeEvent.class, seen::add);
        CorePlayer p = player();

        p.damage(6);

        assertEquals(1, seen.size());
        assertEquals(20, seen.get(0).getOldHealth());
        assertEquals(14, seen.get(0).getNewHealth());
        assertTrue(seen.get(0).isDamage());
    }

    @Test
    void cancellingLeavesHealthExactlyWhereItWas() {
        events.register(PlayerHealthChangeEvent.class, e -> e.setCancelled(true));
        CorePlayer p = player();

        p.damage(6);

        assertEquals(20, p.getHealth(), "not hurt — and not healed either");
    }

    @Test
    void aListenerCanRewriteTheNumber() {
        events.register(PlayerHealthChangeEvent.class, e -> e.setNewHealth(e.getNewHealth() + 4));
        CorePlayer p = player();

        p.damage(10);

        assertEquals(14, p.getHealth(), "half the hit absorbed");
    }

    @Test
    void aRewriteIsStillClampedToTheRealRange() {
        events.register(PlayerHealthChangeEvent.class, e -> e.setNewHealth(999));
        CorePlayer p = player();

        p.damage(1);

        assertEquals(CorePlayer.MAX_HEALTH, p.getHealth(), "a listener cannot invent a bigger bar");
    }

    @Test
    void aChangeThatChangesNothingIsNotAnnounced() {
        List<PlayerHealthChangeEvent> seen = new ArrayList<>();
        events.register(PlayerHealthChangeEvent.class, seen::add);
        CorePlayer p = player();

        p.setHealth(CorePlayer.MAX_HEALTH); // already full
        p.damage(0);

        assertTrue(seen.isEmpty(), "'health changed' has to mean it changed");
    }

    @Test
    void aListenerWritingHealthDoesNotComeBackRound() {
        List<Integer> seen = new ArrayList<>();
        CorePlayer p = player();
        events.register(PlayerHealthChangeEvent.class, e -> {
            seen.add(e.getNewHealth());
            if (e.getNewHealth() < 10) {
                e.getPlayer().setHealth(20); // a "keep them alive" listener, the obvious way to write it
            }
        });

        p.damage(15);

        assertEquals(1, seen.size(), "the nested write is applied plainly rather than announcing itself");
        assertEquals(20, p.getHealth(),
                "and it wins: it is a later decision than the change that was being settled");
    }

    @Test
    void aPlayerWithNoEventBusJustChangesHealth() {
        CorePlayer bare = new CorePlayer(UUID.randomUUID(), "P", new Conn(), world,
                world.getSpawnLocation(), GameMode.SURVIVAL); // the constructor tests use
        bare.damage(3);
        assertEquals(17, bare.getHealth());
    }

    private static final class Conn implements PlayerConnection {
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public void sendMessage(String message) { }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z,
                                         float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
