package com.jedrock.api.entity;

import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.UUID;

/**
 * Base entity abstraction. Keep this as thin as possible.
 */
public interface Entity {

    UUID getUniqueId();

    /**
     * Server-assigned numeric id, unique per entity for the lifetime of the server.
     * Protocols address the entity with it on the wire (JE entity id / PE runtime id),
     * so it must never collide with another live entity's id.
     */
    long getEntityId();

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
