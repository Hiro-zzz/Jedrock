package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired after a player has disconnected and been removed from the server state. Not cancellable — by the
 * time it fires the connection is already gone. Carries the <b>quit announcement</b> broadcast to everyone
 * still online; a listener may restyle it, replace it, or suppress it by setting it {@code null} or empty.
 */
public class PlayerQuitEvent extends PlayerEvent {

    private String quitMessage;

    public PlayerQuitEvent(Player player) {
        super(player);
    }

    /** The quit announcement shown to the remaining players; {@code null} or empty means no broadcast. */
    public String getQuitMessage() {
        return quitMessage;
    }

    public void setQuitMessage(String quitMessage) {
        this.quitMessage = quitMessage;
    }
}
