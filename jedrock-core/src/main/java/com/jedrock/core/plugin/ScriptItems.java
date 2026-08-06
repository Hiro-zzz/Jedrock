package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.core.item.CoreCustomItem;
import com.jedrock.core.item.ItemRegistry;
import com.jedrock.core.player.CorePlayer;

/**
 * The {@code items} global — custom items: a name, some lore and programmable behaviour hung on an
 * ordinary item state.
 *
 * <pre>{@code
 *   items.define('frostblade', Blocks.state(276, 0))
 *        .setName('{aqua}Frostblade')
 *        .onUse(function (player, ctx) { player.sendMessage('brrr'); return true; });
 *
 *   items.give(player, 'frostblade');
 *   if (items.keyOf(player.getHeldItem?) …)          // see heldKey(player)
 * }</pre>
 *
 * <p><b>There is no resource pack</b>, by design — that would break the promise that any unmodified client
 * can join, and 0.14 barely supports one. So a custom item is drawn as whatever vanilla item it is built
 * on. What is custom is its name, its lore and what it does.
 *
 * <p>Identity is the <b>key</b>, and a stack carries the key rather than a copy of the definition. That is
 * what lets a custom item survive a hot reload (the new definition applies to every stack at once), a
 * restart (the world file is read before any plugin exists), and even the plugin being removed — such an
 * item just behaves as the vanilla one it is drawn as until its script comes back.
 *
 * <p>Definitions are <b>server state</b> in the same sense regions are: they are not torn down with the
 * plugin. Re-declaring on every load is the intended usage and is why {@code define} replaces rather than
 * refuses.
 */
public final class ScriptItems {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final ItemRegistry registry;
    /** The plugin's scope — where {@code JSON} lives, for per-stack data that isn't a plain string. */
    private final org.mozilla.javascript.Scriptable scope;

    ScriptItems(PluginManager manager, ScriptPlugin plugin, ItemRegistry registry,
                org.mozilla.javascript.Scriptable scope) {
        this.manager = manager;
        this.plugin = plugin;
        this.registry = registry;
        this.scope = scope;
    }

    /**
     * The state a vanilla name stands for — {@code items.state('red_wool')} is {@code 574} — or
     * {@code -1} for a word that names nothing. Accepts every form a command does ({@code wool:14},
     * {@code 35:14}, {@code 276}), so a script and a player can spell an item the same way.
     *
     * <p>This is a convenience over the numbers, not a replacement for them: the API still speaks
     * states, and a name is only ever resolved to one here.
     */
    public int state(String name) {
        return com.jedrock.api.item.ItemNames.parse(name);
    }

    /**
     * What to call a state — {@code items.nameOf(574)} is {@code 'red_wool'}. Falls back to
     * {@code 'id:meta'} for a state nothing names, so it is always printable.
     */
    public String nameOf(int state) {
        return com.jedrock.api.item.ItemNames.name(state);
    }

    /**
     * Declare an item drawn as {@code state}, replacing any definition already under {@code key}.
     *
     * @param key a short stable identity — letters, digits, {@code _ - .}, up to 64
     * @return the item, or {@code null} if the key is unusable
     */
    public ScriptCustomItem define(String key, int state) {
        CoreCustomItem defined = registry.define(key, state, null, null);
        return defined == null ? null : new ScriptCustomItem(manager, plugin, registry, defined);
    }

    /** The item defined under {@code key}, or {@code null}. */
    public ScriptCustomItem get(String key) {
        CoreCustomItem found = registry.get(key);
        return found == null ? null : new ScriptCustomItem(manager, plugin, registry, found);
    }

    /** Forget a definition. Stacks keep their key and fall back to the vanilla item they are drawn as. */
    public boolean remove(String key) {
        return registry.remove(key);
    }

    /** Every defined key. */
    public String[] keys() {
        return registry.keys().toArray(new String[0]);
    }

    public int count() {
        return registry.size();
    }

    /**
     * Give {@code count} of a custom item to a player's inventory (survival slots), as much as fits.
     *
     * @return how many actually went in
     */
    public int give(Object player, String key, int count) {
        CorePlayer target = core(player);
        CoreCustomItem item = registry.get(key);
        if (item == null) {
            throw new IllegalArgumentException("no item is defined as '" + key + "'");
        }
        return target.giveItem(item.getState(), count, item.getKey());
    }

    /** Give one. */
    public int give(Object player, String key) {
        return give(player, key, 1);
    }

    /** Put a custom item straight into one inventory slot (0-35), replacing whatever is there. */
    public void set(Object player, int slot, String key, int count) {
        CorePlayer target = core(player);
        CoreCustomItem item = registry.get(key);
        if (item == null) {
            throw new IllegalArgumentException("no item is defined as '" + key + "'");
        }
        if (slot < 0 || slot >= CorePlayer.STORAGE_SLOTS) {
            throw new IllegalArgumentException("inventory slot must be 0.." + (CorePlayer.STORAGE_SLOTS - 1));
        }
        target.getInventory().set(slot, item.getState(), Math.max(1, count), item.getKey());
        target.syncSlot(slot);
    }

    /** The custom key in an inventory slot, or {@code null} for an ordinary item. */
    public String keyAt(Object player, int slot) {
        return core(player).getInventory().customKeyAt(slot);
    }

    /** The custom key of whatever the player is holding, or {@code null}. */
    public String heldKey(Object player) {
        return core(player).getHeldItemKey();
    }

    // ===== Per-stack data =====

    /**
     * What <em>this particular stack</em> is carrying, or {@code null} if nothing has been put on it.
     *
     * <pre>{@code
     *   items.define('wand', Blocks.state(280, 0)).onUse(function (player) {
     *       var state = items.heldData(player) || {charges: 3};
     *       if (state.charges <= 0) { player.sendMessage('{gray}Spent.'); return true; }
     *       state.charges--;
     *       items.setHeldData(player, state);      // …and this wand, not every wand, is down one
     *       return true;
     *   });
     * }</pre>
     *
     * <p>A definition is shared by every stack that names it, so it is the wrong place for anything that
     * happened to one of them. This is the right place. Strings, numbers and booleans come back as
     * themselves; an object or array is stored as JSON and handed back as a real value.
     *
     * <p>Two stacks with different data do not merge, which is what stops a spent wand from dissolving
     * into a full one when they meet in a slot.
     */
    public Object heldData(Object player) {
        CorePlayer target = core(player);
        return readData(target.getInventory().customDataAt(target.getHeldItemSlot()));
    }

    /** Put data on the stack the player is holding. {@code null} clears it; an empty hand is a no-op. */
    public void setHeldData(Object player, Object value) {
        CorePlayer target = core(player);
        setDataAt(player, target.getHeldItemSlot(), value);
    }

    /** The per-stack data in an inventory slot (0-35), or {@code null}. */
    public Object dataAt(Object player, int slot) {
        CorePlayer target = core(player);
        return inStorage(slot) ? readData(target.getInventory().customDataAt(slot)) : null;
    }

    /**
     * Put data on the stack in one inventory slot. It belongs to the stack, so an empty slot has nowhere
     * to keep it and the call does nothing.
     */
    public void setDataAt(Object player, int slot, Object value) {
        CorePlayer target = core(player);
        if (!inStorage(slot) || target.getInventory().isEmpty(slot)) {
            return;
        }
        target.getInventory().setCustomData(slot, writeData(value));
        target.syncSlot(slot);
    }

    private static boolean inStorage(int slot) {
        return slot >= 0 && slot < CorePlayer.STORAGE_SLOTS;
    }

    /** Stored text back to the value a script put there. Package-private: {@code ScriptChest} shares it. */
    Object readData(String stored) {
        return stored == null ? null : ScriptJson.parse(scope, stored);
    }

    /**
     * A script's value down to the one string a stack can carry. Kept deliberately narrow: a stack's data
     * rides in the level file and is compared verbatim for merging, so it has to be something that means
     * the same thing every time it is written.
     */
    String writeData(Object value) {
        Object unwrapped = ScriptJson.unwrap(value);
        if (unwrapped == null || unwrapped instanceof org.mozilla.javascript.Undefined) {
            return null;
        }
        if (unwrapped instanceof CharSequence text) {
            return text.toString();
        }
        if (unwrapped instanceof Number || unwrapped instanceof Boolean) {
            return unwrapped.toString();
        }
        if (unwrapped instanceof org.mozilla.javascript.Scriptable s
                && !(unwrapped instanceof org.mozilla.javascript.Function)) {
            return ScriptJson.stringify(scope, s, "items.setHeldData");
        }
        throw new IllegalArgumentException("a stack can carry a string, number, boolean, object or array"
                + " — not " + ScriptJson.describe(unwrapped));
    }

    /** How many of a custom item a player is carrying, counted across their inventory. */
    public int count(Object player, String key) {
        CorePlayer target = core(player);
        int total = 0;
        for (int slot = 0; slot < CorePlayer.STORAGE_SLOTS; slot++) {
            if (key.equals(target.getInventory().customKeyAt(slot))) {
                total += target.getInventory().countAt(slot);
            }
        }
        return total;
    }

    private static CorePlayer core(Object player) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (!(target instanceof CorePlayer resolved)) {
            throw new IllegalArgumentException("items expects a player");
        }
        return resolved;
    }
}
