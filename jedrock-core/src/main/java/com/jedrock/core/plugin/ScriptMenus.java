package com.jedrock.core.plugin;

/**
 * The {@code menus} global a script sees — a factory for {@linkplain ScriptMenu virtual chests}. One per
 * plugin, purely so a created menu carries its plugin (for the click handler's lock / context); menus
 * aren't otherwise tracked or torn down, since a menu lives only as long as a player keeps its window open.
 *
 * <pre>{@code
 *   var m = menus.create('Shop', 3);   // a 3-row (27-slot) chest window
 * }</pre>
 */
public final class ScriptMenus {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptMenus(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /** Create a menu with {@code rows} rows of 9 slots (1..6). Set items on it, then {@code open(player)}. */
    public ScriptMenu create(String title, int rows) {
        return new ScriptMenu(manager, plugin, title, rows);
    }
}
