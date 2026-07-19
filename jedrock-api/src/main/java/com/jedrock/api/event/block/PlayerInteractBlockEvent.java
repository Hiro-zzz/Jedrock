package com.jedrock.api.event.block;

import com.jedrock.api.player.Player;

/**
 * Fired when a player right-clicks a block. <b>Cancellable</b>: cancelling consumes the click — the core
 * neither opens the block (a chest) nor places the held item against it, so the interaction does nothing.
 *
 * <p>This fires for a right-click on <em>any</em> block, so a listener can gate access to a block or attach
 * custom behaviour to one the core otherwise treats as inert. {@link #getState()} is the clicked block.
 */
public class PlayerInteractBlockEvent extends BlockEvent {

    public PlayerInteractBlockEvent(Player player, int x, int y, int z, int state) {
        super(player, x, y, z, state);
    }
}
