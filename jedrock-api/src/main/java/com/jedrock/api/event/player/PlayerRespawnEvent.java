package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;

/**
 * Fired when a player is about to respawn after dying, before they are moved there. Not cancellable — a
 * dead player has to land somewhere — but the {@link #getRespawnLocation() respawn location} is mutable,
 * so a listener can send them somewhere other than world spawn (a bed, an arena, a lobby).
 */
public class PlayerRespawnEvent extends PlayerEvent {

    private Location respawnLocation;

    public PlayerRespawnEvent(Player player, Location respawnLocation) {
        super(player);
        this.respawnLocation = respawnLocation;
    }

    /** Where the player will reappear — change it to respawn them elsewhere. */
    public Location getRespawnLocation() {
        return respawnLocation;
    }

    public void setRespawnLocation(Location respawnLocation) {
        this.respawnLocation = respawnLocation;
    }
}
