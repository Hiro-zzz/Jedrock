package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player attacks (or interacts with) another entity — another player or a puppet.
 * <b>Cancellable</b>: cancelling drops the interaction before the core acts on it (no damage is dealt, no
 * puppet interaction callback fires).
 *
 * <p>The target is given as its server entity id rather than an {@code Entity} handle, because the core
 * resolves what that id refers to (a player, a puppet, or nothing) after the event — the same id every
 * edition sends on the wire.
 */
public class PlayerInteractEntityEvent extends CancellablePlayerEvent {

    private final long targetEntityId;

    public PlayerInteractEntityEvent(Player player, long targetEntityId) {
        super(player);
        this.targetEntityId = targetEntityId;
    }

    /** The server entity id of the thing being attacked / interacted with. */
    public long getTargetEntityId() {
        return targetEntityId;
    }
}
