package com.jedrock.core.item;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.EventPriority;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.item.CustomItem;
import com.jedrock.api.player.Player;
import com.jedrock.utils.JLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every {@linkplain CustomItem custom item} the server knows, and the dispatch that gives them behaviour.
 *
 * <h2>Why a stack carries a key, not a definition</h2>
 *
 * <p>A definition is owned by whoever declared it and is thrown away on a hot reload; a stack in a chest
 * outlives all of that. The world file is loaded before any plugin runs, so a chest is restored at a point
 * where <em>no</em> definition exists yet, and a plugin may be uninstalled while its swords are still lying
 * around. So a stack stores the key, this registry gives the key meaning, and an item nobody defines right
 * now is simply the vanilla item it is drawn as — coming back to life the moment its script does. Nothing
 * has to be migrated, reconciled or garbage-collected.
 *
 * <h2>Why it costs nothing when unused</h2>
 *
 * <p>Behaviour is dispatched from <b>event listeners registered only while some defined item actually has a
 * hook</b>, and torn down when the last one goes. The core builds a {@code BlockBreakEvent} only when
 * something is listening, so registering unconditionally would make every block edit on every server
 * allocate an event for behaviours nobody wrote. Same rule the region rules follow, for the same reason.
 *
 * <p>A hook returning {@code true} consumes the action, which it does by cancelling the event it rides —
 * so an item's behaviour is exactly the cancellation a script could have written by hand, and a listener
 * at a higher priority can still overrule it.
 */
public final class ItemRegistry {

    private static final JLogger LOGGER = JLogger.getLogger(ItemRegistry.class);

    /** Key (lower-case) → definition. */
    private final Map<String, CoreCustomItem> items = new ConcurrentHashMap<>();

    private final EventBus events;
    /** Live only while some item has a hook — see the class doc. */
    private final List<EventBus.Subscription> dispatch = new ArrayList<>();

    public ItemRegistry(EventBus events) {
        this.events = events;
    }

    // ===== The registry =====

    /** Whether a key may name an item — the same test {@link #define} applies. */
    public static boolean isValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Declare an item, replacing any definition already under that key.
     *
     * <p>Replacing rather than refusing is the point: a script re-declares its items on every load, and the
     * stacks already in the world — which carry only the key — pick the new definition up immediately. That
     * is what makes editing an item's behaviour a hot reload rather than a migration.
     *
     * @return the definition, or {@code null} if the key is unusable
     */
    public CoreCustomItem define(String key, int state, String displayName, String[] lore) {
        if (!isValidKey(key)) {
            return null;
        }
        CoreCustomItem item = new CoreCustomItem(key.trim().toLowerCase(Locale.ROOT), state, displayName, lore);
        items.put(item.getKey(), item);
        dispatchFollowsHooks();
        return item;
    }

    /** The definition for {@code key}, or {@code null} if nothing defines it right now. */
    public CoreCustomItem get(String key) {
        return key == null ? null : items.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** Forget a definition. Stacks keep their key and fall back to the vanilla item they are drawn as. */
    public boolean remove(String key) {
        boolean removed = key != null && items.remove(key.trim().toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            dispatchFollowsHooks();
        }
        return removed;
    }

    /** Every defined key. */
    public List<String> keys() {
        return List.copyOf(items.keySet());
    }

    public int size() {
        return items.size();
    }

    /** Re-arm dispatch after a hook is added or cleared on an existing definition. */
    public void hooksChanged() {
        dispatchFollowsHooks();
    }

    // ===== Rendering =====

    /**
     * The name to show for a stack: the custom item's, or {@code null} for a plain one (and for a key
     * nothing defines — the vanilla name is the honest answer there).
     */
    public String displayNameOf(String key) {
        CoreCustomItem item = get(key);
        return item == null ? null : item.getDisplayName();
    }

    // ===== Dispatch =====

    /** The item a player is holding, if it is a custom one with behaviour. */
    private CoreCustomItem heldCustom(Player player, CoreCustomItem.Trigger trigger) {
        if (!(player instanceof com.jedrock.core.player.CorePlayer core)) {
            return null;
        }
        CoreCustomItem item = get(core.getHeldItemKey());
        return item != null && item.hook(trigger) != null ? item : null;
    }

    /**
     * Fire the {@code HIT} behaviour of whatever {@code attacker} is holding.
     *
     * <p>Called straight from the combat path rather than from a listener, because the attack has no event
     * carrying both sides — {@code PlayerDamageEvent} knows who was hurt, not who did it.
     *
     * @return {@code true} if the item consumed the hit, so no damage should follow
     */
    public boolean onHit(Player attacker, Player victim) {
        if (items.isEmpty()) {
            return false;
        }
        CoreCustomItem item = heldCustom(attacker, CoreCustomItem.Trigger.HIT);
        return item != null && runHook(item, CoreCustomItem.Trigger.HIT, attacker,
                ItemHook.ItemContext.on(victim));
    }

    private boolean runHook(CoreCustomItem item, CoreCustomItem.Trigger trigger, Player player,
                            ItemHook.ItemContext context) {
        ItemHook hook = item.hook(trigger);
        if (hook == null) {
            return false;
        }
        try {
            return hook.run(player, context);
        } catch (RuntimeException e) {
            // A script's mistake must not take a player's action — or the server — down with it.
            LOGGER.error("Item '" + item.getKey() + "' " + trigger + " hook threw", e);
            return false;
        }
    }

    /** Keep the listeners in step with whether any defined item has a behaviour at all. Idempotent. */
    private synchronized void dispatchFollowsHooks() {
        boolean wanted = items.values().stream().anyMatch(CoreCustomItem::hasHooks);
        if (wanted == !dispatch.isEmpty()) {
            return;
        }
        if (wanted) {
            dispatch.add(events.register(PlayerUseItemEvent.class, EventPriority.HIGH, event -> {
                if (!event.isUsing()) {
                    return; // the end of a use, not the start of one
                }
                CoreCustomItem item = heldCustom(event.getPlayer(), CoreCustomItem.Trigger.USE);
                if (item != null && runHook(item, CoreCustomItem.Trigger.USE, event.getPlayer(),
                        ItemHook.ItemContext.none())) {
                    event.setCancelled(true);
                }
            }));
            dispatch.add(events.register(BlockBreakEvent.class, EventPriority.HIGH, event -> {
                CoreCustomItem item = heldCustom(event.getPlayer(), CoreCustomItem.Trigger.BREAK);
                if (item != null && runHook(item, CoreCustomItem.Trigger.BREAK, event.getPlayer(),
                        ItemHook.ItemContext.at(event.getX(), event.getY(), event.getZ(), event.getState()))) {
                    event.setCancelled(true); // the item handled it — the block stays
                }
            }));
            dispatch.add(events.register(PlayerHeldItemChangeEvent.class, EventPriority.HIGH, event -> {
                // Resolved from the slot being switched TO, not from the hand: this event is posted before
                // the switch is recorded, so the player is still "holding" the slot they are leaving.
                if (!(event.getPlayer() instanceof com.jedrock.core.player.CorePlayer core)) {
                    return;
                }
                CoreCustomItem item = get(core.getInventory().customKeyAt(event.getNewSlot()));
                if (item != null && item.hook(CoreCustomItem.Trigger.HOLD) != null
                        && runHook(item, CoreCustomItem.Trigger.HOLD, event.getPlayer(),
                                ItemHook.ItemContext.none())) {
                    event.setCancelled(true); // refuse the switch, as a listener cancelling it would
                }
            }));
        } else {
            for (EventBus.Subscription subscription : dispatch) {
                subscription.remove();
            }
            dispatch.clear();
        }
    }
}
