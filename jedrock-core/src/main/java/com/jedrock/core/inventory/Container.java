package com.jedrock.core.inventory;

/**
 * A flat array of item slots — the shared backing for the player inventory and, later, block containers
 * (chests). Each slot is a canonical {@code (id << 4) | meta} state (0 = empty) plus a count. Only
 * stacking / moving lives here: the server <em>stores and moves</em> items, it simulates nothing (no
 * crafting, no item entities). Not thread-safe — a container is touched only from its owner's own thread
 * (a player's network thread; a chest under the lock of whoever has it open).
 */
public class Container {

    public static final int MAX_STACK = 64;

    protected final int[] states;
    protected final int[] counts;
    /**
     * Per slot, the {@linkplain com.jedrock.api.item.CustomItem custom item} key this stack carries, or
     * {@code null} for an ordinary one. A <b>key</b>, not a definition: the world file is loaded before any
     * plugin runs and a hot reload replaces every definition, so a stack that held a reference would come
     * back from disk pointing at nothing. See {@code ItemRegistry}.
     */
    protected final String[] customKeys;

    public Container(int size) {
        this.states = new int[size];
        this.counts = new int[size];
        this.customKeys = new String[size];
    }

    public int size() {
        return states.length;
    }

    /** Canonical state per slot (0 = empty). Read-only by convention. */
    public int[] states() {
        return states;
    }

    /** Item count per slot, parallel to {@link #states()}. Read-only by convention. */
    public int[] counts() {
        return counts;
    }

    /** Custom-item key per slot ({@code null} = an ordinary item), parallel to {@link #states()}. */
    public String[] customKeys() {
        return customKeys;
    }

    /** The custom-item key in this slot, or {@code null} if it holds an ordinary item. */
    public String customKeyAt(int slot) {
        return slot >= 0 && slot < customKeys.length ? customKeys[slot] : null;
    }

    public int stateAt(int slot) {
        return states[slot];
    }

    public int countAt(int slot) {
        return counts[slot];
    }

    public boolean isEmpty(int slot) {
        return states[slot] == 0 || counts[slot] <= 0;
    }

    /** True if every slot is empty — an empty chest need not be persisted (it's recreated on open). */
    public boolean isAllEmpty() {
        for (int i = 0; i < states.length; i++) {
            if (!isEmpty(i)) {
                return false;
            }
        }
        return true;
    }

    /** Set a slot outright (a swap / place); {@code state == 0} or {@code count <= 0} clears it. */
    public void set(int slot, int state, int count) {
        set(slot, state, count, null);
    }

    /** Set a slot to a custom item — the same thing, but the stack carries {@code customKey}. */
    public void set(int slot, int state, int count, String customKey) {
        if (state == 0 || count <= 0) {
            clear(slot);
        } else {
            states[slot] = state;
            counts[slot] = count;
            customKeys[slot] = customKey;
        }
    }

    public void clear(int slot) {
        states[slot] = 0;
        counts[slot] = 0;
        customKeys[slot] = null;
    }

    /**
     * Add one {@code state} into slots {@code [from, to)}, stacking onto a matching slot (up to
     * {@link #MAX_STACK}) or filling the first empty one. @return the affected slot, or -1 if it didn't fit.
     */
    public int give(int state, int from, int to) {
        return give(state, from, to, null);
    }

    /**
     * Add one item into slots {@code [from, to)}. A stack only merges with one of the <b>same state and
     * the same custom key</b>, so a named sword never quietly stacks with an ordinary one — they are
     * different items even though they are drawn the same.
     *
     * @return the affected slot, or -1 if it didn't fit
     */
    public int give(int state, int from, int to, String customKey) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] < MAX_STACK && sameCustom(customKeys[i], customKey)) {
                counts[i]++;
                return i;
            }
        }
        for (int i = from; i < to; i++) {
            if (states[i] == 0) {
                states[i] = state;
                counts[i] = 1;
                customKeys[i] = customKey;
                return i;
            }
        }
        return -1;
    }

    private static boolean sameCustom(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Remove one {@code state} from slots {@code [from, to)}. @return the affected slot, or -1. */
    public int take(int state, int from, int to) {
        return take(state, from, to, null);
    }

    /** Remove one item matching both state and custom key. @return the affected slot, or -1. */
    public int take(int state, int from, int to, String customKey) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] > 0 && sameCustom(customKeys[i], customKey)) {
                if (--counts[i] == 0) {
                    clear(i);
                }
                return i;
            }
        }
        return -1;
    }
}
