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

    public Container(int size) {
        this.states = new int[size];
        this.counts = new int[size];
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
        if (state == 0 || count <= 0) {
            states[slot] = 0;
            counts[slot] = 0;
        } else {
            states[slot] = state;
            counts[slot] = count;
        }
    }

    public void clear(int slot) {
        states[slot] = 0;
        counts[slot] = 0;
    }

    /**
     * Add one {@code state} into slots {@code [from, to)}, stacking onto a matching slot (up to
     * {@link #MAX_STACK}) or filling the first empty one. @return the affected slot, or -1 if it didn't fit.
     */
    public int give(int state, int from, int to) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] < MAX_STACK) {
                counts[i]++;
                return i;
            }
        }
        for (int i = from; i < to; i++) {
            if (states[i] == 0) {
                states[i] = state;
                counts[i] = 1;
                return i;
            }
        }
        return -1;
    }

    /** Remove one {@code state} from slots {@code [from, to)}. @return the affected slot, or -1. */
    public int take(int state, int from, int to) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] > 0) {
                if (--counts[i] == 0) {
                    states[i] = 0;
                }
                return i;
            }
        }
        return -1;
    }
}
