package com.jedrock.api.event.block;

import com.jedrock.api.event.player.CancellablePlayerEvent;
import com.jedrock.api.player.Player;

/**
 * Fired when a player places a block, after the world-bounds and reach checks but before the world is
 * changed. <b>Cancellable</b>: cancelling means nothing is placed — the core skips the world write and
 * re-sends the real (pre-placement) block to the placer, so their client reverts the ghost block.
 *
 * <p>Coordinates are block coordinates; {@link #getState()} is the canonical {@code (id << 4) | meta}
 * value being placed, and {@link #getReplacedState()} the state that was there before.
 */
public class BlockPlaceEvent extends CancellablePlayerEvent {

    private final int x;
    private final int y;
    private final int z;
    private final int state;
    private final int replacedState;

    public BlockPlaceEvent(Player player, int x, int y, int z, int state, int replacedState) {
        super(player);
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state;
        this.replacedState = replacedState;
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

    /** The canonical state being placed. */
    public int getState() {
        return state;
    }

    /** The canonical state that was there before (usually air). */
    public int getReplacedState() {
        return replacedState;
    }
}
