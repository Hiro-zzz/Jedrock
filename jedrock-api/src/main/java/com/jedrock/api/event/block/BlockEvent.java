package com.jedrock.api.event.block;

import com.jedrock.api.event.player.CancellablePlayerEvent;
import com.jedrock.api.player.Player;

/**
 * Base for a cancellable block event a player caused. Absorbs the block coordinates and canonical state so
 * the concrete events don't each re-declare them — the same lift {@link CancellablePlayerEvent} does for
 * the player and cancellation flag. Coordinates are block coordinates; {@link #getState()} is the canonical
 * {@code (id << 4) | meta} state the event concerns (what's broken, placed, or clicked).
 */
public abstract class BlockEvent extends CancellablePlayerEvent {

    private final int x;
    private final int y;
    private final int z;
    private final int state;

    protected BlockEvent(Player player, int x, int y, int z, int state) {
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

    /** The canonical {@code (id << 4) | meta} state of the block this event concerns. */
    public int getState() {
        return state;
    }
}
