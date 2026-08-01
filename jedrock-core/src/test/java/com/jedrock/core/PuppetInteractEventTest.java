package com.jedrock.core;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerInteractEntityEvent;
import com.jedrock.api.event.player.PuppetInteractEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hitting a puppet, from the outside.
 *
 * <p>A puppet could always answer for itself, but only the script that spawned it could say how — which
 * left an NPC framework, a protection rule or a log with nothing to hook. These pin the resolved event
 * that fills that gap, and the order it sits in: the wire event first (cancelling it stops everything),
 * then this one, then the puppet's own callback.
 */
class PuppetInteractEventTest {

    private final CoreWorld world = new CoreWorld("hit", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final EntityDirector entities = new EntityDirector(players, world);
    private final CombatService combat = new CombatService(players, world, events,
            new PlayerBroadcast(players), entities, new BlindJudge(false, 6.0, 10.0));

    private final Conn conn = new Conn();

    private CorePlayer attacker() {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                new Location(world, 0, 64, 0, 0f, 0f), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    private PuppetEntity puppet() {
        return entities.spawnPuppet(EntityType.ZOMBIE, new Location(world, 1, 64, 0, 0f, 0f), "Guard");
    }

    @Test
    void hittingAPuppetHandsTheListenerThePuppetItself() {
        List<PuppetInteractEvent> seen = new ArrayList<>();
        events.register(PuppetInteractEvent.class, seen::add);
        CorePlayer player = attacker();
        PuppetEntity guard = puppet();

        combat.onAttack(conn, guard.getEntityId());

        assertEquals(1, seen.size());
        assertSame(guard, seen.get(0).getPuppet(), "resolved, not an entity id to look up");
        assertSame(player, seen.get(0).getPlayer());
    }

    @Test
    void cancellingItStopsThePuppetsOwnCallback() {
        events.register(PuppetInteractEvent.class, e -> e.setCancelled(true));
        attacker();
        PuppetEntity guard = puppet();
        List<String> callback = new ArrayList<>();
        guard.onInteract(who -> callback.add(who.getName()));

        combat.onAttack(conn, guard.getEntityId());

        assertTrue(callback.isEmpty(), "one script can overrule an NPC another installed");
    }

    @Test
    void otherwiseTheCallbackStillRuns() {
        attacker();
        PuppetEntity guard = puppet();
        List<String> callback = new ArrayList<>();
        guard.onInteract(who -> callback.add(who.getName()));

        combat.onAttack(conn, guard.getEntityId());

        assertEquals(List.of("P"), callback);
    }

    @Test
    void cancellingTheWireEventStopsItEverBeingAsked() {
        events.register(PlayerInteractEntityEvent.class, e -> e.setCancelled(true));
        List<PuppetInteractEvent> seen = new ArrayList<>();
        events.register(PuppetInteractEvent.class, seen::add);
        attacker();
        PuppetEntity guard = puppet();

        combat.onAttack(conn, guard.getEntityId());

        assertTrue(seen.isEmpty(), "the earlier veto covers everything downstream of it");
    }

    @Test
    void hittingNothingAtAllAnnouncesNoPuppet() {
        List<PuppetInteractEvent> seen = new ArrayList<>();
        events.register(PuppetInteractEvent.class, seen::add);
        attacker();

        combat.onAttack(conn, 999_999L); // an id that is neither a player nor a puppet

        assertTrue(seen.isEmpty());
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
