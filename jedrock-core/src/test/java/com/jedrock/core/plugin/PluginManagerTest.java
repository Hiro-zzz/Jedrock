package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.command.Command;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the scripting layer without a network: load a JS source, post real events, and prove
 * the Rhino binding runs the handler, cancels through it, schedules, registers commands, hot-reloads, and
 * tears down cleanly.
 */
class PluginManagerTest {

    private PluginManager manager(EventBus bus, Path dir) {
        return new PluginManager(bus, null, new Scheduler(), new CommandManager(null), dir);
    }

    private PluginManager manager(EventBus bus, Path dir, Scheduler scheduler) {
        return new PluginManager(bus, null, scheduler, new CommandManager(null), dir);
    }

    private PluginManager manager(EventBus bus, Path dir, CommandManager commands) {
        return new PluginManager(bus, null, new Scheduler(), commands, dir);
    }

    /** Drive a scheduler forward n ticks, as the game loop would. */
    private static void tick(Scheduler scheduler, long from, long ticks) {
        for (long t = from; t <= from + ticks; t++) {
            scheduler.tick(t);
        }
    }

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    /** A survival player over a no-op connection, for dispatching script commands at. */
    private CorePlayer newPlayer() {
        return new CorePlayer(UUID.randomUUID(), "Tester", new NoopConnection(),
                world, world.getSpawnLocation(), GameMode.SURVIVAL);
    }

    @Test
    void aScriptListenerRunsWhenTheEventIsPosted(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("greet.js",
                "var seen = 0;\n"
              + "events.on('PlayerChat', function(e) { seen++; e.setMessage('edited'); });\n"
              + "events.on('PlayerChat', function(e) { console.log('saw', seen); });\n", 1L);

        PlayerChatEvent event = new PlayerChatEvent(null, "hello");
        bus.post(event);

        assertEquals("edited", event.getMessage(), "the script mutated the event");
    }

    @Test
    void aScriptCanCancelACancellableEvent(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("nojoin.js",
                "events.on('PlayerJoin', function(e) { e.setCancelled(true); });", 1L);

        assertTrue(bus.post(new PlayerJoinEvent(null)).isCancelled(), "the script vetoed the join");
    }

    @Test
    void arrowFunctionsAndLetWork(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        // ES6 arrow functions + let/const prove the language version is set. (Rhino 1.7.13 does not
        // interpolate template literals, so this uses plain concatenation.)
        plugins.loadSource("es6.js",
                "const prefix = 'hi ';\n"
              + "events.on('PlayerChat', e => e.setMessage(prefix + e.getMessage()));", 1L);

        PlayerChatEvent event = new PlayerChatEvent(null, "there");
        bus.post(event);
        assertEquals("hi there", event.getMessage());
    }

    @Test
    void unloadRemovesTheListenersAndFiresOnDisable(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("lifecycle.js",
                "events.on('ServerTick', function(e) {});\n"
              + "function onDisable() { console.log('bye'); }", 1L);
        assertTrue(bus.hasListeners(ServerTickEvent.class), "listener registered");

        plugins.unload("lifecycle.js");
        assertFalse(bus.hasListeners(ServerTickEvent.class), "unload removed the listener");
    }

    @Test
    void reloadingReplacesTheOldListeners(@TempDir Path dir) throws IOException {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        Path file = dir.resolve("counter.js");

        // v1: two chat listeners.
        Files.writeString(file, "events.on('PlayerChat', function(e){});\n"
                + "events.on('PlayerChat', function(e){});");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1000L));
        plugins.load(file);

        // v2: one chat listener. A reload must drop v1's two, not stack on top of them.
        Files.writeString(file, "events.on('PlayerChat', function(e){ e.setMessage('v2'); });");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(2000L));
        plugins.reloadChanged();

        PlayerChatEvent event = new PlayerChatEvent(null, "x");
        bus.post(event);
        assertEquals("v2", event.getMessage(), "only the reloaded version's listener ran");
        assertEquals(1, plugins.pluginNames().size(), "still one plugin, not duplicated");
    }

    @Test
    void anUnknownEventNameIsReportedNotSilentlyIgnored(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        // The script throws on load (unknown event); the manager logs and moves on, leaving no listener.
        plugins.loadSource("bad.js", "events.on('NoSuchEvent', function(e){});", 1L);

        assertTrue(plugins.pluginNames().isEmpty(), "a script that failed to load isn't registered");
        assertFalse(bus.hasListeners(PlayerChatEvent.class));
    }

    @Test
    void schedulerRunLaterFiresOnceAfterTheDelay(@TempDir Path dir) {
        EventBus bus = new EventBus();
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(bus, dir, scheduler);
        plugins.loadSource("later.js",
                "var runs = 0;\n"
              + "scheduler.runLater(function() { runs++; }, 5);\n"
              + "events.on('PlayerChat', function(e) { e.setMessage('runs=' + runs); });", 1L);

        // Not yet: only 3 ticks in.
        tick(scheduler, 0, 3);
        PlayerChatEvent before = new PlayerChatEvent(null, "");
        bus.post(before);
        assertEquals("runs=0", before.getMessage(), "task hasn't reached its delay");

        // Past the delay — and it must fire exactly once, not every tick after.
        tick(scheduler, 4, 20);
        PlayerChatEvent after = new PlayerChatEvent(null, "");
        bus.post(after);
        assertEquals("runs=1", after.getMessage(), "one-shot fired once");
    }

    @Test
    void schedulerRunTimerRepeatsUntilCancelled(@TempDir Path dir) {
        EventBus bus = new EventBus();
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(bus, dir, scheduler);
        plugins.loadSource("timer.js",
                "var runs = 0;\n"
              + "var t = scheduler.runTimer(function() { runs++; if (runs === 3) t.cancel(); }, 2);\n"
              + "events.on('PlayerChat', function(e) { e.setMessage('runs=' + runs); });", 1L);

        tick(scheduler, 0, 40);
        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);
        assertEquals("runs=3", event.getMessage(), "timer repeated then cancelled itself");
    }

    @Test
    void reloadCancelsAScriptsRepeatingTimer(@TempDir Path dir) {
        EventBus bus = new EventBus();
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(bus, dir, scheduler);

        // A timer that would run forever, incrementing a counter parked on the shared bus via chat.
        plugins.loadSource("ticker.js",
                "var runs = 0;\n"
              + "scheduler.runTimer(function() { runs++; }, 1);\n"
              + "events.on('PlayerChat', function(e) { e.setMessage('v1=' + runs); });", 1L);
        tick(scheduler, 0, 5);

        // Reload with a version that has no timer. v1's timer must stop — no ghost firing into a dead scope.
        plugins.loadSource("ticker.js",
                "events.on('PlayerChat', function(e) { e.setMessage('v2'); });", 2L);
        tick(scheduler, 6, 50);

        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);
        assertEquals("v2", event.getMessage(), "only the reloaded listener runs; the old timer was cancelled");
    }

    @Test
    void setTimeoutGlobalWorksInMilliseconds(@TempDir Path dir) {
        EventBus bus = new EventBus();
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(bus, dir, scheduler);
        // 100 ms == 2 ticks.
        plugins.loadSource("timeout.js",
                "var fired = false;\n"
              + "setTimeout(function() { fired = true; }, 100);\n"
              + "events.on('PlayerChat', function(e) { e.setMessage(fired ? 'yes' : 'no'); });", 1L);

        tick(scheduler, 0, 1);
        PlayerChatEvent early = new PlayerChatEvent(null, "");
        bus.post(early);
        assertEquals("no", early.getMessage(), "not fired at 1 tick");

        tick(scheduler, 2, 3);
        PlayerChatEvent late = new PlayerChatEvent(null, "");
        bus.post(late);
        assertEquals("yes", late.getMessage(), "fired by 2 ticks");
    }

    @Test
    void aThrowingScheduledTaskDoesNotBreakTheScheduler(@TempDir Path dir) {
        EventBus bus = new EventBus();
        Scheduler scheduler = new Scheduler();
        PluginManager plugins = manager(bus, dir, scheduler);
        plugins.loadSource("boomtask.js",
                "scheduler.runLater(function() { throw new Error('boom'); }, 1);\n"
              + "var ok = false;\n"
              + "scheduler.runLater(function() { ok = true; }, 2);\n"
              + "events.on('PlayerChat', function(e) { e.setMessage(ok ? 'ok' : 'no'); });", 1L);

        tick(scheduler, 0, 5); // the throwing task must not stop the second one
        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);
        assertEquals("ok", event.getMessage(), "a throwing task was logged, the next still ran");
    }

    @Test
    void aThrowingHandlerDoesNotBreakDispatch(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("boom.js",
                "events.on('PlayerChat', function(e){ throw new Error('boom'); });", 1L);

        // Posting must not propagate the script error.
        PlayerChatEvent event = new PlayerChatEvent(null, "x");
        bus.post(event);
        assertEquals("x", event.getMessage(), "dispatch survived the throwing script");
    }

    @Test
    void aScriptRegistersACommandWithItsMetadata(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("kit.js",
                "commands.register({ name: 'Kit', aliases: ['starter', 'sk'],\n"
              + "  description: 'Grab a kit', usage: '/kit',\n"
              + "  execute: function(player, args) {} });", 1L);

        Command c = cm.get("kit");
        assertNotNull(c, "the command is registered under its (lower-cased) name");
        assertEquals("kit", c.name());
        assertEquals("Grab a kit", c.description());
        assertEquals("/kit", c.usage());
        assertNotNull(cm.get("starter"), "an alias resolves");
        assertNotNull(cm.get("sk"), "and the second alias");
    }

    @Test
    void aRegisteredCommandRunsItsHandlerWithArgs(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        // Sets the sender's health to args[0]; proves the api Player + String[] args reach the script.
        plugins.loadSource("hp.js",
                "commands.register('hp', function(player, args) {\n"
              + "  player.setHealth(parseInt(args[0]));\n"
              + "});", 1L);

        CorePlayer player = newPlayer();
        cm.dispatch(player, "/hp 7");
        assertEquals(7, player.getHealth(), "the handler ran with the parsed argument");
    }

    @Test
    void reloadUnregistersOldCommands(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);

        plugins.loadSource("cmds.js", "commands.register('alpha', function(p, a) {});", 1L);
        assertNotNull(cm.get("alpha"), "v1 registered /alpha");

        // v2 registers a different command — the reload must drop /alpha, not leave a ghost.
        plugins.loadSource("cmds.js", "commands.register('beta', function(p, a) {});", 2L);
        assertNull(cm.get("alpha"), "the reloaded plugin's old command was unregistered");
        assertNotNull(cm.get("beta"), "and its new command is live");
    }

    @Test
    void unloadUnregistersCommands(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("gone.js",
                "commands.register({ name: 'poof', aliases: ['p'], execute: function(pl, a) {} });", 1L);
        assertNotNull(cm.get("poof"));
        assertNotNull(cm.get("p"));

        plugins.unload("gone.js");
        assertNull(cm.get("poof"), "unload removed the command");
        assertNull(cm.get("p"), "and its alias");
    }

    @Test
    void aThrowingCommandIsReportedToTheSender(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("bad.js",
                "commands.register('boom', function(p, a) { throw new Error('kaboom'); });", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "Cap", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);
        // dispatch catches the propagated error and messages the sender — it must not escape here.
        cm.dispatch(player, "/boom");
        assertTrue(conn.lastMessage != null && conn.lastMessage.contains("failed"),
                "the sender was told the command failed, got: " + conn.lastMessage);
    }

    /** Captures the last message pushed to the connection (for the command-error test). */
    private static final class CapturingConnection extends NoopConnection {
        private String lastMessage;
        @Override public void sendMessage(String message) {
            this.lastMessage = message;
        }
    }

    /** All-no-op connection, enough to build a CorePlayer for command dispatch. */
    private static class NoopConnection implements PlayerConnection {
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.PE_1_1_5; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void sendMessage(String message) { }
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
