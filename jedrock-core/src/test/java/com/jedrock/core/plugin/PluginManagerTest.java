package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.EventPriority;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Weather;
import com.jedrock.core.command.Command;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.net.PacketDirection;
import com.jedrock.core.net.PacketEvent;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        return new PluginManager(bus, null, new Scheduler(), new CommandManager(null),
                new PacketTapRegistry(), dir);
    }

    private PluginManager manager(EventBus bus, Path dir, Scheduler scheduler) {
        return new PluginManager(bus, null, scheduler, new CommandManager(null),
                new PacketTapRegistry(), dir);
    }

    private PluginManager manager(EventBus bus, Path dir, CommandManager commands) {
        return new PluginManager(bus, null, new Scheduler(), commands, new PacketTapRegistry(), dir);
    }

    private PluginManager manager(EventBus bus, Path dir, PacketTapRegistry packetTaps) {
        return new PluginManager(bus, null, new Scheduler(), new CommandManager(null), packetTaps, dir);
    }

    /**
     * The weather event reaches scripts, and its enum-valued redirect is usable from JS — the one part of
     * this event that isn't obviously ergonomic through Rhino, so it is pinned rather than assumed. The
     * script reads the enum the way {@link #aJavaStringFromAnEnumIsNotStrictlyEqualToAJsString} says to.
     */
    @Test
    void aScriptCanRedirectTheWeather(@TempDir Path dir) throws IOException {
        EventBus bus = new EventBus();
        CoreWorld world = new CoreWorld("sky", Dimension.OVERWORLD, 1L);
        world.setEventBus(bus);
        Files.writeString(dir.resolve("sky.js"),
                "events.on('WeatherChange', function (e) {\n"
                        + "  if (e.getTo() == Packages.com.jedrock.api.world.Weather.THUNDER)"
                        + " e.setTo(Packages.com.jedrock.api.world.Weather.RAIN);\n"
                        + "});");
        manager(bus, dir).loadAll();

        world.setWeather(Weather.THUNDER);

        assertEquals(Weather.RAIN, world.getWeather(), "the script downgraded the storm");
    }

    /**
     * The Rhino trap this project used to have, now closed and pinned so it stays closed: a {@code String}
     * returned <em>from Java</em> was wrapped, and a wrapper is never {@code ===} a JS literal, so
     * {@code e.getTo().name() === 'THUNDER'} was silently false and the {@code if} around a listener's
     * real work never ran. The script scope now disables primitive wrapping, so all four comparisons
     * agree — which is what a script author expects and what the command-args path already did by hand.
     */
    @Test
    void aJavaStringFromAnEnumComparesStrictlyEqualToAJsString(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("cmp.js",
                "events.on('PlayerChat', function (e) {\n"
                        + "  var w = Packages.com.jedrock.api.world.Weather.THUNDER;\n"
                        + "  e.setMessage('strict=' + (w.name() === 'THUNDER')\n"
                        + "    + ' loose=' + (w.name() == 'THUNDER')\n"
                        + "    + ' cast=' + (String(w.name()) === 'THUNDER')\n"
                        + "    + ' enum=' + (w == Packages.com.jedrock.api.world.Weather.THUNDER));\n"
                        + "});", 1L);

        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);

        assertEquals("strict=true loose=true cast=true enum=true", event.getMessage(),
                "a Java-returned string reaches scripts as a JS primitive, so === behaves");
    }

    @Test
    void aScriptCanRefuseTheWeather(@TempDir Path dir) throws IOException {
        EventBus bus = new EventBus();
        CoreWorld world = new CoreWorld("sky", Dimension.OVERWORLD, 1L);
        world.setEventBus(bus);
        Files.writeString(dir.resolve("sky.js"),
                "events.on('WeatherChange', function (e) { e.setCancelled(true); });");
        manager(bus, dir).loadAll();

        world.setWeather(Weather.RAIN);

        assertEquals(Weather.CLEAR, world.getWeather(), "the sky never changed");
    }

    private static PacketEvent inbound(int id, byte[] payload) {
        return new PacketEvent(ProtocolVersion.PE_1_1_5, PacketDirection.INBOUND, id, payload, null, null);
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

    /**
     * The two halves of an item's memory, end to end through Rhino: what one stack is carrying, and how
     * long the item makes you wait. Both go through conversions that compile whatever they do — a JS object
     * stored as JSON and handed back, and a hook that is not called at all — so they are worth running.
     */
    @Test
    void aScriptGivesOneStackItsOwnStateAndTheItemACooldown(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("wand.js",
                "items.define('wand', 280 << 4)\n"
              + "  .setCooldown(60000)\n"
              + "  .onUse(function (player) {\n"
              + "      var s = items.heldData(player) || {charges: 3};\n"
              + "      s.charges--;\n"
              + "      items.setHeldData(player, s);\n"
              + "      return true;\n"
              + "  })\n"
              + "  .onCooldown(function (player, ctx) {\n"
              + "      console.log('cooling for ' + ctx.getRemaining());\n"
              + "      return true;\n"
              + "  });\n", 1L);

        CorePlayer holder = newPlayer();
        holder.getInventory().set(0, 280 << 4, 1, "wand");

        assertTrue(bus.post(new com.jedrock.api.event.player.PlayerUseItemEvent(holder, true)).isCancelled());
        assertEquals("{\"charges\":2}", holder.getInventory().customDataAt(0),
                "the JS object came back as an object, was edited, and went back down as JSON");

        assertTrue(bus.post(new com.jedrock.api.event.player.PlayerUseItemEvent(holder, true)).isCancelled(),
                "the cooldown hook consumed the second use");
        assertEquals("{\"charges\":2}", holder.getInventory().customDataAt(0),
                "…and the behaviour did not run, so nothing was spent");
    }

    // ===== Priority, unsubscribe, once =====

    /**
     * The one that matters: the README has always said a script can overrule a region's deny by listening
     * at a higher priority, and until the option existed it could not — every script listener went in at
     * NORMAL, which runs <em>before</em> the HIGH the core enforces its rules at. This models that exactly:
     * a HIGH listener cancels the way a region does, and the script has the last word.
     */
    @Test
    void aScriptAtHighestOverrulesTheCoresOwnEnforcement(@TempDir Path dir) {
        EventBus bus = new EventBus();
        bus.register(PlayerChatEvent.class, EventPriority.HIGH, e -> e.setCancelled(true)); // "a region"
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("override.js",
                "events.on('PlayerChat', function (e) { e.setCancelled(false); },"
              + " {priority: 'HIGHEST'});", 1L);

        assertFalse(bus.post(new PlayerChatEvent(newPlayer(), "hi")).isCancelled(),
                "the script ran after the enforcement and took it back");
    }

    @Test
    void andAtTheDefaultPriorityItCannot(@TempDir Path dir) {
        EventBus bus = new EventBus();
        bus.register(PlayerChatEvent.class, EventPriority.HIGH, e -> e.setCancelled(true));
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("tooearly.js",
                "events.on('PlayerChat', function (e) { e.setCancelled(false); });", 1L);

        assertTrue(bus.post(new PlayerChatEvent(newPlayer(), "hi")).isCancelled(),
                "NORMAL runs first and is then overruled — which is why the option had to exist");
    }

    @Test
    void ignoreCancelledSkipsAListenerOnceSomethingHasCancelled(@TempDir Path dir) {
        EventBus bus = new EventBus();
        bus.register(PlayerChatEvent.class, EventPriority.LOW, e -> e.setCancelled(true));
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("skip.js",
                "events.on('PlayerChat', function (e) { e.setMessage('ran'); },"
              + " {ignoreCancelled: true});", 1L);

        PlayerChatEvent event = bus.post(new PlayerChatEvent(newPlayer(), "untouched"));
        assertEquals("untouched", event.getMessage(), "cancelled before it, so it never ran");
    }

    @Test
    void aScriptCanStopListeningWithoutReloadingItself(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(bus, dir, cm);
        plugins.loadSource("sub.js",
                "var seen = 0;\n"
              + "var sub = events.on('PlayerChat', function (e) { seen++; });\n"
              + "commands.register('stop', function (p, a) { sub.remove(); p.sendMessage('seen=' + seen); });\n"
              + "commands.register('count', function (p, a) { p.sendMessage('seen=' + seen); });", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);

        bus.post(new PlayerChatEvent(player, "one"));
        cm.dispatch(player, "/stop");
        assertEquals("seen=1", conn.lastMessage);

        bus.post(new PlayerChatEvent(player, "two"));
        cm.dispatch(player, "/count");
        assertEquals("seen=1", conn.lastMessage, "removed means removed");
    }

    @Test
    void onceFiresOnceAndThenIsGone(@TempDir Path dir) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(bus, dir, cm);
        plugins.loadSource("once.js",
                "var seen = 0;\n"
              + "events.once('PlayerChat', function (e) { seen++; });\n"
              + "commands.register('count', function (p, a) { p.sendMessage('seen=' + seen); });", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);

        bus.post(new PlayerChatEvent(player, "one"));
        bus.post(new PlayerChatEvent(player, "two"));
        cm.dispatch(player, "/count");

        assertEquals("seen=1", conn.lastMessage);
        assertFalse(bus.hasListeners(PlayerChatEvent.class), "and it took itself off the bus");
    }

    @Test
    void aThrowingOnceHandlerStillDoesNotFireTwice(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("boom.js",
                "events.once('PlayerChat', function (e) { throw new Error('boom'); });", 1L);

        bus.post(new PlayerChatEvent(newPlayer(), "one"));

        assertFalse(bus.hasListeners(PlayerChatEvent.class),
                "removal happens before the handler body, so a throw cannot leave it armed");
    }

    @Test
    void aMisspeltOptionIsRefusedRatherThanQuietlyMeaningTheDefault(@TempDir Path dir) {
        PluginManager plugins = manager(new EventBus(), dir);
        plugins.loadSource("typo.js",
                "events.on('PlayerChat', function (e) {}, {priorty: 'HIGHEST'});", 1L);
        assertTrue(plugins.pluginNames().isEmpty(), "an unknown option key fails the load");

        plugins.loadSource("badvalue.js",
                "events.on('PlayerChat', function (e) {}, {priority: 'VERYHIGH'});", 1L);
        assertTrue(plugins.pluginNames().isEmpty(), "and so does a priority that isn't one");
    }

    @Test
    void priorityOrdersCustomEventsToo(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        // Registered in the wrong order on purpose: priority, not registration, decides who runs last.
        plugins.loadSource("order.js",
                "events.on('note', function (e) { e.getData().log += 'highest '; }, {priority: 'HIGHEST'});\n"
              + "events.on('note', function (e) { e.getData().log += 'lowest '; }, {priority: 'LOWEST'});\n"
              + "events.on('note', function (e) { e.getData().log += 'normal '; });\n"
              + "commands.register('fire', function (p, a) {\n"
              + "  p.sendMessage(events.emit('note', {log: ''}).getData().log);\n"
              + "});", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);
        cm.dispatch(player, "/fire");

        assertEquals("lowest normal highest ", conn.lastMessage,
                "a script cannot tell a custom name from a built-in one, so the option must mean the "
                        + "same thing on both");
    }

    @Test
    void everyBuiltInEventNameIsListable(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("names.js",
                "commands.register('names', function (p, a) {\n"
              + "  var n = events.names();\n"
              + "  p.sendMessage(n.length + ' ' + (n.indexOf('PlayerJoin') >= 0));\n"
              + "});", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);
        cm.dispatch(player, "/names");

        assertEquals(EventTypes.names().size() + " true", conn.lastMessage);
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
    void aNonBuiltinEventNameRegistersACustomListener(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        // A name that isn't a built-in event is now a custom-event channel (not an error): the plugin loads,
        // and no Java event bus listener is added for it.
        plugins.loadSource("custom.js", "events.on('NoSuchEvent', function(e){});", 1L);

        assertEquals(1, plugins.pluginNames().size(), "the plugin loaded — the name is a custom event");
        assertFalse(bus.hasListeners(PlayerChatEvent.class), "no core event listener was registered");
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
    void commandArgsArePrimitiveStringsSoStrictEqualityWorks(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        // args[0] must be a JS primitive string so `=== 'clear'` matches — not a String wrapper object
        // (which would make === fail and silently skip the branch — the /inv clear bug).
        plugins.loadSource("eq.js",
                "commands.register('eq', function(player, args) {\n"
              + "  if (args[0] === 'clear') player.setHealth(3);\n"
              + "});", 1L);

        CorePlayer player = newPlayer();
        player.setHealth(20);
        cm.dispatch(player, "/eq clear");
        assertEquals(3, player.getHealth(), "args[0] === 'clear' matched a primitive string");
    }

    @Test
    void theMenusGlobalBuildsAVirtualChestAScriptCanLayOut(@TempDir Path dir) {
        // No server here, so open() would no-op, but a menu can still be created and populated — proving
        // the `menus` global, ScriptMenu.setItem/getItem/size and the chainable API all reach the script.
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("shop.js",
                "var m = menus.create('Shop', 2);\n"        // 2 rows = 18 slots
              + "m.setItem(0, 264 << 4).setItem(1, 57 << 4, 3);\n"
              + "events.on('PlayerChat', function (e) {\n"
              + "  e.setMessage(m.size() + '|' + m.getItem(0) + '|' + m.getItem(1) + '|' + m.open(e.getPlayer()));\n"
              + "});", 1L);

        PlayerChatEvent event = new PlayerChatEvent(newPlayer(), "");
        bus.post(event);

        assertEquals("18|" + (264 << 4) + "|" + (57 << 4) + "|false", event.getMessage(),
                "size, items, and open() returning false without a live server");
    }

    @Test
    void aScriptCommandCanSupplyTabCompletion(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("kit.js",
                "commands.register({\n"
              + "  name: 'kit',\n"
              + "  execute: function (player, args) {},\n"
              + "  complete: function (player, args) { return ['starter', 'pvp', 'builder']; }\n"
              + "});", 1L);

        CorePlayer player = newPlayer();
        // On the first argument: the script's whole list, narrowed to the partial by the core.
        assertEquals(List.of("starter", "pvp", "builder"), cm.complete(player, "/kit "));
        assertEquals(List.of("pvp"), cm.complete(player, "/kit p"));
    }

    @Test
    void aScriptCommandWithoutCompleteOffersNothing(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("plain.js",
                "commands.register('plain', function (player, args) {});", 1L);

        assertTrue(cm.complete(newPlayer(), "/plain ").isEmpty(), "no completer, no suggestions");
        // But the label still completes.
        assertTrue(cm.complete(newPlayer(), "/pla").contains("/plain"));
    }

    @Test
    void aThrowingCompleterYieldsNoSuggestionsRatherThanBreakingTyping(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        plugins.loadSource("boomc.js",
                "commands.register({\n"
              + "  name: 'boomc', execute: function (p, a) {},\n"
              + "  complete: function (p, a) { throw new Error('nope'); }\n"
              + "});", 1L);

        assertTrue(cm.complete(newPlayer(), "/boomc ").isEmpty(), "a broken completer must not throw to the wire");
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

    @Test
    void customEventsFlowBetweenPluginsWithSharedDataAndCancel(@TempDir Path dir) {
        CommandManager cm = new CommandManager(null);
        PluginManager plugins = manager(new EventBus(), dir, cm);
        // Plugin A listens for a custom 'score' event and mutates the shared data; the second listener cancels.
        plugins.loadSource("a.js",
                "events.on('score', function(e) { e.getData().n = e.getData().n + 1; });\n"
              + "events.on('score', function(e) { e.getData().n = e.getData().n + 10;\n"
              + "                                  if (e.getData().n >= 5) e.cancel(); });", 1L);
        // Plugin B emits it (from a command) and reports the result the emitter reads back.
        plugins.loadSource("b.js",
                "commands.register('fire', function(p, a) {\n"
              + "  var r = events.emit('score', { n: 0 });\n"
              + "  p.sendMessage('n=' + r.getData().n + ' cancelled=' + r.isCancelled());\n"
              + "});", 1L);

        CapturingConnection conn = new CapturingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL);
        cm.dispatch(player, "/fire");
        assertEquals("n=11 cancelled=true", conn.lastMessage,
                "both listeners ran in order over shared data, and the cancel was read back");

        // Unloading the listener plugin tears its custom listeners down — the same emit now reaches nobody.
        plugins.unload("a.js");
        cm.dispatch(player, "/fire");
        assertEquals("n=0 cancelled=false", conn.lastMessage, "custom listeners were removed with their plugin");
    }

    @Test
    void emittingABuiltinEventNameIsRejected(@TempDir Path dir) {
        PluginManager plugins = manager(new EventBus(), dir);
        // events.emit on a built-in name throws (the core fires those) — so this script fails to load.
        plugins.loadSource("bad.js", "events.emit('PlayerChat', { x: 1 });", 1L);
        assertTrue(plugins.pluginNames().isEmpty(), "emitting a built-in name is refused");
    }

    @Test
    void theRealExamplePluginLoadsUnderTheSandbox(@TempDir Path dir) throws IOException {
        // Smoke-test the shipped plugins/example.js: it must parse and register cleanly under the sandbox —
        // this validates the `Packages.com.jedrock…` enum access and that every global (events, scheduler,
        // commands, packets, server) is in scope. Handler bodies don't run on load, only the registrations.
        Path example = Path.of("plugins/example.js");
        if (!Files.exists(example)) {
            example = Path.of("../plugins/example.js"); // surefire runs from the module dir
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(example), "plugins/example.js not found");

        CommandManager cm = new CommandManager(null);
        PacketTapRegistry taps = new PacketTapRegistry();
        PluginManager plugins = new PluginManager(new EventBus(), null, new Scheduler(), cm, taps, dir);
        plugins.loadSource("example.js", Files.readString(example), 1L);

        assertEquals(1, plugins.pluginNames().size(), "example.js loaded without a sandbox/parse error");
        assertNotNull(cm.get("test"), "it registered its /test command");
        assertNotNull(cm.get("bc"), "and the /broadcast alias");
        assertTrue(taps.hasTaps(), "and its packet taps");
    }

    @Test
    void aScriptPacketTapSeesAndCancelsInboundPackets(@TempDir Path dir) {
        PacketTapRegistry taps = new PacketTapRegistry();
        PluginManager plugins = manager(new EventBus(), dir, taps);
        // The tap cancels only 0x05, and only after reading getId()/getLength() — so a cancel proves it saw
        // the packet. A 2-byte 0x05 would otherwise be let through, so length is exercised too.
        plugins.loadSource("tap.js",
                "packets.onReceive(function(p) {\n"
              + "  if (p.getId() === 0x05 && p.getLength() === 2) p.cancel();\n"
              + "});", 1L);
        assertTrue(taps.hasTaps(), "the script registered an inbound tap");

        assertFalse(taps.dispatch(inbound(0x03, new byte[]{1, 2})), "0x03 passes through");
        assertFalse(taps.dispatch(inbound(0x05, new byte[]{9})), "0x05 with the wrong length passes through");
        assertTrue(taps.dispatch(inbound(0x05, new byte[]{1, 2})), "0x05 (len 2) was cancelled by the script");
    }

    @Test
    void reloadAndUnloadRemoveAScriptsPacketTaps(@TempDir Path dir) {
        PacketTapRegistry taps = new PacketTapRegistry();
        PluginManager plugins = manager(new EventBus(), dir, taps);

        plugins.loadSource("tap.js", "packets.onReceive(function(p) { p.cancel(); });", 1L);
        assertTrue(taps.hasTaps(), "tap registered");
        assertTrue(taps.dispatch(inbound(1, new byte[0])), "v1 cancels");

        // Reload with no tap: the old tap must be gone.
        plugins.loadSource("tap.js", "events.on('PlayerChat', function(e){});", 2L);
        assertFalse(taps.hasTaps(), "reload removed the tap");
        assertFalse(taps.dispatch(inbound(1, new byte[0])), "nothing cancels now");

        // Register again, then unload entirely.
        plugins.loadSource("tap2.js", "packets.onSend(function(p) { p.cancel(); });", 1L);
        assertTrue(taps.hasTaps());
        plugins.unload("tap2.js");
        assertFalse(taps.hasTaps(), "unload removed the outbound tap");
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
