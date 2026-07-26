package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A script can drive the sidebar off the api {@code Player}: {@code player.setSidebar(title, [lines])}.
 * This pins that a JS array reaches the {@code List<String>} parameter (Rhino's NativeArray conversion),
 * and that the markup is rendered to legacy before it hits the connection.
 */
class ScriptSidebarTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    private PluginManager manager(EventBus bus, Path dir, CommandManager cm) {
        return new PluginManager(bus, null, new Scheduler(), cm, new PacketTapRegistry(), dir);
    }

    @Test
    void aScriptSetsAndClearsTheSidebar(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(bus, dir, cm);
        plugins.loadSource("sb.js",
                "commands.register('board', function (player, args) {\n"
              + "  player.setSidebar('{gold}Stats', ['{white}Kills: 3', 'Deaths: 1']);\n"
              + "});\n"
              + "commands.register('unboard', function (player, args) { player.clearSidebar(); });", 1L);

        SidebarConnection conn = new SidebarConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);

        cm.dispatch(player, "/board");
        assertEquals("§6Stats", conn.title, "markup rendered to legacy");
        assertArrayEquals(new String[]{"§fKills: 3", "Deaths: 1"}, conn.lines,
                "the JS array reached the List parameter, rendered line by line");

        cm.dispatch(player, "/unboard");
        assertNull(conn.title, "clearSidebar reached the connection");
    }

    /**
     * A concatenated line is a Rhino {@code ConsString}, not a {@code String} — and since {@code NativeArray}
     * itself implements {@code java.util.List}, the array reaches the parameter with its elements
     * unconverted. Reading one as a {@code String} used to checkcast and throw; the sidebar must render it.
     */
    @Test
    void concatenatedLinesReachTheSidebar(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(bus, dir, cm);
        plugins.loadSource("sb.js",
                "commands.register('board', function (player, args) {\n"
              + "  player.setSidebar('{gold}' + 'Stats', ['{white}Player: ' + player.getName(),\n"
              + "                                        '{gray}Score: ' + (1 + 2)]);\n"
              + "});", 1L);

        SidebarConnection conn = new SidebarConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);

        cm.dispatch(player, "/board");
        assertEquals("§6Stats", conn.title, "a concatenated title renders");
        assertArrayEquals(new String[]{"§fPlayer: P", "§7Score: 3"}, conn.lines,
                "concatenated lines render instead of throwing a ClassCastException");
    }

    /** Records the sidebar the connection is told to show. */
    private static final class SidebarConnection implements PlayerConnection {
        String title;
        String[] lines;

        @Override public void setSidebar(String title, String[] lines) { this.title = title; this.lines = lines; }
        @Override public void clearSidebar() { this.title = null; this.lines = null; }

        @Override public void sendMessage(String message) { }
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public String getAddress() { return "test"; }
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
