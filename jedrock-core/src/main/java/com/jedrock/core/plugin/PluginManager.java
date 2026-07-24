package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.command.CommandSender;
import com.jedrock.api.event.EventBus;
import com.jedrock.core.command.Command;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.net.PacketEvent;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.gameloop.Scheduler;
import com.jedrock.utils.JLogger;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Loads and runs JavaScript plugins — the scripting layer, on a Rhino ({@code rhino-runtime}) backend. Each
 * {@code .js} file in the plugins folder is a plugin: it runs once on load, wiring up event listeners via
 * {@code events.on(...)}, and can define an {@code onDisable()} the manager calls on unload. Scripts are
 * <b>hot-reloadable</b> — a changed file is torn down and re-run in place, its old listeners removed.
 *
 * <p><b>Threading.</b> Rhino is not thread-safe and events post from several threads (network I/O, the game
 * loop), so every script execution — load, a listener callback, {@code onDisable} — is serialized under one
 * lock. Scripts therefore run effectively single-threaded; a listener must stay quick, since it can hold up
 * event dispatch elsewhere.
 *
 * <p><b>Sandbox.</b> A {@link ClassShutter} keeps scripts to Jedrock's own classes and a conservative slice
 * of the JDK, blocking the obvious escape hatches ({@code Runtime}, {@code ProcessBuilder}, reflection,
 * {@code java.io} / {@code java.nio}). This is guard-rails against footguns, <em>not</em> a security
 * boundary — plugins are code the operator chose to install, and a determined script can still find a way
 * out, exactly as a Bukkit plugin could.
 */
public final class PluginManager {

    private static final JLogger LOGGER = JLogger.getLogger("plugin");

    /** Best-effort sandbox: allow our own code and a safe JDK slice; deny the rest. */
    private static final ClassShutter SHUTTER = fullName -> {
        // The obvious escape hatches, denied even though they live under an allowed prefix.
        if (fullName.equals("java.lang.Runtime")
                || fullName.equals("java.lang.ProcessBuilder")
                || fullName.equals("java.lang.Process")
                || fullName.equals("java.lang.System")
                || fullName.equals("java.lang.Thread")
                || fullName.equals("java.lang.ClassLoader")
                || fullName.startsWith("java.lang.reflect.")) {
            return false;
        }
        return fullName.startsWith("com.jedrock.")
                || fullName.startsWith("java.lang.")
                || fullName.startsWith("java.util.")
                || fullName.startsWith("java.time.")
                || fullName.startsWith("java.math.")
                || fullName.startsWith("java.text.");
    };

    /**
     * A tiny prelude evaluated in every script's scope before its own source, defining the familiar
     * millisecond-based timer globals in terms of the tick-based {@code scheduler}. Its own eval, so a
     * script's error line numbers stay honest. 50 ms == 1 tick; every delay is floored to at least one tick.
     */
    private static final String TIMER_PRELUDE =
            "function setTimeout(fn, ms) { return scheduler.runLater(fn, Math.max(1, Math.round((ms || 0) / 50))); }\n"
            + "function setInterval(fn, ms) { var t = Math.max(1, Math.round((ms || 0) / 50)); return scheduler.runTimer(fn, t, t); }\n"
            + "function clearTimeout(h) { if (h) h.cancel(); }\n"
            + "function clearInterval(h) { if (h) h.cancel(); }\n";

    private final ContextFactory contextFactory = new ScriptContextFactory();
    private final ReentrantLock scriptLock = new ReentrantLock();
    private final Map<String, ScriptPlugin> plugins = new LinkedHashMap<>();
    /** Channel for script-defined custom events (events.emit / events.on for a non-built-in name). */
    private final CustomEventBus customEvents = new CustomEventBus();

    private final EventBus eventBus;
    private final Server server;
    private final Scheduler scheduler;
    private final CommandManager commandManager;
    private final PacketTapRegistry packetTaps;
    private final Path pluginsDir;
    private volatile Thread watcher;

    public PluginManager(EventBus eventBus, Server server, Scheduler scheduler, CommandManager commandManager,
                         PacketTapRegistry packetTaps, Path pluginsDir) {
        this.eventBus = eventBus;
        this.server = server;
        this.scheduler = scheduler;
        this.commandManager = commandManager;
        this.packetTaps = packetTaps;
        this.pluginsDir = pluginsDir;
    }

    EventBus eventBus() {
        return eventBus;
    }

    Scheduler scheduler() {
        return scheduler;
    }

    Server server() {
        return server;
    }

    /** How many entities a loaded plugin currently owns (-1 if it isn't loaded). For tests and diagnostics. */
    public int entityCount(String pluginName) {
        ScriptPlugin plugin = plugins.get(pluginName);
        return plugin == null ? -1 : plugin.entities().size();
    }

    CommandManager commandManager() {
        return commandManager;
    }

    PacketTapRegistry packetTaps() {
        return packetTaps;
    }

    CustomEventBus customEvents() {
        return customEvents;
    }

    /**
     * Start a background daemon that polls the plugins folder for changes and hot-reloads them, every
     * {@code intervalMillis}. Deliberately <em>off</em> the game-loop thread — the poll blocks on directory
     * and {@code stat} I/O, which must never sit in a tick's budget. Idempotent.
     */
    public void startWatching(long intervalMillis) {
        if (watcher != null) {
            return;
        }
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    return; // asked to stop
                }
                try {
                    reloadChanged();
                } catch (RuntimeException e) {
                    LOGGER.error("Plugin watcher failed: " + e.getMessage());
                }
            }
        }, "jedrock-plugin-watch");
        thread.setDaemon(true);
        watcher = thread;
        thread.start();
    }

    /** Load every {@code .js} in the plugins folder (creating the folder if absent). */
    public void loadAll() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            LOGGER.warn("Could not create plugins folder " + pluginsDir + ": " + e.getMessage());
            return;
        }
        List<Path> files = listScripts();
        if (files.isEmpty()) {
            LOGGER.info("No plugins in " + pluginsDir.toAbsolutePath() + " (drop a .js there to add one)");
            return;
        }
        for (Path file : files) {
            load(file);
        }
        LOGGER.info("Loaded " + plugins.size() + " plugin(s) from " + pluginsDir.toAbsolutePath());
    }

    /** Load (or reload) one script file. Errors are logged, never fatal. */
    public void load(Path file) {
        String name = file.getFileName().toString();
        String source;
        long modified;
        try {
            source = Files.readString(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            LOGGER.error("Could not read plugin " + name + ": " + e.getMessage());
            return;
        }
        loadSource(name, source, modified);
    }

    /**
     * Load a script from source directly (the file path is only the display name). Exposed for tests and
     * shared by {@link #load}. If a plugin of this name is already loaded, it is unloaded first.
     */
    public void loadSource(String name, String source, long modified) {
        scriptLock.lock();
        try {
            ScriptPlugin existing = plugins.remove(name);
            if (existing != null) {
                teardown(existing);
            }
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = cx.initStandardObjects();
                ScriptPlugin plugin = new ScriptPlugin(name, scope, modified);
                ScriptableObject.putProperty(scope, "server", Context.javaToJS(server, scope));
                ScriptableObject.putProperty(scope, "events",
                        Context.javaToJS(new ScriptEvents(this, plugin), scope));
                ScriptableObject.putProperty(scope, "scheduler",
                        Context.javaToJS(new ScriptScheduler(this, plugin), scope));
                ScriptableObject.putProperty(scope, "commands",
                        Context.javaToJS(new ScriptCommands(this, plugin), scope));
                ScriptableObject.putProperty(scope, "packets",
                        Context.javaToJS(new ScriptPackets(this, plugin), scope));
                if (server != null) { // headless tests run without a server (and thus without a world)
                    ScriptableObject.putProperty(scope, "world",
                            Context.javaToJS(new ScriptWorld(server.getDefaultWorld()), scope));
                    ScriptableObject.putProperty(scope, "entities",
                            Context.javaToJS(new ScriptEntities(this, plugin), scope));
                }
                ScriptableObject.putProperty(scope, "console",
                        Context.javaToJS(new ScriptConsole(name), scope));

                // Define setTimeout/setInterval on top of `scheduler`, in its own eval so the script's own
                // line numbers stay correct, then run the script itself.
                cx.evaluateString(scope, TIMER_PRELUDE, "<jedrock-timers>", 1, null);
                cx.evaluateString(scope, source, name, 1, null);

                Object onDisable = scope.get("onDisable", scope);
                if (onDisable instanceof Function fn) {
                    plugin.setOnDisable(fn);
                }
                plugins.put(name, plugin);
                LOGGER.info("Loaded plugin " + name + " (" + plugin.subscriptions().size() + " listener(s))");
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + name + " failed to load: " + e.getMessage());
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Reload any plugin whose file changed since it was loaded, and load any newly-added file. Called on a
     * timer for hot reload. A deleted file's plugin is unloaded.
     */
    public void reloadChanged() {
        List<Path> files = listScripts();
        // New or modified files.
        for (Path file : files) {
            String name = file.getFileName().toString();
            long modified;
            try {
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                continue;
            }
            ScriptPlugin loaded = plugins.get(name);
            if (loaded == null || loaded.lastModified() != modified) {
                LOGGER.info((loaded == null ? "Loading new plugin " : "Reloading plugin ") + name);
                load(file);
            }
        }
        // Files that disappeared: unload their plugins.
        List<String> onDisk = files.stream().map(f -> f.getFileName().toString()).toList();
        for (String name : new ArrayList<>(plugins.keySet())) {
            if (!onDisk.contains(name)) {
                LOGGER.info("Unloading removed plugin " + name);
                unload(name);
            }
        }
    }

    /** Unload one plugin by name (calls its {@code onDisable} and removes its listeners). */
    public void unload(String name) {
        scriptLock.lock();
        try {
            ScriptPlugin plugin = plugins.remove(name);
            if (plugin != null) {
                teardown(plugin);
            }
        } finally {
            scriptLock.unlock();
        }
    }

    /** Unload every plugin and stop the hot-reload watcher — called on shutdown. */
    public void unloadAll() {
        Thread thread = watcher;
        if (thread != null) {
            watcher = null;
            thread.interrupt();
        }
        scriptLock.lock();
        try {
            for (ScriptPlugin plugin : plugins.values()) {
                teardown(plugin);
            }
            plugins.clear();
        } finally {
            scriptLock.unlock();
        }
    }

    /** The names of the loaded plugins, for the console command. */
    public List<String> pluginNames() {
        scriptLock.lock();
        try {
            return new ArrayList<>(plugins.keySet());
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Invoke a script event handler. Called from the event bus (any thread), serialized under the script
     * lock and wrapped in a Rhino context. A handler that throws is logged and swallowed so one bad script
     * can't break event dispatch.
     */
    void callHandler(ScriptPlugin plugin, Function handler, Object event) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                handler.call(cx, scope, scope, new Object[]{Context.javaToJS(event, scope)});
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + plugin.name() + " listener threw: " + e.getMessage());
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Invoke an entity callback — a per-tick brain or an interaction — with the entity as the first
     * argument and, for an interaction, the player as the second. Same script lock, same Rhino context
     * and same swallow-and-log as {@link #callHandler}, so one misbehaving entity can't stall the tick
     * loop or break the others.
     */
    void callEntityHandler(ScriptPlugin plugin, Function handler, ScriptEntity entity, Object second) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                Object[] args = second == null
                        ? new Object[]{Context.javaToJS(entity, scope)}
                        : new Object[]{Context.javaToJS(entity, scope), Context.javaToJS(second, scope)};
                handler.call(cx, scope, scope, args);
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + plugin.name() + " entity handler threw: " + e.getMessage());
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Run a shape-helper placement callback with a position and its index, returning whatever the
     * script spawned there. Unlike the fire-and-forget handlers this one has a result, so a throw is
     * logged and reported as "nothing placed" rather than silently losing the whole shape.
     */
    Object callPlacement(ScriptPlugin plugin, Function place, double x, double y, double z, int index) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                Object result = place.call(cx, scope, scope, new Object[]{x, y, z, index});
                // A Java object handed back from JS arrives wrapped (NativeJavaObject); the caller
                // wants the entity itself, so unwrap before it leaves the script boundary.
                return result instanceof Wrapper wrapper ? wrapper.unwrap() : result;
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + plugin.name() + " placement callback threw: " + e.getMessage());
            return null;
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Run a no-argument script callback that a scheduled task fired. Mirrors {@link #callHandler} — same
     * script lock, same Rhino context, same swallow-and-log — but for a {@code scheduler} task. When
     * {@code oneShotHandle} is non-null the task was a one-shot: drop it from the plugin now it has fired,
     * so a busy script's task list doesn't grow without bound. Removal runs under the lock, as does every
     * other mutation of that list, so it is safe.
     */
    void runScheduled(ScriptPlugin plugin, Function fn, Scheduler.Task oneShotHandle) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                fn.call(cx, scope, scope, new Object[0]);
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + plugin.name() + " scheduled task threw: " + e.getMessage());
        } finally {
            if (oneShotHandle != null) {
                plugin.removeTask(oneShotHandle);
            }
            scriptLock.unlock();
        }
    }

    /**
     * Run a script command handler with the sender (as an api {@code Player}) and the raw args. Serialized
     * under the script lock in a Rhino context like every other script call — but, unlike {@link #callHandler}
     * and {@link #runScheduled}, a thrown error is <b>not</b> swallowed: it propagates to
     * {@link com.jedrock.core.command.CommandManager#dispatch}, which logs it and tells the sender the command
     * failed. That is the command contract, and it keeps errors visible to whoever ran the command.
     */
    void callCommand(ScriptPlugin plugin, Function handler, CommandSender sender, String[] args) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                // Build a JS array of PRIMITIVE strings. A java.lang.String IS Rhino's representation of a JS
                // primitive string, so putting the raw args into an Object[] gives `args.length`, `args[0]`,
                // `args.join(' ')`, `parseInt(args[0])` AND strict `args[0] === 'x'` — all natural. (Passing a
                // typed String[] instead wraps it as a Java array, handing elements back as Java objects;
                // wrapping each as `new String()` makes a String OBJECT, which breaks `===` against a literal.)
                Object[] jsElements = new Object[args.length];
                System.arraycopy(args, 0, jsElements, 0, args.length);
                Scriptable jsArgs = cx.newArray(scope, jsElements);
                handler.call(cx, scope, scope,
                        new Object[]{Context.javaToJS(sender, scope), jsArgs});
            } finally {
                Context.exit();
            }
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Run a script packet tap with the {@link PacketEvent}. Serialized under the script lock in a Rhino
     * context like every other script call; a throwing tap is swallowed and logged (it must never break the
     * wire — and a tap that throws does not cancel the packet). The tap may call {@code event.cancel()}.
     */
    void callPacketTap(ScriptPlugin plugin, Function handler, PacketEvent event) {
        scriptLock.lock();
        try {
            Context cx = contextFactory.enterContext();
            try {
                Scriptable scope = plugin.scope();
                handler.call(cx, scope, scope, new Object[]{Context.javaToJS(event, scope)});
            } finally {
                Context.exit();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Plugin " + plugin.name() + " packet tap threw: " + e.getMessage());
        } finally {
            scriptLock.unlock();
        }
    }

    /**
     * Call a plugin's onDisable (if any), cancel its scheduled tasks, unregister its commands, remove its
     * packet taps and drop its subscriptions. Caller holds the script lock.
     */
    private void teardown(ScriptPlugin plugin) {
        Function onDisable = plugin.onDisable();
        if (onDisable != null) {
            Context cx = contextFactory.enterContext();
            try {
                onDisable.call(cx, plugin.scope(), plugin.scope(), new Object[0]);
            } catch (RuntimeException e) {
                LOGGER.error("Plugin " + plugin.name() + " onDisable threw: " + e.getMessage());
            } finally {
                Context.exit();
            }
        }
        for (Scheduler.Task task : plugin.tasks()) {
            task.cancel();
        }
        // Despawn the script's entities — a reloaded script must not inherit bodies whose brains
        // (tick and interaction callbacks) belong to the torn-down scope.
        for (ScriptEntity entity : plugin.entities()) {
            entity.remove();
        }
        for (Command command : plugin.commands()) {
            commandManager.unregister(command);
        }
        for (PacketTapRegistry.Registration registration : plugin.packetTaps()) {
            registration.remove();
        }
        for (CustomEventBus.Registration registration : plugin.customListeners()) {
            registration.remove();
        }
        for (EventBus.Subscription subscription : plugin.subscriptions()) {
            subscription.remove();
        }
    }

    private List<Path> listScripts() {
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A context factory that pins the language level, interpreted mode, and the sandbox on every context. */
    private static final class ScriptContextFactory extends ContextFactory {
        @Override
        protected void onContextCreated(Context cx) {
            cx.setLanguageVersion(Context.VERSION_ES6); // arrow functions, let/const, template literals
            cx.setOptimizationLevel(-1);                // interpret, don't generate a class per script (hot reload)
            cx.setClassShutter(SHUTTER);
            super.onContextCreated(cx);
        }
    }
}
