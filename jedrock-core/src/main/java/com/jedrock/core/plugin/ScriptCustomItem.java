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

    /**
     * What a freshly given stack of this item arrives enchanted with — {@code 'sharpness'} at one level,
     * or a whole set at once. Returns this item, so calls chain.
     *
     * <pre>{@code
     *   items.define('frostblade', 276)
     *        .setEnchantment('sharpness', 3)
     *        .setEnchantments({sharpness: 3, unbreaking: 1});   // …or all of them together
     * }</pre>
     *
     * <p>Part of the definition, so a reload changes what the <em>next</em> one comes with; stacks already
     * in the world keep what they were given, enchantments being per-stack once they exist.
     */
    public ScriptCustomItem setEnchantment(String name, int level) {
        com.jedrock.api.item.Enchantment enchantment = requireEnchantment(name);
        item.setEnchantments(item.getEnchantments().with(enchantment, level));
        return this;
    }

    /** Every enchantment at once, as a plain object of {@code name: level}. */
    public ScriptCustomItem setEnchantments(Object levels) {
        com.jedrock.api.item.Enchantments set = com.jedrock.api.item.Enchantments.NONE;
        if (levels instanceof org.mozilla.javascript.Scriptable object) {
            for (Object id : object.getIds()) {
                String key = String.valueOf(id);
                Object value = object.get(key, object);
                int level = value instanceof Number n ? n.intValue() : 0;
                set = set.with(requireEnchantment(key), level);
            }
        }
        item.setEnchantments(set);
        return this;
    }

    /** What this definition hands out, as {@code {sharpness: 3}} would have set it. */
    public String getEnchantments() {
        return item.getEnchantments().toCompactString();
    }

    private static com.jedrock.api.item.Enchantment requireEnchantment(String name) {
        com.jedrock.api.item.Enchantment enchantment = com.jedrock.api.item.Enchantment.fromString(name);
        if (enchantment == null) {
            throw new IllegalArgumentException("no such enchantment: '" + name + "'");
        }
        return enchantment;
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

    // ===== Cooldown =====

    /**
     * Make the item wait {@code millis} between uses, per player. {@code 0} removes the wait.
     *
     * <pre>{@code
     *   items.define('bomb', Blocks.state(46, 0))
     *        .setCooldown(5000)
     *        .onUse(function (player) { world.spawnParticle('explode', …); return true; })
     *        .onCooldown(function (player, ctx) {
     *            player.sendActionBar('{gray}Ready in ' + Math.ceil(ctx.getRemaining() / 1000) + 's');
     *            return true;                    // …and swallow the click while it recharges
     *        });
     * }</pre>
     *
     * <p>It gates {@code onUse}, {@code onBreak} and {@code onHit} — not {@code onHold}, since taking an
     * item into your hand isn't an act the item gets to refuse. The clock starts when the behaviour
     * <em>runs</em>, whatever it returns; {@link #clearCooldown} takes it back if a script decides that
     * particular use didn't count.
     *
     * <p>No client here draws the vanilla cooldown sweep for a server-side item, so <b>nothing shows on
     * screen</b> unless the script says something — which is what {@link #onCooldown} is for.
     */
    public ScriptCustomItem setCooldown(double millis) {
        item.setCooldownMillis((long) millis);
        registry.hooksChanged(); // gaining a cooldown is what arms the per-player cleanup
        return this;
    }

    /** How long this item makes a player wait, in milliseconds; {@code 0} = no wait. */
    public double getCooldown() {
        return item.getCooldownMillis();
    }

    /**
     * Called <em>instead of</em> the behaviour when the item is still cooling down. {@code ctx.getRemaining()}
     * is the milliseconds left; return {@code true} to consume the action anyway, {@code false} (or no hook
     * at all) to let it fall through and behave as the vanilla item it is drawn as.
     */
    public ScriptCustomItem onCooldown(Function handler) {
        return hook(CoreCustomItem.Trigger.COOLDOWN, handler);
    }

    /** How long {@code player} must still wait for this item, in milliseconds; {@code 0} = ready. */
    public double cooldownFor(Object player) {
        return registry.cooldownRemaining(requirePlayer(player), item.getKey());
    }

    /** Whether this item will answer {@code player} right now. */
    public boolean isReadyFor(Object player) {
        return cooldownFor(player) <= 0;
    }

    /** End this item's cooldown for one player. Returns this item, so calls chain. */
    public ScriptCustomItem clearCooldown(Object player) {
        registry.clearCooldown(requirePlayer(player), item.getKey());
        return this;
    }

    /** Start it by hand, as though the item had just been used. */
    public ScriptCustomItem startCooldown(Object player) {
        registry.startCooldown(requirePlayer(player), item.getKey());
        return this;
    }

    private static com.jedrock.api.player.Player requirePlayer(Object player) {
        com.jedrock.api.player.Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("a cooldown is per player — pass one");
        }
        return target;
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
