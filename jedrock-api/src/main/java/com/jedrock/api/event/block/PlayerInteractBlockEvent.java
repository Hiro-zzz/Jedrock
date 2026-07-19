package com.jedrock.api.event.block;

import com.jedrock.api.event.player.CancellablePlayerEvent;
import com.jedrock.api.player.Player;

/**
 * Fired when a player right-clicks a block. <b>Cancellable</b>: cancelling consumes the click — the core
 * neither opens the block (a chest) nor places the held item against it, so the interaction does nothing.
 *
 * <p>This fires for a right-click on <em>any</em> block, so a listener can gate access to a block or attach
 * custom behaviour to one the core otherwise treats as inert. Coordinates are the clicked block's.
 */
public class PlayerInteractBlockEvent extends CancellablePlayerEvent {

    private final int x;
    private final int y;
    private final int z;
    private final int state;

    public PlayerInteractBlockEvent(Player player, int x, int y, int z, int state) {
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

    /** The canonical {@code (id << 4) | meta} state of the clicked block. */
    public int getState() {
        return state;
    }
}
