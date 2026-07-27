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

        RecordingConnection conn = new RecordingConnection();
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
        RecordingConnection conn = new RecordingConnection();
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
        RecordingConnection conn = new RecordingConnection();
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

    @Test
    void puppetsAndHologramsAreViewsToo(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = new PluginManager(bus, new StubServer(world), new Scheduler(), cm,
                new PacketTapRegistry(), dir);
        RecordingConnection conn = new RecordingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);

        // The stub's puppet and hologram both carry an extra public method that no api interface declares
        // — exactly the shape of the real implementations' internals.
        plugins.loadSource("v.js",
                "commands.register('probe', function (p, args) {\n"
              + "  var puppet = server.spawnPuppet(null, p.getLocation());\n"
              + "  p.sendMessage('tag=' + typeof puppet.setNameTag);\n"
              + "  p.sendMessage('puppetDoor=' + typeof puppet.secretDoor);\n"
              + "  var holo = server.spawnHologram(p.getLocation(), 'a', 'b');\n"
              + "  p.sendMessage('lines=' + holo.getLines().size());\n"
              + "  p.sendMessage('holoDoor=' + typeof holo.secretDoor);\n"
              + "});", 1L);
        cm.dispatch(player, "/probe");

        assertEquals(List.of("tag=function", "puppetDoor=undefined", "lines=2", "holoDoor=undefined"),
                conn.messages);
    }

}
