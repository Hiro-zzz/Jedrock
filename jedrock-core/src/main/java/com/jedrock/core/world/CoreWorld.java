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
        int state = getBlockId(x, y, z);
        // Allocate a BlockState only on this abstract path; hot code uses getBlockId().
        return state == Blocks.AIR ? BlockState.AIR : new BlockState(state, "minecraft:unknown");
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
     * marker or the generator would just put it back. Bit 12 is set, so it sits just outside the
     * 12-bit {@code (id << 4) | meta} state range and can never collide with a real placed state.
     */
    private static final int REMOVED = 0x1000;

    @Override
    public int getBlockId(int x, int y, int z) {
        int stored = storage.getId(x, y, z);
        if (stored == REMOVED) {
            return Blocks.AIR;          // a player broke a (possibly natural) block here
        }
        if (stored != Blocks.AIR) {
            return stored;              // a player placed a block here (packed state)
        }
        return generatedBlock(x, y, z); // procedural terrain
    }

    /** Classify a coordinate into a block state from the surface height: grass / dirt / stone / air. */
    private int generatedBlock(int x, int y, int z) {
        return generatedBlock(y, surfaceHeight(x, z));
    }

    /** Same classification, but with the column's surface height already known (hot-path helper). */
    private static int generatedBlock(int y, int surface) {
        if (y < 0 || y > 255) {
            return Blocks.AIR;
        }
        if (y > surface) {
            return Blocks.AIR;
        }
        // Natural terrain is all meta-0, so each state is simply the id shifted into place.
        if (y == surface) {
            return Blocks.state(Blocks.GRASS, 0);
        }
        return y >= surface - DIRT_DEPTH ? Blocks.state(Blocks.DIRT, 0) : Blocks.state(Blocks.STONE, 0);
    }

    /**
     * Fast bulk section read: one storage lookup for the whole section and one terrain-height
     * evaluation per column (reused across the 16 y-layers), with no per-block map lookup or
     * boxing. Equivalent to calling {@link #getBlockId} for every cell, overlay sentinel included.
     */
    @Override
    public boolean fillSection(int chunkX, int sectionY, int chunkZ, short[] out) {
        short[] stored = storage.section(chunkX, sectionY, chunkZ);
        int baseX = chunkX << 4;
        int baseY = sectionY << 4;
        int baseZ = chunkZ << 4;
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // One height eval per column (reused across the 16 y-layers); noise is allocation-free.
                int surface = terrain.surfaceHeight(baseX + x, baseZ + z);
                for (int y = 0; y < 16; y++) {
                    int idx = BlockStorage.index(x, y, z);
                    int s = stored == null ? Blocks.AIR : (stored[idx] & 0xFFFF);
                    int id;
                    if (s == REMOVED) {
                        id = Blocks.AIR;            // a player broke a (possibly natural) block here
                    } else if (s != Blocks.AIR) {
                        id = s;                     // a player placed a block here
                    } else {
                        id = generatedBlock(baseY + y, surface); // procedural terrain
                    }
                    out[idx] = (short) id;
                    if (id != Blocks.AIR) {
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /**
     * Surface height at a column. Recomputed on demand from the generator — the noise is cheap and
     * allocation-free, so this stores nothing (no cache to box keys into or leak memory through).
     * The chunk-serialization hot path calls the generator directly via {@link #fillSection}.
     */
    public int surfaceHeight(int x, int z) {
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public void setBlockId(int x, int y, int z, int state) {
        // Store the explicit-air sentinel so a broken natural block stays broken (plain air in the
        // overlay reads as "not stored" and would fall through to the generated terrain again).
        storage.setId(x, y, z, state == Blocks.AIR ? REMOVED : state);
    }

    // ===== Player membership (managed by the server on join/quit) =====

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }
}
