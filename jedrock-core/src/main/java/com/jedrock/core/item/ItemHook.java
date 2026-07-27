package com.jedrock.core.item;

import com.jedrock.api.player.Player;

/**
 * One programmable behaviour of a {@linkplain com.jedrock.api.item.CustomItem custom item} — what happens
 * when somebody uses it, hits with it, breaks a block with it, or takes it in hand.
 *
 * <p>Deliberately one shape for all of them, with the extras passed as a small context rather than a
 * signature per hook: an item's behaviours are written in JavaScript, where a fixed argument list is the
 * awkward part, and a context object is what a script wants anyway.
 */
@FunctionalInterface
public interface ItemHook {

    /**
     * Run the behaviour.
     *
     * @return {@code true} to consume the action — the core does not go on to do what it would have done
     *         (place the block, deal the damage, break the cell). Same meaning as cancelling the event this
     *         rides on, because that is exactly what it does.
     */
    boolean run(Player player, ItemContext context);

    /** What the action was about, beyond the player holding the item. Fields not relevant to a hook are 0. */
    record ItemContext(int x, int y, int z, int blockState, Player target) {

        /** For a hook with no place and no other player — {@code use} and {@code hold}. */
        public static ItemContext none() {
            return new ItemContext(0, 0, 0, 0, null);
        }

        /** For a hook about a block — {@code break}. */
        public static ItemContext at(int x, int y, int z, int blockState) {
            return new ItemContext(x, y, z, blockState, null);
        }

        /** For a hook about somebody else — {@code hit}. */
        public static ItemContext on(Player target) {
            return new ItemContext(0, 0, 0, 0, target);
        }
    }
}
