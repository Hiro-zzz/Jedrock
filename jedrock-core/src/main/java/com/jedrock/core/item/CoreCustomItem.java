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
 * share this one object, and there is nowhere to put "this particular sword's remaining charges" (that is
 * what {@code storage} is for, keyed however the script likes).
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
        HOLD
    }

    private final String key;
    private final int state;
    private volatile String displayName;
    private volatile String[] lore;
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

    /** Whether this item has any behaviour at all — a purely cosmetic item needs no dispatch. */
    public boolean hasHooks() {
        return !hooks.isEmpty();
    }

    @Override
    public String toString() {
        return "CustomItem[" + key + "]";
    }
}
