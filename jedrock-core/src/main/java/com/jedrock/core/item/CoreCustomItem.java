package com.jedrock.core.item;

import com.jedrock.api.item.CustomItem;

import java.util.EnumMap;
import java.util.Map;

/**
 * The server's {@link CustomItem}: the name and lore a stack is shown with, plus the behaviours hung on it.
 *
 * <p>Deliberately thin, and deliberately <em>replaceable</em>. A definition belongs to whoever declared it,
 * so a hot reload throws this away and builds a new one under the same key — which is exactly why a stack
 * stores the key rather than a reference to this. Nothing here is per-stack: two frostblades in two chests
 * share this one object, and what happened to one of them in particular is written on the stack itself
 * (see {@code Container}'s per-stack data, reachable as {@code items.heldData}).
 *
 * <p>The <b>cooldown</b> is the same distinction from the other side. How long an item makes you wait is a
 * property of the item and belongs here; <em>when a given player last used one</em> is not, and lives in
 * the registry — which outlives this object, so an item's cooldowns are not all reset by saving its script.
 */
public final class CoreCustomItem implements CustomItem {

    /** The behaviours a custom item can carry. Each one rides an event the core already routes. */
    public enum Trigger {
        /** Right-clicked while held. */
        USE,
        /** Used to break a block. */
        BREAK,
        /** Used to hit another player. */
        HIT,
        /** Taken into the hand (a hotbar switch, or the slot's contents changing under it). */
        HOLD,
        /**
         * Not an action of its own: the one above was refused because the item is still cooling down.
         * Fires in place of {@link #USE} / {@link #BREAK} / {@link #HIT}, with the milliseconds left in
         * the context, and consumes the action the same way if it returns {@code true}.
         */
        COOLDOWN
    }

    /** Which triggers a cooldown gates. Taking an item into your hand is not an act the item can refuse. */
    public static boolean isCooledDown(Trigger trigger) {
        return trigger == Trigger.USE || trigger == Trigger.BREAK || trigger == Trigger.HIT;
    }

    private final String key;
    private final int state;
    private volatile String displayName;
    private volatile String[] lore;
    private volatile long cooldownMillis;
    private final Map<Trigger, ItemHook> hooks = new EnumMap<>(Trigger.class);

    public CoreCustomItem(String key, int state, String displayName, String[] lore) {
        this.key = key;
        this.state = state;
        this.displayName = displayName == null ? key : displayName;
        this.lore = lore == null ? new String[0] : lore.clone();
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? key : displayName;
    }

    @Override
    public String[] getLore() {
        return lore.clone();
    }

    public void setLore(String[] lore) {
        this.lore = lore == null ? new String[0] : lore.clone();
    }

    /**
     * How long, in milliseconds, this item makes a player wait between uses; {@code 0} = no wait.
     *
     * <p>One cooldown per item, not per behaviour: to a player "the wand is recharging" is one fact about
     * one object, and a separate timer per trigger would be a rule nobody could see on screen. (No edition
     * here draws the vanilla cooldown sweep for a server-side item anyway — the item simply doesn't answer,
     * which is what a {@link Trigger#COOLDOWN} hook is for.)
     */
    public long getCooldownMillis() {
        return cooldownMillis;
    }

    public void setCooldownMillis(long millis) {
        this.cooldownMillis = Math.max(0L, millis);
    }

    /** Hang a behaviour on this item, or clear it with {@code null}. */
    public void setHook(Trigger trigger, ItemHook hook) {
        if (hook == null) {
            hooks.remove(trigger);
        } else {
            hooks.put(trigger, hook);
        }
    }

    /** The behaviour for {@code trigger}, or {@code null} if this item has none. */
    public ItemHook hook(Trigger trigger) {
        return hooks.get(trigger);
    }

    /**
     * Whether this item has any behaviour at all — a purely cosmetic item needs no dispatch.
     *
     * <p>A lone {@link Trigger#COOLDOWN} hook doesn't count: it only ever fires <em>instead of</em> another
     * behaviour, so an item that has nothing to refuse has nothing to say either.
     */
    public boolean hasHooks() {
        for (Trigger trigger : hooks.keySet()) {
            if (trigger != Trigger.COOLDOWN) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "CustomItem[" + key + "]";
    }
}
