package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.inventory.MenuClick;
import org.mozilla.javascript.Function;

/**
 * A <b>virtual chest</b> a script owns and opens to a player — a chest window with no world block behind
 * it. Two shapes, decided by whether an {@link #onClick} handler is set:
 *
 * <pre>{@code
 *   // A button menu: slots are read-only, a click calls back.
 *   var m = menus.create('Shop', 3);                 // 3 rows = 27 slots
 *   m.setItem(0, Blocks.state(57, 0));               // a diamond-block "button"
 *   m.onClick(function (player, slot, state) { player.sendMessage('bought slot ' + slot); });
 *   m.open(player);
 *
 *   // A storage chest: no handler, the player moves items freely; read them back afterwards.
 *   var bag = menus.create('Stash', 1);
 *   bag.open(player);
 * }</pre>
 *
 * <p>Java and Bedrock 0.14, which open real chest windows. The retail 1.1.5 client crashes on a chest
 * window (the same reason world chests trade through click-transfer there), so {@link #open} returns
 * {@code false} for a 1.1.5 player. On the client-authoritative PE window a storage menu works cleanly;
 * a button menu's read-only revert is best-effort.
 */
public final class ScriptMenu {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final String title;
    private final Container container;
    private Function onClick;

    ScriptMenu(PluginManager manager, ScriptPlugin plugin, String title, int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("a menu has 1..6 rows, got " + rows);
        }
        this.manager = manager;
        this.plugin = plugin;
        this.title = title == null ? "" : title;
        this.container = new Container(rows * 9);
    }

    /** Number of slots (rows × 9). */
    public int size() {
        return container.size();
    }

    /** Put a canonical item state in a slot (count 1). */
    public ScriptMenu setItem(int slot, int state) {
        return setItem(slot, state, 1);
    }

    /** Put a canonical item state + count in a slot; state 0 empties it. */
    public ScriptMenu setItem(int slot, int state, int count) {
        if (slot >= 0 && slot < container.size()) {
            container.set(slot, state, state == 0 ? 0 : Math.max(1, count));
        }
        return this;
    }

    /** The canonical state in a slot, or 0 if empty / out of range. */
    public int getItem(int slot) {
        return slot >= 0 && slot < container.size() ? container.stateAt(slot) : 0;
    }

    /** Empty every slot. */
    public ScriptMenu clear() {
        for (int i = 0; i < container.size(); i++) {
            container.clear(i);
        }
        return this;
    }

    /**
     * Make this a button menu: slots become read-only and a click on one calls {@code handler(player,
     * slot, state)}. Without this, the menu is a storage chest the player can move items in and out of.
     */
    public ScriptMenu onClick(Function handler) {
        this.onClick = handler;
        return this;
    }

    /**
     * Open the menu to {@code player}. Returns {@code false} if the player is offline or on an edition
     * that can't show a chest window (Bedrock).
     */
    public boolean open(Player player) {
        MenuClick click = onClick == null ? null
                : (p, slot, state) -> manager.callMenuClick(plugin, onClick, p, slot, state);
        return manager.openMenu(player, title, container, click);
    }
}
