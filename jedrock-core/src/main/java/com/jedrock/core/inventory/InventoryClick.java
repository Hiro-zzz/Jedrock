package com.jedrock.core.inventory;

/**
 * The server-authoritative logic for a window click: how the {@link Cursor} and a {@link Container} slot
 * change on a left / right / shift click. Pure and edition-agnostic — the network layer translates each
 * edition's click packet into these operations, applies them, and resyncs the client. A trimmed subset
 * of vanilla's menu semantics (no drag, no double-click), enough for a usable inventory and chest.
 */
public final class InventoryClick {

    private InventoryClick() {}

    /**
     * A normal click (mouse) at {@code slot}. {@code right} picks up half / places one; left picks up
     * all / places all / swaps. The server is authoritative — it never trusts the client's item claim.
     */
    public static void normal(Container c, Cursor cur, int slot, boolean right) {
        int sState = c.stateAt(slot);
        int sCount = c.countAt(slot);

        if (cur.isEmpty()) {
            if (sState == 0) {
                return; // empty slot, empty cursor — nothing to do
            }
            if (right) {
                int take = (sCount + 1) / 2;          // pick up half (rounded up)
                cur.set(sState, take);
                c.set(slot, sState, sCount - take);
            } else {
                cur.set(sState, sCount);              // pick up the whole stack
                c.clear(slot);
            }
            return;
        }

        // Cursor holds items.
        if (sState == 0) {                             // place into an empty slot
            if (right) {
                c.set(slot, cur.state(), 1);
                cur.set(cur.state(), cur.count() - 1);
            } else {
                c.set(slot, cur.state(), cur.count());
                cur.clear();
            }
        } else if (sState == cur.state()) {            // same item — merge up to a full stack
            int space = Container.MAX_STACK - sCount;
            if (space <= 0) {
                return; // slot already full — a no-op (don't swap identical items)
            }
            int move = right ? 1 : Math.min(space, cur.count());
            move = Math.min(move, Math.min(space, cur.count()));
            c.set(slot, sState, sCount + move);
            cur.set(cur.state(), cur.count() - move);
        } else {                                       // different item — swap slot and cursor
            c.set(slot, cur.state(), cur.count());
            cur.set(sState, sCount);
        }
    }

    /**
     * A shift click at {@code slot}: quick-move the whole stack there into {@code [otherFrom, otherTo)}
     * (hotbar ↔ main for the player window; container ↔ player for a chest). Moves as much as fits.
     */
    public static void shift(Container c, int slot, int otherFrom, int otherTo) {
        shiftTo(c, slot, c, otherFrom, otherTo);
    }

    /**
     * Quick-move the whole stack at {@code src[slot]} into a <em>different</em> container's range
     * {@code dst[from, to)} (chest ↔ player). Moves as much as fits; the remainder stays in the source.
     */
    public static void shiftTo(Container src, int slot, Container dst, int from, int to) {
        int state = src.stateAt(slot);
        int count = src.countAt(slot);
        if (state == 0) {
            return;
        }
        while (count > 0 && dst.give(state, from, to) >= 0) {
            count--;
        }
        src.set(slot, state, count); // whatever didn't fit stays
    }
}
