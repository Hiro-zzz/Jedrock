package com.jedrock.core.inventory;

/**
 * The item a player is "carrying" on the mouse cursor while an inventory window is open — client-side
 * state the server must mirror and resync (Java shows it via a Set Slot to window -1 / slot -1). Empty
 * when {@code state == 0}.
 */
public final class Cursor {

    private int state; // canonical (id<<4)|meta, 0 = empty
    private int count;

    public int state() {
        return state;
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return state == 0 || count <= 0;
    }

    public void set(int state, int count) {
        if (state == 0 || count <= 0) {
            clear();
        } else {
            this.state = state;
            this.count = count;
        }
    }

    public void clear() {
        state = 0;
        count = 0;
    }
}
