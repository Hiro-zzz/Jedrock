package com.jedrock.core.world;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.BlockState;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory world implementation. A thin state holder over {@link BlockStorage};
 * it does not simulate physics, light or AI — the client renders the illusion.
 */
public final class CoreWorld implements World {

    /** Height of the procedural flat stone floor. Matches the spawn (y=64, one block above). */
    public static final int FLOOR_Y = 63;

    private final String name;
    private final UUID uniqueId;
    private final Dimension dimension;
    private final Location spawnLocation;
    private final BlockStorage storage = new BlockStorage();
    private final Set<Player> players = new CopyOnWriteArraySet<>();

    public CoreWorld(String name, Dimension dimension) {
        this.name = name;
        this.uniqueId = UUID.randomUUID();
        this.dimension = dimension;
        this.spawnLocation = new Location(this, 0.5, 64, 0.5);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUniqueId() {
        return uniqueId;
    }

    @Override
    public Dimension getDimension() {
        return dimension;
    }

    @Override
    public Collection<Player> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    @Override
    public Collection<Entity> getEntities() {
        // Players are the only entities modelled so far.
        return Collections.unmodifiableSet(players);
    }

    @Override
    public BlockState getBlockAt(int x, int y, int z) {
        int id = getBlockId(x, y, z);
        // Allocate a BlockState only on this abstract path; hot code uses getBlockId().
        return id == Blocks.AIR ? BlockState.AIR : new BlockState(id, "minecraft:unknown");
    }

    @Override
    public void setBlockAt(int x, int y, int z, BlockState state) {
        setBlockId(x, y, z, state == null ? Blocks.AIR : state.protocolId());
    }

    @Override
    public Location getSpawnLocation() {
        return spawnLocation;
    }

    // ===== Fast, allocation-free canonical block access =====

    @Override
    public int getBlockId(int x, int y, int z) {
        int stored = storage.getId(x, y, z);
        if (stored != Blocks.AIR) {
            return stored;              // a player edit overrides the base terrain
        }
        return y == FLOOR_Y ? Blocks.STONE : Blocks.AIR; // procedural flat floor
    }

    @Override
    public void setBlockId(int x, int y, int z, int id) {
        storage.setId(x, y, z, id);
    }

    // ===== Player membership (managed by the server on join/quit) =====

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }
}
