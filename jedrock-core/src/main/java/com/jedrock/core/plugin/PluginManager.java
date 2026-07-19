package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.event.EventBus;
import com.jedrock.utils.JLogger;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

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

    private final ContextFactory contextFactory = new ScriptContextFactory();
    private final ReentrantLock scriptLock = new ReentrantLock();
    private final Map<String, ScriptPlugin> plugins = new LinkedHashMap<>();

    private final EventBus eventBus;
    private final Server server;
    private final Path pluginsDir;
    private volatile Thread watcher;

    public PluginManager(EventBus eventBus, Server server, Path pluginsDir) {
        this.eventBus = eventBus;
        this.server = server;
        this.pluginsDir = pluginsDir;
    }

    EventBus eventBus() {
        return eventBus;
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
                ScriptableObject.putProperty(scope, "console",
                        Context.javaToJS(new ScriptConsole(name), scope));

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

    /** Call a plugin's onDisable (if any) and drop its subscriptions. Caller holds the script lock. */
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
