package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player starts or stops sneaking. <b>Cancellable</b>: cancelling makes the server ignore the
 * toggle — the pose isn't recorded and isn't relayed to other players. (The sneaker's own client still
 * crouches locally; in a client-authoritative model the server can only refuse to <em>reflect</em> it.)
 */
public class PlayerToggleSneakEvent extends CancellablePlayerEvent {

    private final boolean sneaking;

    public PlayerToggleSneakEvent(Player player, boolean sneaking) {
        super(player);
        this.sneaking = sneaking;
    }

    /** {@code true} if the player is now sneaking, {@code false} if they stopped. */
    public boolean isSneaking() {
        return sneaking;
    }
}
