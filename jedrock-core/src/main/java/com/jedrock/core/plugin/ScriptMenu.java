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
 * <p><b>Java</b> opens a real chest window. On <b>Bedrock</b> a window isn't available — 1.1.5 crashes on
 * one, and 0.14 doesn't bring it up — so a <em>button</em> menu there is shown as a text <b>list</b>
 * instead: give each button a {@link #button(int, int, String) label} and the player picks it with
 * {@code /pick <label>}, firing the same handler. A menu with no labels has nothing to list, so
 * {@link #open} returns {@code false} on 1.1.5; on 0.14 a storage menu (which moves items and can't be a
 * list) still attempts the window, since a window there is unreliable rather than fatal.
 */
public final class ScriptMenu {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final String title;
    private final Container container;
    private final String[] labels; // per-slot option label for the 1.1.5 list fallback; null = none
    private Function onClick;

    ScriptMenu(PluginManager manager, ScriptPlugin plugin, String title, int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("a menu has 1..6 rows, got " + rows);
        }
        this.manager = manager;
        this.plugin = plugin;
        this.title = title == null ? "" : title;
        this.container = new Container(rows * 9);
        this.labels = new String[container.size()];
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

    /**
     * A labelled <b>button</b>: sets the slot's item and gives it a name. The label is what a client that
     * can't show a window (either Bedrock era) lists and what {@code /pick} matches; Java ignores it
     * beyond the item itself. Buttons are the slots the list fallback can offer — a Bedrock button menu
     * without labels can't be shown at all.
     */
    public ScriptMenu button(int slot, int state, String label) {
        setItem(slot, state);
        if (slot >= 0 && slot < labels.length) {
            labels[slot] = label;
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
     * Open the menu to {@code player} — as a window on Java, as a {@code /pick} list on Bedrock. Returns
     * {@code false} if the player is offline, or if neither shape is available to them (a Bedrock button
     * menu whose buttons carry no labels).
     */
    public boolean open(Player player) {
        MenuClick click = onClick == null ? null
                : (p, slot, state) -> manager.callMenuClick(plugin, onClick, p, slot, state);
        return manager.openMenu(player, title, container, labels, click);
    }
}
