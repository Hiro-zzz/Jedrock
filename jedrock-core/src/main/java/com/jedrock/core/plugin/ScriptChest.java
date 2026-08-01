package com.jedrock.core.plugin;

import com.jedrock.core.inventory.Container;
import com.jedrock.core.world.CoreWorld;

/**
 * A <b>chest in the world</b> as scripts see it — the 27 slots behind a chest block, which until now the
 * scripting layer could not reach at all. The {@code menus} global makes chests out of nothing; this is
 * the other kind, the one a player placed, that persists in the level file and that anyone standing at it
 * can open.
 *
 * <pre>{@code
 *   const chest = world.getChest(x, y, z);      // null if there's no chest block there
 *   if (chest && chest.count(264) >= 3) {       // three diamonds
 *     chest.remove(264, 3);
 *     chest.add(57, 1);                         // …becomes a diamond block
 *   }
 * }</pre>
 *
 * <p>A write goes straight into the same container a player's window is bound to, so the world is marked
 * dirty for autosave and anyone with that chest open sees the change immediately — a script and a player
 * are reaching into one box, not two copies of one.
 *
 * <p>Slots hold a canonical {@code (id << 4) | meta} state and a count, the same numbers the rest of the
 * script API speaks. There is no "item" object: a slot is a state and a number, and an empty slot is
 * state 0.
 */
public final class ScriptChest {

    private final PluginManager manager;
    private final CoreWorld world;
    private final Container container;
    private final int x;
    private final int y;
    private final int z;
    /** Where {@code JSON} lives, for per-stack data; null in a view built without a plugin's scope. */
    private final org.mozilla.javascript.Scriptable scope;

    ScriptChest(PluginManager manager, CoreWorld world, Container container, int x, int y, int z,
                org.mozilla.javascript.Scriptable scope) {
        this.manager = manager;
        this.world = world;
        this.container = container;
        this.x = x;
        this.y = y;
        this.z = z;
        this.scope = scope;
    }

    // ===== Where it is =====

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    /** Slot count — 27, a single chest. */
    public int size() {
        return container.size();
    }

    // ===== Reading =====

    /** The canonical state in {@code slot}, or 0 for an empty slot / a slot out of range. */
    public int getItem(int slot) {
        return inRange(slot) ? container.stateAt(slot) : 0;
    }

    /** How many items are in {@code slot}. */
    public int getCount(int slot) {
        return inRange(slot) ? container.countAt(slot) : 0;
    }

    public boolean isEmpty() {
        return container.isAllEmpty();
    }

    /** How many of {@code state} the chest holds in total, across every slot. */
    public int count(int state) {
        int total = 0;
        for (int slot = 0; slot < container.size(); slot++) {
            if (container.stateAt(slot) == state) {
                total += container.countAt(slot);
            }
        }
        return total;
    }

    public boolean contains(int state) {
        return count(state) > 0;
    }

    /**
     * The {@linkplain ScriptItems custom-item key} in {@code slot}, or {@code null} for an ordinary stack.
     *
     * <p>A chest is where a custom item spends most of its life — the level file has carried these keys
     * since v4, long enough that a chest can hold an item whose script has been uninstalled and reinstalled
     * since. Reading one costs nothing and does not require the item to be defined right now.
     */
    public String getKey(int slot) {
        return inRange(slot) ? container.customKeyAt(slot) : null;
    }

    /** What the stack in {@code slot} carries as its own state, or {@code null}. See {@code items.heldData}. */
    public Object getData(int slot) {
        if (!inRange(slot)) {
            return null;
        }
        String stored = container.customDataAt(slot);
        if (stored == null) {
            return null;
        }
        return scope == null ? stored : ScriptJson.parse(scope, stored);
    }

    /** Put data on the stack in {@code slot}. An empty slot has no stack to put it on. */
    public void setData(int slot, Object value) {
        if (!inRange(slot) || container.isEmpty(slot)) {
            return;
        }
        container.setCustomData(slot, writeData(value));
        changed();
    }

    private String writeData(Object value) {
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
        if (scope != null && unwrapped instanceof org.mozilla.javascript.Scriptable s
                && !(unwrapped instanceof org.mozilla.javascript.Function)) {
            return ScriptJson.stringify(scope, s, "chest.setData");
        }
        throw new IllegalArgumentException("a stack can carry a string, number, boolean, object or array"
                + " — not " + ScriptJson.describe(unwrapped));
    }

    /** Put a <b>custom</b> item in {@code slot} — the key is what makes it one; see {@code items.define}. */
    public void setItem(int slot, int state, int count, String customKey) {
        if (inRange(slot)) {
            container.set(slot, state, count, customKey);
            changed();
        }
    }

    // ===== Writing =====

    /** Put {@code count} of {@code state} in {@code slot}, replacing whatever was there. */
    public void setItem(int slot, int state, int count) {
        if (inRange(slot)) {
            container.set(slot, state, count);
            changed();
        }
    }

    public void setItem(int slot, int state) {
        setItem(slot, state, 1);
    }

    /**
     * Add {@code count} of {@code state}, stacking onto matching slots and then filling empty ones.
     *
     * @return how many actually fit — less than {@code count} when the chest fills up, 0 when it's full
     */
    public int add(int state, int count) {
        int added = 0;
        for (int i = 0; i < count; i++) {
            if (container.give(state, 0, container.size()) < 0) {
                break;
            }
            added++;
        }
        if (added > 0) {
            changed();
        }
        return added;
    }

    public int add(int state) {
        return add(state, 1);
    }

    /**
     * Take {@code count} of {@code state} out.
     *
     * @return how many were actually removed — less than asked when the chest didn't hold that many
     */
    public int remove(int state, int count) {
        int removed = 0;
        for (int i = 0; i < count; i++) {
            if (container.take(state, 0, container.size()) < 0) {
                break;
            }
            removed++;
        }
        if (removed > 0) {
            changed();
        }
        return removed;
    }

    public int remove(int state) {
        return remove(state, 1);
    }

    /** Empty one slot. */
    public void clear(int slot) {
        if (inRange(slot)) {
            container.clear(slot);
            changed();
        }
    }

    /** Empty the whole chest. */
    public void clear() {
        for (int slot = 0; slot < container.size(); slot++) {
            container.clear(slot);
        }
        changed();
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < container.size();
    }

    /** Persist the change, and show it to anyone who has this chest open right now. */
    private void changed() {
        world.markDirty();
        manager.refreshContainer(container);
    }

    @Override
    public String toString() {
        return "Chest(" + x + "," + y + "," + z + ")";
    }
}
