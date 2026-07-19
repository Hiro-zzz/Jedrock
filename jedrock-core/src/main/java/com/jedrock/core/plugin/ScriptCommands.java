package com.jedrock.core.plugin;

import com.jedrock.core.command.Command;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code commands} object a script sees. One per plugin, so every command a script registers is tracked
 * against that plugin and unregistered when it unloads or hot-reloads. Two forms:
 *
 * <pre>{@code
 *   // Minimal: a name and a handler.
 *   commands.register('heal', function (player, args) {
 *       player.setHealth(player.getMaxHealth());
 *       player.sendMessage('{green}Healed.');
 *   });
 *
 *   // Full: an options object with help text and aliases.
 *   commands.register({
 *       name: 'kit', aliases: ['starter'], description: 'Grab a starter kit', usage: '/kit',
 *       execute: function (player, args) { ... }
 *   });
 * }</pre>
 *
 * The handler gets the sender as an api {@code Player} and {@code args} — a JS array of the words after the
 * label ({@code args.length}, {@code args[0]}, {@code args.join(' ')} all work). The command shows up in
 * {@code /help}, so give it a description and usage. Handlers run under the script lock, like event listeners.
 */
public final class ScriptCommands {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptCommands(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /** Register a command from just a name and a handler; description/usage default, no aliases. */
    public void register(String name, Function handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("commands.register needs a non-empty name");
        }
        if (handler == null) {
            throw new IllegalArgumentException("commands.register('" + name + "', …) needs a function");
        }
        add(name, "", "/" + name.toLowerCase(Locale.ROOT), List.of(), handler);
    }

    /** Register a command from an options object: {@code {name, description, usage, aliases, execute}}. */
    public void register(Scriptable options) {
        if (options == null) {
            throw new IllegalArgumentException("commands.register needs a name+function or an options object");
        }
        String name = str(options, "name", null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("commands.register({...}) needs a 'name'");
        }
        Object fn = get(options, "execute");
        if (!(fn instanceof Function handler)) {
            throw new IllegalArgumentException("commands.register({...}) needs an 'execute' function");
        }
        String description = str(options, "description", "");
        String usage = str(options, "usage", "/" + name.toLowerCase(Locale.ROOT));
        add(name, description, usage, aliasesOf(options), handler);
    }

    private void add(String name, String description, String usage, List<String> aliases, Function handler) {
        ScriptCommand command = new ScriptCommand(manager, plugin, name.toLowerCase(Locale.ROOT),
                description, usage, aliases, handler);
        manager.commandManager().register(command);
        plugin.addCommand(command);
    }

    // ----- reading properties off the JS options object -----

    private static Object get(Scriptable obj, String key) {
        Object v = ScriptableObject.getProperty(obj, key);
        return v == Scriptable.NOT_FOUND ? null : v;
    }

    private static String str(Scriptable obj, String key, String fallback) {
        Object v = get(obj, key);
        return v == null ? fallback : Context.toString(v);
    }

    /** Read {@code aliases} as a JS array of strings, a single string, or absent → empty. */
    private static List<String> aliasesOf(Scriptable obj) {
        Object v = get(obj, "aliases");
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v instanceof NativeArray array) {
            long len = array.getLength();
            for (int i = 0; i < len; i++) {
                Object item = array.get(i, array);
                if (item != null && item != Scriptable.NOT_FOUND) {
                    out.add(Context.toString(item).toLowerCase(Locale.ROOT));
                }
            }
        } else {
            out.add(Context.toString(v).toLowerCase(Locale.ROOT)); // a lone string alias
        }
        return out;
    }
}
