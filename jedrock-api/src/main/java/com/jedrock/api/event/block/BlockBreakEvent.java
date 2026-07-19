package com.jedrock.api.event.block;

import com.jedrock.api.player.Player;

/**
 * Fired when a player breaks a block, after the world-bounds and reach checks but before the world is
 * changed. <b>Cancellable</b>: cancelling leaves the block where it is — the core skips the world write
 * and re-sends the real block to the breaker, so their client re-shows it.
 *
 * <p>{@link #getState()} is the canonical state of the block being broken.
 */
public class BlockBreakEvent extends BlockEvent {

    public BlockBreakEvent(Player player, int x, int y, int z, int state) {
        super(player, x, y, z, state);
    }
}
