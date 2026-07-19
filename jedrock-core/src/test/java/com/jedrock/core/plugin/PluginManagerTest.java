package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the scripting layer without a network: load a JS source, post real events, and prove
 * the Rhino binding runs the handler, cancels through it, hot-reloads, and tears down cleanly.
 */
class PluginManagerTest {

    private PluginManager manager(EventBus bus, Path dir) {
        return new PluginManager(bus, null, dir); // these scripts don't touch `server`
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
}
