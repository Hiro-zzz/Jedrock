package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when damage takes a player to zero health, just before the (deliberately primitive) silent respawn
 * at spawn. Not cancellable — death has already happened; a listener that wants to prevent it cancels the
 * {@link PlayerDamageEvent} that would be lethal instead.
 *
 * <p>The {@link #getDeathMessage() death message} is mutable — change it to restyle the broadcast, or set it
 * to {@code null} / empty to suppress the announcement entirely.
 */
public class PlayerDeathEvent extends PlayerEvent {

    private final DamageCause cause;
    private String deathMessage;

    public PlayerDeathEvent(Player player, DamageCause cause, String deathMessage) {
        super(player);
        this.cause = cause;
        this.deathMessage = deathMessage;
    }

    /** What killed the player. */
    public DamageCause getCause() {
        return cause;
    }

    /** The line broadcast to everyone; {@code null} or empty suppresses it. */
    public String getDeathMessage() {
        return deathMessage;
    }

    public void setDeathMessage(String deathMessage) {
        this.deathMessage = deathMessage;
    }
}
