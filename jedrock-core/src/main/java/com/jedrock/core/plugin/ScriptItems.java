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

    ScriptItems(PluginManager manager, ScriptPlugin plugin, ItemRegistry registry) {
        this.manager = manager;
        this.plugin = plugin;
        this.registry = registry;
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
        int given = 0;
        int lastSlot = -1;
        for (int i = 0; i < Math.max(0, count); i++) {
            int slot = target.getInventory().give(item.getState(), 0, CorePlayer.STORAGE_SLOTS, item.getKey());
            if (slot < 0) {
                break; // full
            }
            if (slot != lastSlot) {
                if (lastSlot >= 0) {
                    target.syncSlot(lastSlot);
                }
                lastSlot = slot;
            }
            given++;
        }
        if (lastSlot >= 0) {
            target.syncSlot(lastSlot);
        }
        return given;
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
