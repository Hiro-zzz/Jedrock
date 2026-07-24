package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code storage} global end-to-end: a script writes, the store is written to disk and read back by a
 * <em>different</em> manager — the closest a test gets to a restart — and the script sees its own values
 * again. Also covers the isolation that makes the API safe to hand out (per plugin, per player) and the
 * refusals that keep nonsense off disk.
 */
class ScriptStorageTest {

    private PluginManager manager(EventBus bus, Path dir) {
        return new PluginManager(bus, null, new Scheduler(), new CommandManager(null),
                new PacketTapRegistry(), dir);
    }

    /** Run a script and read one value back out through a chat event, which the test can inspect. */
    private static String report(PluginManager plugins, EventBus bus, String name, String body) {
        plugins.loadSource(name, body, 1L);
        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);
        return event.getMessage();
    }

    @Test
    void valuesSurviveAServerRestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("plugin-storage.jdb");

        // First run: the script counts its own starts and stores a structured value.
        EventBus firstBus = new EventBus();
        PluginManager first = manager(firstBus, dir);
        report(first, firstBus, "counter.js",
                "storage.set('runs', storage.get('runs', 0) + 1);\n"
                        + "storage.set('home', {x: 10, y: 64, z: -3});\n"
                        + "events.on('PlayerChat', function (e) { e.setMessage('runs=' + storage.get('runs')); });");
        assertTrue(first.storage().isDirty(), "the script wrote something");
        first.storage().save(file);
        assertFalse(first.storage().isDirty(), "saving clears the flag");
        assertTrue(Files.size(file) > 0);

        // Second run: a brand new manager and store, loading the file the first one left.
        EventBus secondBus = new EventBus();
        PluginManager second = manager(secondBus, dir);
        second.storage().load(file);

        String reported = report(second, secondBus, "counter.js",
                "storage.set('runs', storage.get('runs', 0) + 1);\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  var home = storage.get('home');\n"
                        + "  e.setMessage('runs=' + storage.get('runs') + ' y=' + home.y);\n"
                        + "});");

        assertEquals("runs=2 y=64", reported,
                "the count carried over and the object came back as an object, not its JSON text");
    }

    @Test
    void everyKindOfValueRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s.jdb");
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("kinds.js",
                "storage.set('name', 'Alice');\n"
                        + "storage.set('score', 12.5);\n"
                        + "storage.set('admin', true);\n"
                        + "storage.set('scene', [{id: 1}, {id: 2}]);", 1L);
        plugins.storage().save(file);

        EventBus bus2 = new EventBus();
        PluginManager reloaded = manager(bus2, dir);
        reloaded.storage().load(file);

        String reported = report(reloaded, bus2, "kinds.js",
                "events.on('PlayerChat', function (e) {\n"
                        + "  e.setMessage(storage.get('name') + '|' + storage.get('score') + '|'\n"
                        + "    + storage.get('admin') + '|' + storage.get('scene').length\n"
                        + "    + '|' + storage.get('scene')[1].id);\n"
                        + "});");

        assertEquals("Alice|12.5|true|2|2", reported);
    }

    @Test
    void aStringComesBackAsARealJsString(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        // The Rhino trap that bites Java-returned strings must not bite the storage API — a stored
        // string is the single most likely thing a script compares with ===.
        String reported = report(plugins, bus, "cmp.js",
                "storage.set('mode', 'hard');\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  e.setMessage('strict=' + (storage.get('mode') === 'hard'));\n"
                        + "});");

        assertEquals("strict=true", reported);
    }

    @Test
    void twoPluginsDoNotShareKeys(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("a.js", "storage.set('count', 1);", 1L);
        plugins.loadSource("b.js", "storage.set('count', 2);", 1L);

        String fromA = report(plugins, bus, "a2.js", "");  // no-op, keeps the helper honest
        assertEquals("", fromA);

        assertEquals(1.0, plugins.storage().get("a.js", "count").number());
        assertEquals(2.0, plugins.storage().get("b.js", "count").number());
    }

    @Test
    void perPlayerViewsAreSeparateFromThePluginsOwnKeys(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        String reported = report(plugins, bus, "homes.js",
                "var uuid = '11111111-2222-3333-4444-555555555555';\n"
                        + "var other = '99999999-2222-3333-4444-555555555555';\n"
                        + "storage.set('kills', 'plugin-wide');\n"
                        + "storage.forPlayer(uuid).set('kills', 7);\n"
                        + "storage.forPlayer(other).set('kills', 3);\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  e.setMessage(storage.get('kills') + '|' + storage.forPlayer(uuid).get('kills')\n"
                        + "    + '|' + storage.forPlayer(other).get('kills')\n"
                        + "    + '|' + storage.keys().length);\n"
                        + "});");

        assertEquals("plugin-wide|7|3|1", reported,
                "each player has their own bucket, and none of them show up in the plugin's own keys");
    }

    /**
     * The exact shape {@code /seen} in {@code plugins/example.js} uses — a per-player record built from
     * {@code Date.now()} and read back field by field. The reference plugin isn't executed by any test, so
     * the pattern it teaches is pinned here instead.
     */
    @Test
    void theExamplePluginsPerPlayerRecordPatternWorks(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        String reported = report(plugins, bus, "seen.js",
                "var mine = storage.forPlayer('11111111-2222-3333-4444-555555555555');\n"
                        + "mine.set('lastSeen', {when: Date.now(), x: 10, y: 64, z: -3});\n"
                        + "mine.set('visits', mine.get('visits', 0) + 1);\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  var last = mine.get('lastSeen');\n"
                        + "  e.setMessage('fresh=' + (Date.now() - last.when < 60000)\n"
                        + "    + ' z=' + last.z + ' visits=' + mine.get('visits'));\n"
                        + "});");

        assertEquals("fresh=true z=-3 visits=1", reported);
    }

    @Test
    void aHotReloadKeepsTheData(@TempDir Path dir) throws IOException {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        Path file = dir.resolve("keep.js");
        Files.writeString(file, "storage.set('kept', 'yes');");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1000L));
        plugins.load(file);

        // Edit and reload: listeners, tasks and entities are torn down — the data must not be.
        Files.writeString(file,
                "events.on('PlayerChat', function (e) { e.setMessage('kept=' + storage.get('kept')); });");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(2000L));
        plugins.load(file);

        PlayerChatEvent event = new PlayerChatEvent(null, "");
        bus.post(event);
        assertEquals("kept=yes", event.getMessage(), "data belongs to the plugin name, not the instance");
    }

    @Test
    void removingAndClearingWork(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        String reported = report(plugins, bus, "gone.js",
                "storage.set('a', 1); storage.set('b', 2);\n"
                        + "var removed = storage.remove('a');\n"
                        + "var missing = storage.remove('nope');\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  e.setMessage(removed + '|' + missing + '|' + storage.has('a') + '|' + storage.size());\n"
                        + "});");

        assertEquals("true|false|false|1", reported);

        plugins.storage().clear("gone.js");
        assertEquals(0, plugins.storage().size("gone.js"));
    }

    @Test
    void settingNullForgetsTheKey(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        String reported = report(plugins, bus, "nulls.js",
                "storage.set('a', 'here');\n"
                        + "storage.set('a', null);\n"
                        + "events.on('PlayerChat', function (e) {\n"
                        + "  e.setMessage('has=' + storage.has('a') + ' fallback=' + storage.get('a', 'none'));\n"
                        + "});");

        assertEquals("has=false fallback=none", reported);
    }

    @Test
    void aFunctionIsRefusedRatherThanQuietlyPersisted(@TempDir Path dir) {
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);

        // The script throws; the manager catches and logs it, and nothing reaches the store.
        plugins.loadSource("bad.js", "storage.set('fn', function () { return 1; });", 1L);

        assertEquals(0, plugins.storage().size("bad.js"), "nothing nonsensical was stored");
    }

    @Test
    void anUnwrittenStoreIsNeverRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("s.jdb");
        EventBus bus = new EventBus();
        PluginManager plugins = manager(bus, dir);
        plugins.loadSource("w.js", "storage.set('k', 'v');", 1L);
        plugins.storage().saveIfDirty(file);
        long firstWrite = Files.getLastModifiedTime(file).toMillis();

        plugins.storage().saveIfDirty(file); // nothing changed since

        assertEquals(firstWrite, Files.getLastModifiedTime(file).toMillis(), "the file was left alone");
        assertFalse(plugins.storage().isDirty());
    }

    @Test
    void aStoreWithNoKeysWritesAndReadsBackEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.jdb");
        PluginStorage store = new PluginStorage();
        store.save(file);

        PluginStorage reloaded = new PluginStorage();
        reloaded.load(file);

        assertEquals(0, reloaded.totalKeys());
        assertFalse(reloaded.isDirty());
    }

    @Test
    void aMissingFileIsAFirstRunNotAnError(@TempDir Path dir) throws IOException {
        PluginStorage store = new PluginStorage();
        store.load(dir.resolve("never-written.jdb"));
        assertEquals(0, store.totalKeys());
    }

    @Test
    void aFileThatIsNotAStoreIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("junk.jdb");
        Files.writeString(file, "this is not a plugin store at all");

        PluginStorage store = new PluginStorage();
        assertThrowsIOException(() -> store.load(file));
    }

    /** A long value must survive: writeUTF's 64 KB cap is exactly what the format avoids. */
    @Test
    void aValueLargerThanSixtyFourKilobytesRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("big.jdb");
        String big = "x".repeat(100_000);
        PluginStorage store = new PluginStorage();
        store.put("big.js", "blob", PluginStorage.Value.of(big));
        store.save(file);

        PluginStorage reloaded = new PluginStorage();
        reloaded.load(file);

        assertEquals(big, reloaded.get("big.js", "blob").text());
        assertNotEquals(0, Files.size(file));
    }

    private static void assertThrowsIOException(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected an IOException");
        } catch (IOException expected) {
            // what we wanted
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
