package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.ContainerCloseEvent;
import com.jedrock.api.event.player.ContainerOpenEvent;
import com.jedrock.api.event.player.ContainerType;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container opening is one fact with two routes to it — a chest somebody right-clicked, and a menu a
 * script raised. A lock, a shop or an audit log wants to hook the fact, not the route, so both go through
 * one event; these pin that, and that refusing it really does leave the window shut.
 */
class ContainerEventsTest {

    private final CoreWorld world = new CoreWorld("ev", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ContainerService containers =
            new ContainerService(players, world, events, new PlayerBroadcast(players));

    private CorePlayer join(Conn conn) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    private void placeChest(int x, int y, int z) {
        world.setBlockId(x, y, z, Blocks.state(Blocks.CHEST, 0));
    }

    @Test
    void openingAWorldChestAnnouncesWhereItIs() {
        List<ContainerOpenEvent> seen = new ArrayList<>();
        events.register(ContainerOpenEvent.class, seen::add);
        Conn conn = new Conn();
        CorePlayer player = join(conn);
        placeChest(4, 64, -2);

        containers.onUseBlock(conn, 4, 64, -2);

        assertEquals(1, seen.size());
        assertEquals(ContainerType.CHEST, seen.get(0).getType());
        assertEquals(List.of(4, 64, -2), List.of(seen.get(0).getX(), seen.get(0).getY(), seen.get(0).getZ()));
        assertEquals(27, seen.get(0).getSize());
        assertTrue(player.hasContainerOpen());
    }

    @Test
    void refusingItLeavesTheChestShut() {
        events.register(ContainerOpenEvent.class, e -> e.setCancelled(true));
        Conn conn = new Conn();
        CorePlayer player = join(conn);
        placeChest(0, 64, 0);

        boolean consumed = containers.onUseBlock(conn, 0, 64, 0);

        assertFalse(player.hasContainerOpen(), "nothing was bound");
        assertEquals(0, conn.opened, "and nothing was sent to the client");
        assertTrue(consumed, "the click is still consumed — a refused chest must not become a placement");
    }

    @Test
    void aMenuIsTheSameEventWithNoBlock() {
        List<ContainerOpenEvent> seen = new ArrayList<>();
        events.register(ContainerOpenEvent.class, seen::add);
        Conn conn = new Conn();
        CorePlayer player = join(conn);

        assertTrue(containers.openMenu(player, "Shop", new Container(27), null, null));

        assertEquals(1, seen.size());
        assertEquals(ContainerType.MENU, seen.get(0).getType());
        assertEquals("Shop", seen.get(0).getTitle());
        assertEquals(0, seen.get(0).getX(), "a menu has no position — there is no block");
    }

    @Test
    void refusingAMenuTellsTheCallerItDidNotOpen() {
        events.register(ContainerOpenEvent.class, e -> e.setCancelled(true));
        Conn conn = new Conn();
        CorePlayer player = join(conn);

        assertFalse(containers.openMenu(player, "Shop", new Container(27), null, null));
        assertFalse(player.hasContainerOpen());
    }

    @Test
    void closingSaysWhichKindItWas() {
        List<ContainerCloseEvent> seen = new ArrayList<>();
        events.register(ContainerCloseEvent.class, seen::add);
        Conn conn = new Conn();
        CorePlayer player = join(conn);
        placeChest(1, 64, 1);
        containers.onUseBlock(conn, 1, 64, 1);

        containers.onWindowClose(conn);

        assertEquals(1, seen.size());
        assertEquals(ContainerType.CHEST, seen.get(0).getType(), "read before the container was let go of");
        assertFalse(player.hasContainerOpen());
    }

    @Test
    void closingWithNothingOpenAnnouncesNothing() {
        List<ContainerCloseEvent> seen = new ArrayList<>();
        events.register(ContainerCloseEvent.class, seen::add);
        Conn conn = new Conn();
        join(conn);

        containers.onWindowClose(conn); // a survival player closing their own inventory

        assertEquals(0, seen.size(), "the player inventory is not a container being closed");
    }

    /** The bare minimum, plus a count of windows actually raised. */
    private static final class Conn implements PlayerConnection {
        int opened;

        @Override public void openContainer(int windowId, String title, int slots, int x, int y, int z) {
            opened++;
        }
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public void setWindowItems(int windowId, int[] states, int[] counts) { }
        @Override public void setCursorItem(int state, int count) { }
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
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
