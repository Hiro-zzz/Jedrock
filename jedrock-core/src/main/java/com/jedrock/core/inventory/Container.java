package com.jedrock.core.inventory;

import com.jedrock.api.item.Enchantments;

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
    /**
     * Per slot, this <em>particular</em> stack's own state — an opaque string nobody here interprets, or
     * {@code null}. The key says <em>which</em> item a stack is and is shared by every stack that names it;
     * this says what has happened to <em>this one</em> ("charges left: 3"), which is the one thing a shared
     * definition has nowhere to put. Written and read only by whoever set it, and compared verbatim: two
     * stacks merge only if their data is equal, so a half-spent wand never dissolves into a full one.
     */
    protected final String[] customData;
    /**
     * Per slot, what that stack is enchanted with — never null, {@link Enchantments#NONE} for the
     * overwhelming majority. Part of a stack's identity like the two above, and for the same reason: it
     * has to survive every move, and two stacks that differ in it are different items.
     */
    protected final Enchantments[] enchantments;

    public Container(int size) {
        this.states = new int[size];
        this.counts = new int[size];
        this.customKeys = new String[size];
        this.customData = new String[size];
        this.enchantments = new Enchantments[size];
        java.util.Arrays.fill(this.enchantments, Enchantments.NONE);
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

    /** Per-stack data per slot ({@code null} = none), parallel to {@link #states()}. */
    public String[] customData() {
        return customData;
    }

    /** This slot's own per-stack data, or {@code null}. */
    public String customDataAt(int slot) {
        return slot >= 0 && slot < customData.length ? customData[slot] : null;
    }

    /** What the stack in this slot is enchanted with; never null. */
    public Enchantments enchantmentsAt(int slot) {
        return slot >= 0 && slot < enchantments.length && enchantments[slot] != null
                ? enchantments[slot] : Enchantments.NONE;
    }

    /**
     * Enchant whatever is already in {@code slot}, leaving the item itself alone — the counterpart of
     * {@link #setCustomData}, and ignored for an empty slot for the same reason: an enchantment belongs
     * to a stack, and there is no stack to belong to.
     */
    public void setEnchantments(int slot, Enchantments value) {
        if (slot >= 0 && slot < enchantments.length && !isEmpty(slot)) {
            enchantments[slot] = value == null ? Enchantments.NONE : value;
        }
    }

    /**
     * Attach per-stack data to whatever is already in {@code slot}, leaving the item itself alone —
     * how "this sword has one charge left" is written after the sword is already somewhere. Ignored for
     * an empty slot: data belongs to a stack, and there is no stack to belong to.
     */
    public void setCustomData(int slot, String data) {
        if (slot >= 0 && slot < customData.length && !isEmpty(slot)) {
            customData[slot] = data;
        }
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

    /**
     * Set a slot to an <b>ordinary</b> stack; {@code state == 0} or {@code count <= 0} clears it.
     *
     * <p>Note what this means for a slot that held a custom item: it doesn't any more. That is the honest
     * reading of "put this ordinary item here", and it is deliberate — the alternative, quietly keeping the
     * old key, is how a frostblade's identity would end up on a stack of dirt. Code that means "the same
     * stack, fewer of them" wants {@link #setCount} instead, and code that is moving a stack wants the
     * overload that carries its identity along.
     */
    public void set(int slot, int state, int count) {
        set(slot, state, count, null, null);
    }

    /** Set a slot to a custom item with no per-stack data of its own. */
    public void set(int slot, int state, int count, String customKey) {
        set(slot, state, count, customKey, null);
    }

    /** Set a slot outright, keeping no enchantments — a stack that never had any. */
    public void set(int slot, int state, int count, String customKey, String data) {
        set(slot, state, count, customKey, data, Enchantments.NONE);
    }

    /** Set a slot outright, identity and all — what every move of a whole stack goes through. */
    public void set(int slot, int state, int count, String customKey, String data, Enchantments ench) {
        if (state == 0 || count <= 0) {
            clear(slot);
        } else {
            states[slot] = state;
            counts[slot] = count;
            customKeys[slot] = customKey;
            customData[slot] = data;
            enchantments[slot] = ench == null ? Enchantments.NONE : ench;
        }
    }

    /**
     * Change how many are in {@code slot} without touching what they are — a stack being split, spent or
     * merged into. {@code count <= 0} clears the slot; an empty slot has nothing to count.
     *
     * <p>This exists because {@code set(slot, sameState, fewer)} reads like the same thing and isn't: it
     * rebuilds the stack as an ordinary one, which is exactly how a custom item used to lose its name by
     * being picked up and put down again.
     */
    public void setCount(int slot, int count) {
        if (count <= 0 || states[slot] == 0) {
            clear(slot);
        } else {
            counts[slot] = count;
        }
    }

    public void clear(int slot) {
        states[slot] = 0;
        counts[slot] = 0;
        customKeys[slot] = null;
        customData[slot] = null;
        enchantments[slot] = Enchantments.NONE;
    }

    /**
     * Add one {@code state} into slots {@code [from, to)}, stacking onto a matching slot (up to
     * {@link #MAX_STACK}) or filling the first empty one. @return the affected slot, or -1 if it didn't fit.
     */
    public int give(int state, int from, int to) {
        return give(state, from, to, null, null);
    }

    /** Add one custom item with no per-stack data. */
    public int give(int state, int from, int to, String customKey) {
        return give(state, from, to, customKey, null);
    }

    /** Add one item, identity and all — the form a move of an enchanted stack goes through. */
    public int give(int state, int from, int to, String customKey, String data, Enchantments ench) {
        return giveStack(state, from, to, customKey, data, ench);
    }

    /**
     * Add one item into slots {@code [from, to)}. A stack only merges with one of the <b>same state, the
     * same custom key and the same per-stack data</b>, so a named sword never quietly stacks with an
     * ordinary one — they are different items even though they are drawn the same — and two of the same
     * named item merge only while nothing has happened to one of them that hasn't happened to the other.
     *
     * @return the affected slot, or -1 if it didn't fit
     */
    public int give(int state, int from, int to, String customKey, String data) {
        return giveStack(state, from, to, customKey, data, Enchantments.NONE);
    }

    /** The one implementation: everything above funnels here so the merge test is written once. */
    private int giveStack(int state, int from, int to, String customKey, String data,
                          Enchantments ench) {
        if (state == 0) {
            return -1;
        }
        Enchantments enchant = ench == null ? Enchantments.NONE : ench;
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] < MAX_STACK && sameStack(i, customKey, data)
                    && enchantmentsAt(i).equals(enchant)) {
                counts[i]++;
                return i;
            }
        }
        for (int i = from; i < to; i++) {
            if (states[i] == 0) {
                states[i] = state;
                counts[i] = 1;
                customKeys[i] = customKey;
                customData[i] = data;
                enchantments[i] = enchant;
                return i;
            }
        }
        return -1;
    }

    /** Whether the stack in {@code slot} carries exactly this identity — the merge test, in one place. */
    public boolean sameStack(int slot, String customKey, String data) {
        return sameCustom(customKeys[slot], customKey) && sameCustom(customData[slot], data);
    }

    private static boolean sameCustom(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Remove one {@code state} from slots {@code [from, to)}. @return the affected slot, or -1. */
    public int take(int state, int from, int to) {
        return take(state, from, to, null, null);
    }

    /** Remove one item matching both state and custom key, whatever its per-stack data. */
    public int take(int state, int from, int to, String customKey) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] > 0 && sameCustom(customKeys[i], customKey)) {
                takeOneAt(i);
                return i;
            }
        }
        return -1;
    }

    /** Remove one item matching state, custom key <em>and</em> data. @return the affected slot, or -1. */
    public int take(int state, int from, int to, String customKey, String data) {
        if (state == 0) {
            return -1;
        }
        for (int i = from; i < to; i++) {
            if (states[i] == state && counts[i] > 0 && sameStack(i, customKey, data)) {
                takeOneAt(i);
                return i;
            }
        }
        return -1;
    }

    private void takeOneAt(int slot) {
        if (--counts[slot] == 0) {
            clear(slot);
        }
    }
}
