package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired just before a player is kicked (via {@code Player.kick(reason)}), while they are still connected.
 * <b>Cancellable</b>: cancelling calls off the kick and leaves the player online. A listener may also
 * {@linkplain #setReason(String) rewrite} the reason shown to the client.
 */
public class PlayerKickEvent extends CancellablePlayerEvent {

    private String reason;

    public PlayerKickEvent(Player player, String reason) {
        super(player);
        this.reason = reason;
    }

    /** The disconnect reason shown to the client (rewritable). */
    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
