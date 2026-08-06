package com.jedrock.core.inventory;

/**
 * Carries a stack's identity across a move the <em>client</em> made and the server only hears about.
 *
 * <p>Bedrock owns its inventory window. When a player drags a custom sword from one slot to another, the
 * server is not asked — it is told, in two separate slot reports: one slot is now empty, another now holds
 * item 276. Neither report mentions a custom-item key, because the wire has no field for one; the key lives
 * only in the server's own copy of the inventory. Applied literally, the pair means "the sword is gone and
 * an ordinary sword has appeared", which is how a named item used to lose its name to a drag.
 *
 * <p>So the emptying report leaves a <b>trail</b> — what was displaced, and when — and the next report that
 * puts that same item down claims it. The pairing is by state and by time, the same reasoning
 * {@link SlotEchoGuard} uses on the same wire: content cannot tell these reports apart, and there is
 * nothing else to go on. It is deliberately one stack deep, because one is what a drag is; a client
 * shuffling faster than the window is one that gets its second sword back plain, which is the same answer
 * as before this existed.
 *
 * <p>This is a rescue, not a mechanism — the Java window path moves stacks whole and never consults it.
 *
 * <p>Not thread-safe: a player's inventory is touched only from their own network thread.
 */
public final class CustomStackTrail {

    /** Default window, overridable with {@code -Djedrock.pe.stackTrailMs=<ms>}; {@code 0} = off. */
    public static final long DEFAULT_WINDOW_NANOS =
            Long.getLong("jedrock.pe.stackTrailMs", 750L) * 1_000_000L;

    /** What a client report displaced: the item it was, and the identity that came with it. */
    public record Displaced(String customKey, String customData,
                            com.jedrock.api.item.Enchantments enchantments) {

        public Displaced {
            enchantments = enchantments == null ? com.jedrock.api.item.Enchantments.NONE : enchantments;
        }
    }

    private final long windowNanos;
    private int state;
    private String customKey;
    private String customData;
    private com.jedrock.api.item.Enchantments enchantments = com.jedrock.api.item.Enchantments.NONE;
    private long at;

    public CustomStackTrail() {
        this(DEFAULT_WINDOW_NANOS);
    }

    public CustomStackTrail(long windowNanos) {
        this.windowNanos = windowNanos;
    }

    /**
     * Record that a client report has just taken a stack out of a slot. Only a stack with an identity
     * worth carrying is remembered — an ordinary item has nothing the destination can't work out itself.
     */
    public void displaced(int state, String customKey, String customData, long now) {
        displaced(state, customKey, customData, com.jedrock.api.item.Enchantments.NONE, now);
    }

    /** As above, for a stack whose identity includes what it is enchanted with. */
    public void displaced(int state, String customKey, String customData,
                          com.jedrock.api.item.Enchantments enchantments, long now) {
        boolean plain = customKey == null && customData == null
                && (enchantments == null || enchantments.isEmpty());
        if (windowNanos <= 0 || state == 0 || plain) {
            return;
        }
        this.state = state;
        this.customKey = customKey;
        this.customData = customData;
        this.enchantments = enchantments == null
                ? com.jedrock.api.item.Enchantments.NONE : enchantments;
        this.at = now;
    }

    /**
     * Claim the trail for a slot the client has just filled with {@code state}, or {@code null} if nothing
     * matching was displaced recently enough. Claiming consumes it: one displacement can only explain one
     * arrival, and a second arrival of the same item is a different stack.
     */
    public Displaced claim(int state, long now) {
        if (windowNanos <= 0 || this.state == 0 || this.state != state || now - at >= windowNanos) {
            return null;
        }
        Displaced claimed = new Displaced(customKey, customData, enchantments);
        clear();
        return claimed;
    }

    /** Forget whatever is remembered — a full resync makes any trail meaningless. */
    public void clear() {
        state = 0;
        customKey = null;
        customData = null;
        enchantments = com.jedrock.api.item.Enchantments.NONE;
        at = 0L;
    }
}
