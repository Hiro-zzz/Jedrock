package com.jedrock.api.event.player;

import com.jedrock.api.event.Cancellable;
import com.jedrock.api.event.Event;

import java.util.UUID;

/**
 * Fired the moment a player's login reaches the core, <em>before</em> any state is set up for them — the
 * gate a whitelist or ban check belongs on. <b>Cancellable</b>: cancelling refuses the connection and
 * disconnects the client with {@link #getKickReason()} (a default is used if none was set), and nothing
 * (registry entry, avatar, world entry) is ever created.
 *
 * <p>Unlike most events this carries no {@link com.jedrock.api.player.Player} — there isn't one yet — only
 * the identity the login presented. It runs before {@link PlayerJoinEvent}, which fires once the player is
 * fully set up and is about the announcement rather than the gate.
 */
public final class PlayerLoginEvent implements Event, Cancellable {

    private final UUID uniqueId;
    private final String username;
    private final String address;
    private boolean cancelled;
    private String kickReason;

    public PlayerLoginEvent(UUID uniqueId, String username, String address) {
        this.uniqueId = uniqueId;
        this.username = username;
        this.address = address;
    }

    /** The account's unique id. */
    public UUID getUniqueId() {
        return uniqueId;
    }

    /** The name the login presented (already stripped of formatting codes). */
    public String getUsername() {
        return username;
    }

    /** The remote address the connection came from. */
    public String getAddress() {
        return address;
    }

    /** The disconnect message shown if the login is refused; {@code null} falls back to a default. */
    public String getKickReason() {
        return kickReason;
    }

    public void setKickReason(String kickReason) {
        this.kickReason = kickReason;
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
