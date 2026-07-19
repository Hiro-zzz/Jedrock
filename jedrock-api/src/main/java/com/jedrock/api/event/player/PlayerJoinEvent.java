package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired as a player joins, before the welcome and roster broadcasts. <b>Cancellable</b>: a listener that
 * cancels it refuses the join — the core rolls back the state it added and disconnects the client with
 * {@link #getJoinMessage()} (or a default) as the reason.
 */
public class PlayerJoinEvent extends CancellablePlayerEvent {

    private String joinMessage;

    public PlayerJoinEvent(Player player) {
        super(player);
    }

    /** The message shown if the join is cancelled (a kick reason), or {@code null} for a default. */
    public String getJoinMessage() {
        return joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }
}
