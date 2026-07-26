package com.jedrock.core.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A client that can't hold a sidebar itself gets it repainted on the loop. Bedrock has no scoreboard, so
 * its sidebar borrows a HUD line that fades on its own; the connection says how often it needs re-sending
 * and the core honours that without knowing which edition it is talking to. A Java connection says
 * {@code 0} and is never repainted, because its scoreboard is stateful client-side.
 */
class SidebarRepaintTest {

    private final CoreWorld world = new CoreWorld("sb", Dimension.OVERWORLD, 1L);
    private final PlayerRegistry players = new PlayerRegistry();
    private final PlayerBroadcast broadcast = new PlayerBroadcast(players);

    private CorePlayer join(Conn conn) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    @Test
    void aFadingSidebarIsRepaintedOnItsOwnCadence() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5, 20);
        CorePlayer player = join(conn);
        player.setSidebar("{gold}Stats", List.of("{white}Kills: 3"));

        assertEquals(1, conn.sets, "the first paint is the set itself");
        assertArrayEquals(new String[]{"§fKills: 3"}, conn.lines, "sent legacy-rendered");

        broadcast.repaintSidebars(19);
        assertEquals(1, conn.sets, "not on an off-cadence tick");

        broadcast.repaintSidebars(20);
        assertEquals(2, conn.sets, "repainted a second later");
        assertEquals("§6Stats", conn.title, "the same rendered content, not the raw markup again");

        broadcast.repaintSidebars(40);
        assertEquals(3, conn.sets);
    }

    @Test
    void aClearedSidebarStopsBeingRepainted() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14, 20);
        CorePlayer player = join(conn);
        player.setSidebar("Stats", List.of("a"));
        player.clearSidebar();

        assertNull(conn.title, "clear reached the connection");

        broadcast.repaintSidebars(20);
        broadcast.repaintSidebars(40);

        assertEquals(1, conn.sets, "nothing to repaint once it's cleared");
    }

    @Test
    void aJavaSidebarIsNeverRepainted() {
        Conn conn = new Conn(ProtocolVersion.JE_1_12_2, 0); // the client holds the scoreboard itself
        CorePlayer player = join(conn);
        player.setSidebar("Stats", List.of("a"));

        for (long tick = 1; tick <= 60; tick++) {
            broadcast.repaintSidebars(tick);
        }

        assertEquals(1, conn.sets, "sent once and left alone — the JE scoreboard is stateful");
    }

    @Test
    void aPlayerWithNoSidebarIsUntouched() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5, 20);
        join(conn);

        broadcast.repaintSidebars(20);

        assertEquals(0, conn.sets);
    }

    /** Records what the connection was told to draw, and how often it asks to be repainted. */
    private static final class Conn implements PlayerConnection {
        private final ProtocolVersion version;
        private final int repaintTicks;
        int sets;
        String title;
        String[] lines;

        Conn(ProtocolVersion version, int repaintTicks) {
            this.version = version;
            this.repaintTicks = repaintTicks;
        }

        @Override public int sidebarRepaintTicks() { return repaintTicks; }
        @Override public void setSidebar(String title, String[] lines) {
            this.sets++;
            this.title = title;
            this.lines = lines;
        }
        @Override public void clearSidebar() { this.title = null; this.lines = null; }

        @Override public ProtocolVersion getProtocolVersion() { return version; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendMessage(String message) { }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
