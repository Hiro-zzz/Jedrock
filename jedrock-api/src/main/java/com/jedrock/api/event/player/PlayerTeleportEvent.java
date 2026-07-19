package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;

/**
 * Fired when the server is about to teleport a player — a {@code /tp}, {@code /spawn}, or an API move (not
 * the ordinary walking a {@link PlayerMoveEvent} covers, and not a respawn, which is
 * {@link PlayerRespawnEvent}). <b>Cancellable</b>: cancelling leaves the player where they are. The
 * {@link #getTo() destination} is mutable, so a listener can redirect the teleport instead of vetoing it.
 */
public class PlayerTeleportEvent extends CancellablePlayerEvent {

    private final Location from;
    private Location to;

    public PlayerTeleportEvent(Player player, Location from, Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    /** Where the player is now. */
    public Location getFrom() {
        return from;
    }

    /** Where they will be moved to — change it to redirect the teleport. */
    public Location getTo() {
        return to;
    }

    public void setTo(Location to) {
        this.to = to;
    }
}
