package com.jedrock.api.event.block;

import com.jedrock.api.event.player.CancellablePlayerEvent;
import com.jedrock.api.player.Player;

/**
 * Fired when a player breaks a block, after the world-bounds and reach checks but before the world is
 * changed. <b>Cancellable</b>: cancelling leaves the block where it is — the core skips the world write
 * and re-sends the real block to the breaker, so their client re-shows it.
 *
 * <p>Coordinates are block coordinates; {@link #getState()} is the canonical {@code (id << 4) | meta}
 * value of the block being broken.
 */
public class BlockBreakEvent extends CancellablePlayerEvent {

    private final int x;
    private final int y;
    private final int z;
    private final int state;

    public BlockBreakEvent(Player player, int x, int y, int z, int state) {
        super(player);
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    /** The canonical state of the block being broken. */
    public int getState() {
        return state;
    }
}
