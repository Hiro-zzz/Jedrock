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
     * Current health in half-heart points. Full is {@link #getMaxHealth()} (20 = 10 hearts). Health is
     * server-authoritative — the client only reports that it was hit; the server decides the number.
     */
    int getHealth();

    /** Full health in half-heart points (20 = 10 hearts). */
    int getMaxHealth();

    /**
     * Set health directly, clamped to {@code 0..}{@link #getMaxHealth()}, and refresh the client's health
     * HUD. Setting it to 0 does not itself trigger the death/respawn flow — that lives in the damage path.
     */
    void setHealth(int health);

    /** {@code true} while the player is crouching. */
    boolean isSneaking();

    /** {@code true} while the player is sprinting. */
    boolean isSprinting();

    /** {@code true} while the player is using an item (eating, drinking, blocking, drawing a bow). */
    boolean isUsingItem();

    /**
     * Give one item of the canonical block/item {@code state} ({@code (id << 4) | meta}) to the player's
     * storage inventory, stacking onto a match or filling the first empty slot, and refresh that slot on the
     * client. Only meaningful in survival — creative players carry the creative menu, not this inventory.
     *
     * @return {@code true} if it fit, {@code false} if the storage inventory was full
     */
    boolean giveItem(int state);

    /**
     * The player's remote network address (IP:port or an edition-specific string). A convenience over
     * {@code getConnection().getAddress()}.
     */
    String getAddress();

    /**
     * Kick with a message. Abstract - actual disconnect is implementation detail. Fires a cancellable
     * {@code PlayerKickEvent}; a listener may cancel it (leaving the player online) or rewrite the reason.
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
