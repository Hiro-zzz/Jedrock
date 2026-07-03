package com.jedrock.api.world;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * World abstraction. Extremely lightweight view of a dimension/level.
 */
public interface World {

    String getName();

    UUID getUniqueId();

    Dimension getDimension();

    /**
     * Players currently in this world. Should be cheap to obtain.
     */
    Collection<Player> getPlayers();

    /**
     * Entities in world (living + non-living).
     */
    Collection<Entity> getEntities();

    /**
     * Basic block access - implementations may use lazy chunk loading.
     */
    BlockState getBlockAt(int x, int y, int z);

    void setBlockAt(int x, int y, int z, BlockState state);

    /**
     * Fast, allocation-free block access using canonical block ids (0 = air).
     * Protocol layers map these ids to their own palette when serializing chunks,
     * so Java and Bedrock render the same world.
     */
    int getBlockId(int x, int y, int z);

    void setBlockId(int x, int y, int z, int blockId);

    /**
     * Spawn location for this world.
     */
    Location getSpawnLocation();
}
