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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a script may touch is a contract now, not an accident of which class the core happened to pass in.
 *
 * <p>Rhino reflects an object's runtime class, so before {@link ScriptWrapFactory} a plugin could call
 * every public method the implementation had — including {@code player.getConnection()} into the network
 * layer and {@code server.getOpList()} into the permission store — while the {@code api} module, whose
 * whole job is to be the contract, described none of it. These pin both halves: the contract is reachable,
 * and the implementation is not, <em>through every path</em> a player can arrive by.
 */
class ScriptContractTest {

    private final CoreWorld world = new CoreWorld("contract", Dimension.OVERWORLD, 1L);

    private PluginManager manager(EventBus bus, Path dir, CommandManager cm) {
        return new PluginManager(bus, null, new Scheduler(), cm, new PacketTapRegistry(), dir);
    }

    /** Runs {@code body} as a /probe command and returns everything it sent to the player. */
    private List<String> probe(Path dir, String body) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(bus, dir, cm);
        plugins.loadSource("probe.js",
                "commands.register('probe', function (player, args) {\n" + body + "\n});", 1L);

        Recorder conn = new Recorder();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        cm.dispatch(player, "/probe");
        return conn.messages;
    }

    @Test
    void theContractIsReachable(@TempDir Path dir) {
        List<String> out = probe(dir,
                "  player.sendMessage('name=' + player.getName());\n"
              + "  player.sendMessage('op=' + player.isOp());\n"
              + "  player.sendMessage('perm=' + player.hasPermission('a.b'));\n"
              + "  player.sendMessage('id=' + (player.getEntityId() > 0));\n"
              + "  player.sendMessage('ver=' + player.getVersion());");

        assertEquals(List.of("name=P", "op=false", "perm=false", "id=true", "ver=1.12.2"), out);
    }

    @Test
    void theImplementationIsNotReachable(@TempDir Path dir) {
        // getConnection is the door into the network layer: with it a script writes raw packets to any
        // player. syncInventory and setPermissions are core plumbing that no plugin should see.
        List<String> out = probe(dir,
                "  player.sendMessage('conn=' + typeof player.getConnection);\n"
              + "  player.sendMessage('sync=' + typeof player.syncInventory);\n"
              + "  player.sendMessage('perms=' + typeof player.setPermissions);\n"
              + "  player.sendMessage('equip=' + typeof player.setEquipmentListener);");

        assertEquals(List.of("conn=undefined", "sync=undefined", "perms=undefined", "equip=undefined"), out);
    }

    @Test
    void aPlayerFromAnEventIsWrappedToo(@TempDir Path dir) {
        // The globals are the easy half. A player also arrives through event getters, roster queries and
        // command arguments — all of which Rhino routes through the same wrap factory.
        List<String> out = probe(dir,
                "  var viaEvent = events.emit('probe', { p: player });\n"
              + "  player.sendMessage('arg=' + typeof player.getConnection);\n"
              + "  player.sendMessage('world=' + typeof player.getWorld().setBlock);\n"
              + "  player.sendMessage('worldInternals=' + typeof player.getWorld().addPlayer);\n"
              + "  player.sendMessage('loc=' + (player.getLocation().x() === player.getX()));");

        assertEquals(List.of("arg=undefined", "world=function", "worldInternals=undefined", "loc=true"), out);
    }

    @Test
    void aWrappedPlayerStillGoesBackIntoJava(@TempDir Path dir) {
        // The other direction: what a script holds is the wrapper, so every Java entry point that takes
        // "a player" has to accept it — storage.forPlayer keys by uuid, packets.send needs the connection.
        // Either one still holding out for the raw core object would throw here.
        List<String> out = probe(dir,
                "  storage.forPlayer(player).set('seen', 7);\n"
              + "  player.sendMessage('stored=' + storage.forPlayer(player).get('seen'));\n"
              + "  packets.send(player, 0x01, [0x00]);\n"
              + "  player.sendMessage('sent=ok');");

        assertEquals(List.of("stored=7", "sent=ok"), out);
    }

    @Test
    void theServerGlobalIsTheContractOnly(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = new PluginManager(bus, new StubServer(world), new Scheduler(), cm,
                new PacketTapRegistry(), dir);
        Recorder conn = new Recorder();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);

        plugins.loadSource("srv.js",
                "commands.register('probe', function (p, args) {\n"
              + "  p.sendMessage('name=' + server.getName());\n"
              + "  p.sendMessage('ops=' + typeof server.getOpList);\n"
              + "  p.sendMessage('net=' + typeof server.getNetworkServer);\n"
              + "  p.sendMessage('plugins=' + typeof server.getPlugins);\n"
              + "  p.sendMessage('bus=' + typeof server.getEventBus);\n"
              + "  p.sendMessage('start=' + typeof server.start);\n"
              + "});", 1L);
        cm.dispatch(player, "/probe");

        assertEquals(List.of("name=stub", "ops=undefined", "net=undefined", "plugins=undefined",
                        "bus=undefined", "start=undefined"),
                conn.messages, "the roster and the clock, but no door into the server's internals");
    }

    @Test
    void twoWrappersForOnePlayerCompareEqual(@TempDir Path dir) {
        // Each crossing makes a fresh wrapper, so a script comparing "the player this event is about" with
        // "the player I am watching" is comparing two objects. Loose == still has to say they're the same.
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        StubServer stub = new StubServer(world);
        PluginManager plugins = new PluginManager(bus, stub, new Scheduler(), cm,
                new PacketTapRegistry(), dir);
        Recorder conn = new Recorder();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        stub.add(player);   // the roster hands back a second wrapper around the same player

        plugins.loadSource("eq.js",
                "commands.register('probe', function (p, args) {\n"
              + "  var fromRoster = server.getPlayers().iterator().next();\n"
              + "  p.sendMessage('same=' + (p == fromRoster));\n"
              + "});", 1L);
        cm.dispatch(player, "/probe");

        assertTrue(conn.messages.contains("same=true"), conn.messages.toString());
    }

    /** The least server that lets a script see one: a name, a world and a roster. */
    private static final class StubServer implements com.jedrock.api.Server {
        private final CoreWorld world;
        private final List<com.jedrock.api.player.Player> players = new ArrayList<>();

        StubServer(CoreWorld world) {
            this.world = world;
        }

        void add(com.jedrock.api.player.Player player) {
            players.add(player);
        }

        @Override public String getName() { return "stub"; }
        @Override public String getVersion() { return "test"; }
        @Override public void start() { }
        @Override public void shutdown() { }
        @Override public boolean isRunning() { return true; }
        @Override public EventBus getEventBus() { return new EventBus(); }
        @Override public java.util.Collection<com.jedrock.api.player.Player> getPlayers() { return players; }
        @Override public java.util.Optional<com.jedrock.api.player.Player> getPlayer(String name) {
            return players.stream().filter(p -> p.getName().equals(name)).findFirst();
        }
        @Override public java.util.Optional<com.jedrock.api.player.Player> getPlayer(UUID uuid) {
            return players.stream().filter(p -> p.getUniqueId().equals(uuid)).findFirst();
        }
        @Override public void broadcast(String message) { }
        @Override public void dispatchCommand(com.jedrock.api.player.Player player, String line) { }
        @Override public java.util.Collection<com.jedrock.api.world.World> getWorlds() { return List.of(world); }
        @Override public java.util.Optional<com.jedrock.api.world.World> getWorld(String name) {
            return java.util.Optional.of(world);
        }
        @Override public com.jedrock.api.world.World getDefaultWorld() { return world; }
        @Override public com.jedrock.api.entity.PuppetEntity spawnPuppet(
                com.jedrock.api.entity.EntityType type, com.jedrock.api.world.Location at, String name) {
            return null;
        }
        @Override public com.jedrock.api.entity.PuppetEntity spawnItem(
                com.jedrock.api.world.Location at, int state) { return null; }
        @Override public com.jedrock.api.entity.PuppetEntity spawnFallingBlock(
                com.jedrock.api.world.Location at, int state) { return null; }
        @Override public com.jedrock.api.entity.PuppetEntity spawnText(
                com.jedrock.api.world.Location at, String text) { return null; }
        @Override public com.jedrock.api.entity.Hologram spawnHologram(
                com.jedrock.api.world.Location at, String... lines) { return null; }
        @Override public long getCurrentTick() { return 0; }
        @Override public com.jedrock.api.ServerStatus getStatus() { return null; }
    }

    /** Captures what the player was told. */
    private static final class Recorder implements PlayerConnection {
        final List<String> messages = new ArrayList<>();

        @Override public void sendMessage(String message) { messages.add(message); }
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
