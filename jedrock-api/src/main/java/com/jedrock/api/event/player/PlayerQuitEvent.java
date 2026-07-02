package com.jedrock.api.event.player;

import com.jedrock.api.event.Event;
import com.jedrock.api.player.Player;

/**
 * Fired after a player has disconnected and been removed from the server state.
 * Not cancellable — by the time it fires the connection is already gone.
 */
public class PlayerQuitEvent implements Event {

    private final Player player;
    private String quitMessage;

    public PlayerQuitEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public String getQuitMessage() {
        return quitMessage;
    }

    public void setQuitMessage(String quitMessage) {
        this.quitMessage = quitMessage;
    }
}
