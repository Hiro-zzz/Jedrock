package com.jedrock.api.event.player;

import com.jedrock.api.event.Cancellable;
import com.jedrock.api.event.Event;
import com.jedrock.api.player.Player;

/**
 * Example event. Real events will be added as needed.
 */
public class PlayerJoinEvent implements Event, Cancellable {

    private final Player player;
    private boolean cancelled = false;
    private String joinMessage;

    public PlayerJoinEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public String getJoinMessage() {
        return joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
