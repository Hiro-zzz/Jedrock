package com.jedrock.core.plugin;

import com.jedrock.core.item.CoreCustomItem;
import com.jedrock.core.item.ItemHook;
import com.jedrock.core.item.ItemRegistry;
import org.mozilla.javascript.Function;

/**
 * One custom item as a script sees it — its look, and the behaviours hung on it.
 *
 * <pre>{@code
 *   var blade = items.define('frostblade', Blocks.state(276, 0))   // drawn as a diamond sword
 *       .setName('{aqua}Frostblade')
 *       .setLore(['{gray}Cold to the touch.', '{dark_gray}Right-click to chill'])
 *       .onUse(function (player, ctx) {
 *           player.sendMessage('{aqua}A chill runs down the blade.');
 *           return true;            // consumed: the core does not go on to use the item
 *       })
 *       .onHit(function (player, ctx) {
 *           ctx.getTarget().sendMessage('{aqua}Frozen!');
 *           return false;           // false = let the normal hit land as well
 *       });
 *
 *   items.give(player, 'frostblade');
 * }</pre>
 *
 * <p>A hook returning <b>true consumes the action</b> — the core does not then place the block, break the
 * cell or deal the damage. It does that by cancelling the event it rides on, so an item's behaviour is
 * exactly the cancellation a script could have written by hand.
 *
 * <p>Re-declaring an item under the same key <b>replaces</b> the definition, and every stack already in the
 * world picks the new one up at once — because a stack carries the key, not a copy. That is what makes
 * editing an item's behaviour a hot reload rather than a migration.
 */
public final class ScriptCustomItem {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final ItemRegistry registry;
    private final CoreCustomItem item;

    ScriptCustomItem(PluginManager manager, ScriptPlugin plugin, ItemRegistry registry, CoreCustomItem item) {
        this.manager = manager;
        this.plugin = plugin;
        this.registry = registry;
        this.item = item;
    }

    /** The stable identity a stack carries. */
    public String getKey() {
        return item.getKey();
    }

    /** The vanilla state this item is drawn as. */
    public int getState() {
        return item.getState();
    }

    public String getName() {
        return item.getDisplayName();
    }

    /** The name shown in place of the vanilla one, in the unified markup. Returns this item, so calls chain. */
    public ScriptCustomItem setName(String name) {
        item.setDisplayName(name);
        return this;
    }

    public String[] getLore() {
        return item.getLore();
    }

    /** The lines under the name. Accepts a JS array of strings. Returns this item, so calls chain. */
    public ScriptCustomItem setLore(Object lore) {
        item.setLore(toStringArray(lore));
        return this;
    }

    /** Right-clicked while held. {@code fn(player, ctx)}; return {@code true} to consume the use. */
    public ScriptCustomItem onUse(Function handler) {
        return hook(CoreCustomItem.Trigger.USE, handler);
    }

    /** Used to break a block. {@code ctx} carries {@code getX/getY/getZ/getBlock}; {@code true} keeps the block. */
    public ScriptCustomItem onBreak(Function handler) {
        return hook(CoreCustomItem.Trigger.BREAK, handler);
    }

    /** Used to hit a player. {@code ctx.getTarget()} is the victim; {@code true} suppresses the damage. */
    public ScriptCustomItem onHit(Function handler) {
        return hook(CoreCustomItem.Trigger.HIT, handler);
    }

    /** Taken into the hand. {@code true} refuses the switch. */
    public ScriptCustomItem onHold(Function handler) {
        return hook(CoreCustomItem.Trigger.HOLD, handler);
    }

    private ScriptCustomItem hook(CoreCustomItem.Trigger trigger, Function handler) {
        if (handler == null) {
            item.setHook(trigger, null);
        } else {
            // Run under the plugin's own lock and context, like every other script callback, so a hook can
            // touch the plugin's state safely and a throw is reported against the right file.
            ItemHook hook = (player, context) ->
                    manager.callItemHook(plugin, handler, player, context);
            item.setHook(trigger, hook);
        }
        registry.hooksChanged(); // an item that has just gained (or lost) its first hook changes dispatch
        return this;
    }

    /** A JS array of strings, a lone string, or nothing. */
    static String[] toStringArray(Object value) {
        if (value == null || value instanceof org.mozilla.javascript.Undefined) {
            return new String[0];
        }
        if (value instanceof CharSequence one) {
            return new String[]{one.toString()};
        }
        if (value instanceof org.mozilla.javascript.NativeArray array) {
            String[] out = new String[(int) array.getLength()];
            for (int i = 0; i < out.length; i++) {
                Object element = array.get(i, array);
                out[i] = element == null ? "" : element.toString();
            }
            return out;
        }
        if (value instanceof Object[] array) {
            String[] out = new String[array.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = array[i] == null ? "" : array[i].toString();
            }
            return out;
        }
        return new String[]{value.toString()};
    }

    @Override
    public String toString() {
        return "CustomItem[" + item.getKey() + "]";
    }
}
