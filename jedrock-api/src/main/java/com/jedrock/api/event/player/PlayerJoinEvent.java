package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired as a player joins, before the roster broadcasts. Carries the <b>join announcement</b> broadcast to
 * everyone else — a listener may restyle it, replace it, or suppress it by setting it to {@code null} or
 * empty (the same contract as the death message). <b>Cancellable</b>: a listener that cancels refuses the
 * join — the core rolls back the state it added and disconnects the client (to gate a connection with a
 * custom kick reason, use {@code PlayerLoginEvent} instead).
 */
public class PlayerJoinEvent extends CancellablePlayerEvent {

    private String joinMessage;

    public PlayerJoinEvent(Player player) {
        super(player);
    }

    /** The join announcement shown to other players; {@code null} or empty means no broadcast. */
    public String getJoinMessage() {
        return joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }
}
