package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

/**
 * Fired when a player is about to be moved to a <b>different world</b> — the moment the terrain under
 * them changes, not merely their coordinates. A teleport within one world fires only
 * {@link PlayerTeleportEvent}; a cross-world move fires that first and then this, so a listener that
 * only cares about "someone entered the nether" doesn't have to compare worlds on every {@code /tp}.
 *
 * <p><b>Cancellable</b>: cancelling leaves the player in the world they are in, standing where they
 * were. The {@link #getTo() destination} is mutable, so a listener can redirect the arrival — put a
 * player entering the nether on a safe ledge, or bounce them back to a lobby.
 */
public class PlayerWorldChangeEvent extends CancellablePlayerEvent {

    private final World from;
    private Location to;

    public PlayerWorldChangeEvent(Player player, World from, Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    /** The world the player is leaving. */
    public World getFrom() {
        return from;
    }

    /** The world they are entering — {@code getTo().world()}. */
    public World getToWorld() {
        return to.world();
    }

    /** Where in the new world they will arrive — change it to redirect (including to a third world). */
    public Location getTo() {
        return to;
    }

    public void setTo(Location to) {
        this.to = to;
    }
}
