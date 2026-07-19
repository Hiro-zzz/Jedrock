package com.jedrock.api.event.block;

import com.jedrock.api.player.Player;

/**
 * Fired when a player places a block, after the world-bounds and reach checks but before the world is
 * changed. <b>Cancellable</b>: cancelling means nothing is placed — the core skips the world write and
 * re-sends the real (pre-placement) block to the placer, so their client reverts the ghost block.
 *
 * <p>{@link #getState()} is the canonical state being placed, {@link #getReplacedState()} the state that
 * was there before.
 */
public class BlockPlaceEvent extends BlockEvent {

    private final int replacedState;

    public BlockPlaceEvent(Player player, int x, int y, int z, int state, int replacedState) {
        super(player, x, y, z, state);
        this.replacedState = replacedState;
    }

    /** The canonical state that was there before (usually air). */
    public int getReplacedState() {
        return replacedState;
    }
}
