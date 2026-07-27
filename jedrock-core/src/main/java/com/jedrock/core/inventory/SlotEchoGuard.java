package com.jedrock.core.inventory;

/**
 * Tells a client's own inventory <em>move</em> apart from its <em>echo</em> of a move the server made.
 *
 * <p>Bedrock owns its inventory window: the client moves an item in its GUI and reports the new slot
 * value, and that report is the only notice the server ever gets — so it has to be applied, or the next
 * full resync (closing the inventory) puts the item back where the server still has it. But the retail
 * 1.1.5 client also reports a slot the <em>server</em> just changed, carrying the value it held
 * <em>before</em>: right after a chest deposit that echo re-added the stack the deposit had consumed, so
 * the item ended up in the chest and in the hand at once.
 *
 * <p>The two reports are identical in content — an echo is a stale move, not a malformed one. What
 * separates them is time: an echo answers a push that has only just gone out. So every server-authored
 * push arms a short guard on that slot, and a report arriving inside it is read as the echo and answered
 * with a correction instead of being trusted. Past the window the client is believed.
 *
 * <p>Same shape, and the same reason, as {@code PeEditDebounce} on the block path: this client reports
 * one action more than once. The clock is passed in rather than read, so the rule is deterministically
 * testable.
 *
 * <p>Not thread-safe — a player's inventory is touched only from their own network thread.
 */
public final class SlotEchoGuard {

    /** Default window, overridable with {@code -Djedrock.pe.slotEchoGuardMs=<ms>}; {@code 0} = guard off. */
    public static final long DEFAULT_WINDOW_NANOS =
            Long.getLong("jedrock.pe.slotEchoGuardMs", 750L) * 1_000_000L;

    private final long windowNanos;
    /** Wall-clock of the last server-authored push per slot ({@code 0} = never pushed). */
    private final long[] pushedNanos;

    public SlotEchoGuard(int slots) {
        this(slots, DEFAULT_WINDOW_NANOS);
    }

    public SlotEchoGuard(int slots, long windowNanos) {
        this.windowNanos = windowNanos;
        this.pushedNanos = new long[slots];
    }

    /** Record that the server has just pushed {@code slot} to the client. Out-of-range slots are ignored. */
    public void arm(int slot, long now) {
        if (slot >= 0 && slot < pushedNanos.length) {
            pushedNanos[slot] = now;
        }
    }

    /** Record a push of every slot — a full inventory resync. */
    public void armAll(long now) {
        java.util.Arrays.fill(pushedNanos, now);
    }

    /** True while {@code slot} is still inside the echo window of a server-authored push. */
    public boolean isGuarded(int slot, long now) {
        if (windowNanos <= 0 || slot < 0 || slot >= pushedNanos.length) {
            return false;
        }
        long pushed = pushedNanos[slot];
        return pushed != 0L && now - pushed < windowNanos;
    }
}
