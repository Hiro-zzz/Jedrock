package com.jedrock.core.inventory;

/**
 * The item a player is "carrying" on the mouse cursor while an inventory window is open — client-side
 * state the server must mirror and resync (Java shows it via a Set Slot to window -1 / slot -1). Empty
 * when {@code state == 0}.
 *
 * <p>It carries a stack's whole identity, not just its look. A cursor is where every window move passes
 * through, so anything it drops on the way is dropped for good: while this held only a state and a count,
 * picking a custom sword up and putting it down again was enough to turn it into the ordinary sword it is
 * drawn as.
 */
public final class Cursor {

    private int state; // canonical (id<<4)|meta, 0 = empty
    private int count;
    private String customKey;
    private String customData;
    private com.jedrock.api.item.Enchantments enchantments = com.jedrock.api.item.Enchantments.NONE;

    public int state() {
        return state;
    }

    public int count() {
        return count;
    }

    /** The custom-item key being carried, or {@code null} for an ordinary stack. */
    public String customKey() {
        return customKey;
    }

    /** The per-stack data being carried, or {@code null}. */
    public String customData() {
        return customData;
    }

    /** What the carried stack is enchanted with; never null, {@code NONE} for a plain one. */
    public com.jedrock.api.item.Enchantments enchantments() {
        return enchantments;
    }

    public boolean isEmpty() {
        return state == 0 || count <= 0;
    }

    /** Carry an ordinary stack. */
    public void set(int state, int count) {
        set(state, count, null, null, com.jedrock.api.item.Enchantments.NONE);
    }

    /** Carry a stack with an identity but no enchantments — what most stacks are. */
    public void set(int state, int count, String customKey, String customData) {
        set(state, count, customKey, customData, com.jedrock.api.item.Enchantments.NONE);
    }

    /** Carry a stack, identity and all — including what it is enchanted with. */
    public void set(int state, int count, String customKey, String customData,
                    com.jedrock.api.item.Enchantments enchantments) {
        if (state == 0 || count <= 0) {
            clear();
        } else {
            this.state = state;
            this.count = count;
            this.customKey = customKey;
            this.customData = customData;
            this.enchantments = enchantments == null
                    ? com.jedrock.api.item.Enchantments.NONE : enchantments;
        }
    }

    /**
     * Change how many are being carried without touching what they are — one placed out of the stack,
     * one taken into it. The counterpart of {@link Container#setCount}, and there for the same reason.
     */
    public void setCount(int count) {
        if (count <= 0 || state == 0) {
            clear();
        } else {
            this.count = count;
        }
    }

    public void clear() {
        state = 0;
        count = 0;
        customKey = null;
        customData = null;
        enchantments = com.jedrock.api.item.Enchantments.NONE;
    }
}
