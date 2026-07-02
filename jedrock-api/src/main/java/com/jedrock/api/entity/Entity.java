package com.jedrock.api.entity;

import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.UUID;

/**
 * Base entity abstraction. Keep this as thin as possible.
 */
public interface Entity {

    UUID getUniqueId();

    World getWorld();

    Location getLocation();

    void setLocation(Location location);

    /**
     * Remove/despawn this entity.
     */
    void remove();

    boolean isAlive();

    /**
     * Protocol-agnostic entity type identifier (e.g. "minecraft:player" or numeric).
     */
    String getType();
}
