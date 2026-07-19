package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;

/**
 * Fired when a player reports a new position that passed the world bounds and the blind judge.
 * <b>Cancellable</b>: cancelling refuses the move — the player is snapped back to {@link #getFrom()} and
 * the report is dropped (they never left).
 *
 * <p>This sits on the hottest path in the server (a packet per client per movement), so the core posts it
 * only when {@link com.jedrock.api.event.EventBus#hasListeners a listener actually wants it} — with none
 * registered, movement costs exactly what it did before the event existed.
 */
public class PlayerMoveEvent extends CancellablePlayerEvent {

    private final Location from;
    private final Location to;

    public PlayerMoveEvent(Player player, Location from, Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    /** Where the player was — where they're snapped back to if this event is cancelled. */
    public Location getFrom() {
        return from;
    }

    /** Where the player is trying to move to. */
    public Location getTo() {
        return to;
    }
}
