package com.jedrock.core.plugin;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Turning a JS value into text and back, through the <em>script's own</em> {@code JSON}.
 *
 * <p>Two things a script may hand the server now outlive the script: what a plugin keeps in
 * {@code storage}, and what a stack carries as its own state. Both are strings on disk and both want the
 * same courtesy — an object saved as an object comes back as an object, not as a string that looks like
 * one. Using the script's {@code JSON.stringify} rather than a Java serializer is what makes that true:
 * the value is rendered by the same engine that will parse it.
 *
 * <p>Off a script thread there is no scope to parse into, and the stored text is then the honest answer.
 */
final class ScriptJson {

    private ScriptJson() {}

    /** Render a JS object or array to JSON text. {@code what} names the caller in any error raised. */
    static String stringify(Scriptable scope, Scriptable value, String what) {
        Context cx = Context.getCurrentContext();
        if (cx == null) {
            throw new IllegalStateException(what + " of an object must run on a script thread");
        }
        Object json = ScriptableObject.getProperty(scope, "JSON");
        if (!(json instanceof Scriptable jsonObj)
                || !(ScriptableObject.getProperty(jsonObj, "stringify") instanceof Function stringify)) {
            throw new IllegalStateException("JSON.stringify is missing from the script scope");
        }
        Object result = stringify.call(cx, scope, jsonObj, new Object[]{value});
        if (!(result instanceof CharSequence text)) {
            // JSON.stringify returns undefined for values it can't represent (a lone function, say).
            throw new IllegalArgumentException(what + " could not turn that value into JSON");
        }
        return text.toString();
    }

    /** Hand JSON text back as a real JS value, or as the text itself when there is no scope to parse in. */
    static Object parse(Scriptable scope, String text) {
        Context cx = Context.getCurrentContext();
        Object json = ScriptableObject.getProperty(scope, "JSON");
        if (cx == null || !(json instanceof Scriptable jsonObj)
                || !(ScriptableObject.getProperty(jsonObj, "parse") instanceof Function parse)) {
            return text;
        }
        return parse.call(cx, scope, jsonObj, new Object[]{text});
    }

    /** Rhino hands Java values in wrapped; unwrap once so a type check sees the real thing. */
    static Object unwrap(Object value) {
        return value instanceof NativeJavaObject wrapper ? wrapper.unwrap() : value;
    }

    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof Function ? "a function" : value.getClass().getSimpleName();
    }
}
