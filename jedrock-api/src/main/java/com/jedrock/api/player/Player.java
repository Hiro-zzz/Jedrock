package com.jedrock.api.player;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.UUID;

/**
 * Absolute abstraction over a connected player.
 * Implementations should be extremely thin wrappers.
 * No direct access to packets or network inside this interface.
 */
public interface Player extends Entity {

    UUID getUniqueId();

    String getName();

    /**
     * The world the player is currently in.
     */
    World getWorld();

    /**
     * Current location. Implementations may use lazy position tracking.
     */
    Location getLocation();

    void teleport(Location location);

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    /**
     * Kick with a message. Abstract - actual disconnect is implementation detail.
     */
    void kick(String reason);

    /**
     * Send a raw chat/system message. Keep abstraction.
     */
    void sendMessage(String message);

    /**
     * @return true if the player is still connected
     */
    boolean isOnline();

    /**
     * Low-level connection handle if needed by higher modules.
     * Prefer not to expose - use only for extreme abstraction cases.
     */
    PlayerConnection getConnection();
}
