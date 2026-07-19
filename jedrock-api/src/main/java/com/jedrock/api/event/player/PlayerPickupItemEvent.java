package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a survival player is about to pick up a mined block into their inventory — the illusionist
 * server has no dropped-item entities, so mining a block hands its item straight to the miner, and this is
 * that moment. <b>Cancellable</b>: cancelling means the block still breaks but the item is not collected.
 *
 * <p>{@link #getState()} is the canonical {@code (id << 4) | meta} of the item picked up. Only fires in
 * survival (creative has the full menu and collects nothing).
 */
public class PlayerPickupItemEvent extends CancellablePlayerEvent {

    private final int state;

    public PlayerPickupItemEvent(Player player, int state) {
        super(player);
        this.state = state;
    }

    /** The canonical state of the item being collected. */
    public int getState() {
        return state;
    }
}
