package com.jedrock.core.command;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers command routing ({@link CommandManager}) and the self-target {@code /gamemode} path — both
 * exercisable without a live server (a null server is fine here: the manager only stores it and the
 * command uses it only when a second argument names another player).
 */
class CommandTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    private CorePlayer player(RecordingConnection c) {
        return new CorePlayer(UUID.randomUUID(), "Tester", c, world, world.getSpawnLocation(), GameMode.CREATIVE);
    }

    @Test
    void routesByNameAndAliasWithSplitArgs() {
        CommandManager cm = new CommandManager(null);
        RecordingCommand ping = new RecordingCommand("ping", List.of("p"));
        cm.register(ping);

        RecordingConnection conn = new RecordingConnection();
        CorePlayer sender = player(conn);

        cm.dispatch(sender, "/ping alpha beta");
        assertEquals(1, ping.calls);
        assertEquals(List.of("alpha", "beta"), List.of(ping.lastArgs));

        cm.dispatch(sender, "/p");                 // alias, no args
        assertEquals(2, ping.calls);
        assertEquals(0, ping.lastArgs.length);
    }

    @Test
    void unknownCommandIsReportedNotBroadcast() {
        CommandManager cm = new CommandManager(null);
        RecordingConnection conn = new RecordingConnection();
        cm.dispatch(player(conn), "/nope");
        assertEquals(1, conn.messages.size());
        assertTrue(conn.messages.get(0).toLowerCase().contains("unknown command"), conn.messages.get(0));
    }

    @Test
    void loneSlashIsSwallowed() {
        CommandManager cm = new CommandManager(null);
        RecordingConnection conn = new RecordingConnection();
        cm.dispatch(player(conn), "/");
        assertTrue(conn.messages.isEmpty());
    }

    @Test
    void setGameModePushesLiveSwitchOnlyOnChange() {
        // The self-target /gamemode path routes through CorePlayer.setGameMode; verify it here (the
        // full command persists via the server, which is too heavy to build in a unit test).
        RecordingConnection conn = new RecordingConnection();
        CorePlayer p = player(conn);                 // starts CREATIVE

        p.setGameMode(GameMode.SURVIVAL);
        assertEquals(GameMode.SURVIVAL, p.getGameMode());
        assertEquals(GameMode.SURVIVAL, conn.lastGameMode); // pushed live to the client

        conn.lastGameMode = null;
        p.setGameMode(GameMode.SURVIVAL);            // no change
        assertNull(conn.lastGameMode, "an unchanged mode must not re-push to the client");
    }

    @Test
    void gamemodeRejectsUnknownMode() {
        RecordingConnection conn = new RecordingConnection();
        CorePlayer sender = player(conn);

        new GameModeCommand().execute(null, sender, new String[]{"god"}); // returns before touching server

        assertEquals(GameMode.CREATIVE, sender.getGameMode()); // unchanged
        assertNull(conn.lastGameMode);                          // never pushed
    }

    @Test
    void messageCommandsRejectEmptyInputWithoutTouchingServer() {
        // Their usage guards fire before any server call, so a null server is safe here (as in
        // gamemodeRejectsUnknownMode). Each must report a usage hint rather than broadcast nothing.
        RecordingConnection conn = new RecordingConnection();
        CorePlayer sender = player(conn);

        new SayCommand().execute(null, sender, new String[0]);
        new MeCommand().execute(null, sender, new String[0]);
        new TpHereCommand().execute(null, sender, new String[0]);
        new MsgCommand().execute(null, sender, new String[]{"onlyName"}); // needs 2+ args

        assertEquals(4, conn.messages.size());
        for (String m : conn.messages) {
            assertTrue(m.toLowerCase().contains("usage"), m);
        }
    }

    @Test
    void clearRefusesCreativeWithoutTouchingServer() {
        // /clear on a creative target returns before any server call (the test player is CREATIVE), so a
        // null server is safe. It must explain that only a survival inventory can be cleared.
        RecordingConnection conn = new RecordingConnection();
        CorePlayer sender = player(conn); // CREATIVE

        new ClearCommand().execute(null, sender, new String[0]);

        assertEquals(1, conn.messages.size());
        assertTrue(conn.messages.get(0).toLowerCase().contains("survival"), conn.messages.get(0));
    }

    // ===== Test doubles =====

    /** A command that records how it was invoked. */
    private static final class RecordingCommand implements Command {
        final String name;
        final List<String> aliases;
        int calls;
        String[] lastArgs = new String[0];

        RecordingCommand(String name, List<String> aliases) {
            this.name = name;
            this.aliases = aliases;
        }

        @Override public String name() { return name; }
        @Override public List<String> aliases() { return aliases; }
        @Override public String description() { return "test"; }
        @Override public String usage() { return "/" + name; }
        @Override public void execute(com.jedrock.core.JedrockServer server, CorePlayer sender, String[] args) {
            calls++;
            lastArgs = args;
        }
    }

    /** Captures the messages and game-mode pushes a command sends to the client. */
    private static final class RecordingConnection implements PlayerConnection {
        final List<String> messages = new ArrayList<>();
        GameMode lastGameMode;

        @Override public void sendMessage(String message) { messages.add(message); }
        @Override public void setGameMode(GameMode mode) { lastGameMode = mode; }

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
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
