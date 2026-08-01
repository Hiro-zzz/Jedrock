package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

/**
 * The {@code storage} object a script sees — the one thing a plugin keeps when the server stops.
 *
 * <pre>
 *   storage.set('spawnCount', storage.get('spawnCount', 0) + 1);
 *   storage.forPlayer(player).set('home', {x: 10, y: 64, z: -3});   // objects and arrays work
 *   if (storage.has('greeted')) …
 * </pre>
 *
 * <p>Each plugin gets its own view, so two scripts can both keep a {@code "count"} without colliding, and
 * {@link #forPlayer} narrows that further to one player — the shape most script state actually has.
 * Values survive a hot-reload as well as a restart: the data belongs to the plugin's <em>name</em>, not to
 * the loaded instance, so editing a script never costs it its memory.
 *
 * <p>Strings, numbers and booleans are stored as themselves. A JS object or array is stored as JSON —
 * rendered through the script's own {@code JSON.stringify} and handed back through {@code JSON.parse}, so
 * what comes out is a real JS value and not a string that looks like one. Anything else (a function, a
 * Java object) is refused loudly rather than silently persisted as nonsense. Setting {@code null} or
 * {@code undefined} removes the key — there is no difference on disk between "absent" and "nothing".
 *
 * <p>Writes are held in memory and flushed by the same autosave that persists the world, plus once at
 * shutdown; a store nobody wrote to is never rewritten.
 */
public final class ScriptStorage {

    private final PluginStorage store;
    private final String bucket;
    /** The plugin's scope — where {@code JSON} is found for structured values. */
    private final Scriptable scope;

    ScriptStorage(PluginStorage store, String bucket, Scriptable scope) {
        this.store = store;
        this.bucket = bucket;
        this.scope = scope;
    }

    /** The stored value for {@code key}, or {@code null} if there isn't one. */
    public Object get(String key) {
        return get(key, null);
    }

    /** The stored value for {@code key}, or {@code fallback} if there isn't one. */
    public Object get(String key, Object fallback) {
        PluginStorage.Value value = store.get(bucket, requireKey(key));
        if (value == null) {
            return fallback instanceof Undefined ? null : fallback;
        }
        return switch (value.kind()) {
            case STRING -> value.text();
            case NUMBER -> value.number();
            case BOOLEAN -> value.flag();
            case JSON -> parseJson(value.text());
        };
    }

    /**
     * Store {@code value} under {@code key}. A {@code null} or {@code undefined} removes the key instead —
     * storing "nothing" and having nothing stored are the same thing here.
     */
    public void set(String key, Object value) {
        String k = requireKey(key);
        Object unwrapped = unwrap(value);
        if (unwrapped == null || unwrapped instanceof Undefined) {
            store.remove(bucket, k);
            return;
        }
        store.put(bucket, k, toValue(k, unwrapped));
    }

    /** Whether anything is stored under {@code key}. */
    public boolean has(String key) {
        return store.get(bucket, requireKey(key)) != null;
    }

    /** Forget {@code key}. @return {@code true} if there was something to forget */
    public boolean remove(String key) {
        return store.remove(bucket, requireKey(key));
    }

    /** Every key this plugin has stored, as a plain array. */
    public String[] keys() {
        return store.keys(bucket);
    }

    /** How many keys this plugin has stored. */
    public int size() {
        return store.size(bucket);
    }

    /** Forget everything this plugin stored. The per-player views are separate and are not cleared. */
    public void clear() {
        store.clear(bucket);
    }

    /**
     * A view of the same store narrowed to one player, so {@code storage.forPlayer(p).set('kills', 3)}
     * can't collide with the plugin's own keys or with another player's. Keyed by uuid, so it follows the
     * player across a rename.
     */
    public ScriptStorage forPlayer(Object player) {
        Object unwrapped = unwrap(player);
        String id;
        Player known = ScriptWrapFactory.unwrapPlayer(unwrapped);   // the script contract, or a raw player
        if (known != null) {
            id = known.getUniqueId().toString();
        } else if (unwrapped instanceof CharSequence s) {
            id = s.toString(); // a uuid (or any name the script wants to scope by), passed as text
        } else {
            throw new IllegalArgumentException(
                    "storage.forPlayer expects a player or a uuid string, got " + describe(unwrapped));
        }
        return new ScriptStorage(store, bucket + "#" + id, scope);
    }

    // ===== Conversion =====

    private PluginStorage.Value toValue(String key, Object value) {
        if (value instanceof CharSequence s) {
            return PluginStorage.Value.of(s.toString());
        }
        if (value instanceof Number n) {
            return PluginStorage.Value.of(n.doubleValue());
        }
        if (value instanceof Boolean b) {
            return PluginStorage.Value.of(b);
        }
        if (value instanceof Scriptable s && !(value instanceof Function)) {
            return PluginStorage.Value.json(stringifyJson(s));
        }
        throw new IllegalArgumentException("storage.set('" + key + "', …) can keep a string, number,"
                + " boolean, object or array — not " + describe(value));
    }

    /** Render a JS object or array through the script's own {@code JSON.stringify}. */
    private String stringifyJson(Scriptable value) {
        return ScriptJson.stringify(scope, value, "storage.set");
    }

    /** Hand a stored JSON payload back as a real JS value. */
    private Object parseJson(String text) {
        return ScriptJson.parse(scope, text);
    }

    /** Rhino hands Java values in wrapped; unwrap once so the type checks above see the real thing. */
    private static Object unwrap(Object value) {
        return ScriptJson.unwrap(value);
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("storage keys must be non-empty");
        }
        return key;
    }

    private static String describe(Object value) {
        return ScriptJson.describe(value);
    }
}
