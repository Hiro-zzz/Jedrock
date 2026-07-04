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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory world implementation. A thin state holder over {@link BlockStorage} and a procedural
 * {@link TerrainGenerator}; it does not simulate physics, light or AI — the client renders the
 * illusion and collides against the ground we serialize to it.
 */
public final class CoreWorld implements World {

    /** Depth of the dirt layer below the grass surface; everything deeper is stone. */
    private static final int DIRT_DEPTH = 3;

    /** Default world seed — fixed so restarts reproduce the same terrain (until config lands). */
    private static final long DEFAULT_SEED = 0x5EED1EAFL;

    private final String name;
    private final UUID uniqueId;
    private final Dimension dimension;
    private final Location spawnLocation;
    private final BlockStorage storage = new BlockStorage();
    private final TerrainGenerator terrain;
    /** Lazily-remembered surface height per column (x,z) — computed once, then reused. */
    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();
    private final Set<Player> players = new CopyOnWriteArraySet<>();

    public CoreWorld(String name, Dimension dimension) {
        this(name, dimension, DEFAULT_SEED);
    }

    public CoreWorld(String name, Dimension dimension, long seed) {
        this.name = name;
        this.uniqueId = UUID.randomUUID();
        this.dimension = dimension;
        this.terrain = new TerrainGenerator(seed);
        // Spawn standing on top of the generated ground at the origin column.
        int surface = surfaceHeight(0, 0);
        this.spawnLocation = new Location(this, 0.5, surface + 1, 0.5);
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

    /**
     * Overlay sentinel for a block a player explicitly removed. Plain air in the overlay means
     * "nothing stored" (fall through to terrain), so breaking a natural block needs a distinct
     * marker or the generator would just put it back. Out of the real block-id range.
     */
    private static final int REMOVED = 0x0FFF;

    @Override
    public int getBlockId(int x, int y, int z) {
        int stored = storage.getId(x, y, z);
        if (stored == REMOVED) {
            return Blocks.AIR;          // a player broke a (possibly natural) block here
        }
        if (stored != Blocks.AIR) {
            return stored;              // a player placed a block here
        }
        return generatedBlock(x, y, z); // procedural terrain
    }

    /** Classify a coordinate into a block from the surface height: grass / dirt / stone / air. */
    private int generatedBlock(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return Blocks.AIR;
        }
        int surface = surfaceHeight(x, z);
        if (y > surface) {
            return Blocks.AIR;
        }
        if (y == surface) {
            return Blocks.GRASS;
        }
        return y >= surface - DIRT_DEPTH ? Blocks.DIRT : Blocks.STONE;
    }

    /** Surface height at a column, computed once by the generator and then cached. */
    public int surfaceHeight(int x, int z) {
        return heightCache.computeIfAbsent(columnKey(x, z), k -> terrain.surfaceHeight(x, z));
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public void setBlockId(int x, int y, int z, int id) {
        // Store the explicit-air sentinel so a broken natural block stays broken (plain air in the
        // overlay reads as "not stored" and would fall through to the generated terrain again).
        storage.setId(x, y, z, id == Blocks.AIR ? REMOVED : id);
    }

    // ===== Player membership (managed by the server on join/quit) =====

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }
}
