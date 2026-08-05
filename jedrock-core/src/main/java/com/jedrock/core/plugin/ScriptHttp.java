package com.jedrock.core.plugin;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code http} global — the one way a script reaches outside this process.
 *
 * <pre>{@code
 *   http.post('https://discord.com/api/webhooks/…', {content: 'Alice joined'});
 *
 *   http.get('https://api.example.com/motd', function (res) {
 *       if (res.isOk()) { server.broadcast(res.getBody()); }
 *       else { console.warn('motd failed: ' + res.getError()); }
 *   });
 * }</pre>
 *
 * <p><b>There is no synchronous form, on purpose.</b> Every script callback here runs under one shared
 * lock and the tick event is posted from the game-loop thread, so a call that waited for a reply would
 * hold up every other plugin and, from a tick handler, the tick itself. A slow webhook would present as a
 * laggy server with nothing in the logs to say why. So a request is fired and forgotten, or fired with a
 * callback; there is no third option and no handle to wait on.
 *
 * <p><b>The callback arrives on an HTTP thread</b>, not the game loop. That is the same deal every other
 * callback here already makes — packet taps and most events arrive on network threads — and everything on
 * the script API is safe to call from one. If you want it on the loop, hand it to
 * {@code scheduler.run(...)}.
 *
 * <p>Off unless {@code plugins.http.enabled=true}. When it is off this global is not defined at all,
 * rather than defined and throwing: a script can then ask {@code typeof http === 'undefined'} and degrade,
 * which is more useful than a stack trace.
 *
 * <p>A failed request is a <em>result</em>, not an exception: {@code res.getError()} is non-null and
 * {@code isOk()} is false for a timeout, a refused host or a 500 alike. Making some failures throw and
 * others not would only mean a try/catch around every call.
 */
public final class ScriptHttp {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final ScriptHttpClient client;

    ScriptHttp(PluginManager manager, ScriptPlugin plugin, ScriptHttpClient client) {
        this.manager = manager;
        this.plugin = plugin;
        this.client = client;
    }

    // ===== The verbs =====

    public void get(String url) {
        send("GET", url, null, null, null);
    }

    public void get(String url, Function whenDone) {
        send("GET", url, null, null, whenDone);
    }

    /** Fire and forget — the shape a webhook usually wants. */
    public void post(String url, Object body) {
        send("POST", url, body, null, null);
    }

    public void post(String url, Object body, Function whenDone) {
        send("POST", url, body, null, whenDone);
    }

    public void put(String url, Object body, Function whenDone) {
        send("PUT", url, body, null, whenDone);
    }

    public void del(String url, Function whenDone) {
        send("DELETE", url, null, null, whenDone);
    }

    /**
     * The long form, when the short ones aren't enough:
     * {@code http.request({method: 'PATCH', url: …, body: {…}, headers: {…}}, fn)}.
     */
    public void request(Object options, Function whenDone) {
        Object unwrapped = ScriptJson.unwrap(options);
        if (!(unwrapped instanceof Scriptable opts)) {
            throw new IllegalArgumentException("http.request needs an object like {url: '…'}");
        }
        String url = string(opts, "url");
        if (url == null) {
            throw new IllegalArgumentException("http.request needs a url");
        }
        String method = string(opts, "method");
        send(method == null ? "GET" : method, url,
                ScriptableObject.getProperty(opts, "body"),
                ScriptableObject.getProperty(opts, "headers"), whenDone);
    }

    /** Whether a host would be allowed, without sending anything — for a script that wants to say so first. */
    public boolean isAllowed(String host) {
        return client.hostAllowed(host);
    }

    // ===== Plumbing =====

    private void send(String method, String url, Object body, Object headers, Function whenDone) {
        String payload = renderBody(body);
        Map<String, String> allHeaders = readHeaders(headers);
        // A JS object body is JSON, so say so unless the script already did. Guessing the content type of
        // a raw string would be worse than leaving it off.
        if (payload != null && !payload.isEmpty() && isObject(body)
                && allHeaders.keySet().stream().noneMatch(h -> h.equalsIgnoreCase("Content-Type"))) {
            allHeaders.put("Content-Type", "application/json");
        }
        client.send(method, url, payload, allHeaders, result -> {
            if (whenDone != null) {
                // Back through the same lock every other script callback goes through, and only if the
                // plugin is still the loaded one — a reply landing after a hot reload belongs to nobody.
                manager.callScriptCallback(plugin, whenDone, new Response(result, plugin.scope()));
            } else if (!result.ok()) {
                // Nobody asked, but a webhook that silently stopped working is worth one line.
                LOGGER.warn(plugin.name() + ": " + method + " " + url + " → "
                        + (result.error() != null ? result.error() : "HTTP " + result.status()));
            }
        });
    }

    private static final com.jedrock.utils.JLogger LOGGER =
            com.jedrock.utils.JLogger.getLogger("http");

    /** A JS object or array goes as JSON; anything else goes as its text. */
    private String renderBody(Object body) {
        Object value = ScriptJson.unwrap(body);
        if (value == null || value instanceof Undefined) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Scriptable s && !(value instanceof Function)) {
            return ScriptJson.stringify(plugin.scope(), s, "http body");
        }
        return value.toString();
    }

    private static boolean isObject(Object body) {
        Object value = ScriptJson.unwrap(body);
        return value instanceof Scriptable && !(value instanceof Function)
                && !(value instanceof CharSequence);
    }

    private static Map<String, String> readHeaders(Object headers) {
        Map<String, String> out = new LinkedHashMap<>();
        Object value = ScriptJson.unwrap(headers);
        if (!(value instanceof Scriptable table) || value instanceof Function) {
            return out;
        }
        for (Object id : ScriptableObject.getPropertyIds(table)) {
            String name = String.valueOf(id);
            Object header = ScriptableObject.getProperty(table, name);
            if (header != null && !(header instanceof Undefined)) {
                out.put(name, header.toString());
            }
        }
        return out;
    }

    private static String string(Scriptable options, String key) {
        Object value = ScriptableObject.getProperty(options, key);
        return value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND
                ? null : value.toString();
    }

    /** What a script is handed back. A bean, so it reads like every other object in this API. */
    public static final class Response {
        private final ScriptHttpClient.Result result;
        /** The plugin's own scope, so {@code json()} parses into the world the script actually lives in. */
        private final Scriptable scope;

        Response(ScriptHttpClient.Result result, Scriptable scope) {
            this.result = result;
            this.scope = scope;
        }

        /** The HTTP status, or 0 if the request never got that far. */
        public int getStatus() {
            return result.status();
        }

        public String getBody() {
            return result.body();
        }

        /** Why it failed, or {@code null} if it didn't — a timeout, a refused host, a network error. */
        public String getError() {
            return result.error();
        }

        /** True for 2xx with no error. */
        public boolean isOk() {
            return result.ok();
        }

        public String getHeader(String name) {
            for (Map.Entry<String, String> header : result.headers().entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        /** The body parsed as JSON, or {@code null} if it isn't. Convenience, since most bodies are. */
        public Object json() {
            try {
                return ScriptJson.parse(scope, result.body());
            } catch (RuntimeException e) {
                return null; // a body that isn't JSON is a thing that happens, not an error to throw on
            }
        }

        @Override
        public String toString() {
            return result.error() != null ? "Response[failed: " + result.error() + "]"
                    : "Response[" + result.status() + ", " + result.body().length() + " chars]";
        }
    }
}
