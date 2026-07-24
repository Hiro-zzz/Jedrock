package com.jedrock.core.world;

import com.jedrock.api.entity.Entity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.BlockState;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.io.IOException;
import java.nio.file.Path;
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

    /**
     * Finite world extent, in chunks per side, centred on the origin. The world is a bounded
     * "decoration" (see the world-generation roadmap): 48×48 chunks = 768×768 blocks. Recorded in the
     * level file today; enforced (edge wall) once the bake/edge phases land.
     */
    public static final int BOUNDS_CHUNKS = 48;

    private final String name;
    private final UUID uniqueId;
    private final Dimension dimension;
    private final long seed;
    private volatile Location spawnLocation;
    private final BlockStorage storage = new BlockStorage();
    private final BiomeStorage biomes = new BiomeStorage();
    private final TerrainGenerator terrain;
    private final BiomeGenerator biomeGen;
    private final Set<Player> players = new CopyOnWriteArraySet<>();

    /**
     * True once the one-time world bake has run (or a baked world was loaded); persisted in the level
     * file. While false the world serves procedural terrain on demand with an edit overlay; once true
     * the generator is never consulted again and storage <em>is</em> the whole world.
     */
    private volatile boolean generated = false;

    /** Set by any edit (and by bake); cleared by a successful save, so autosave can skip a clean world. */
    private volatile boolean dirty = false;

    public CoreWorld(String name, Dimension dimension) {
        this(name, dimension, DEFAULT_SEED);
    }

    public CoreWorld(String name, Dimension dimension, long seed) {
        this.name = name;
        this.uniqueId = UUID.randomUUID();
        this.dimension = dimension;
        this.seed = seed;
        this.terrain = new TerrainGenerator(seed);
        this.biomeGen = new BiomeGenerator(seed);
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

    @Override
    public void setSpawnLocation(Location location) {
        // Re-bind to this world so getSpawnLocation always returns a point in this world.
        this.spawnLocation = new Location(this, location.x(), location.y(), location.z(),
                location.yaw(), location.pitch());
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
        if (generated) {
            // Baked world: storage IS the world — air is genuinely air, the generator is never touched.
            return storage.getId(x, y, z);
        }
        // Pre-bake overlay: player edits layered over on-demand procedural terrain.
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
        if (generated) {
            // Baked world: one bulk read straight out of storage (handles uniform / mixed / all-air).
            return storage.readSection(chunkX, sectionY, chunkZ, out);
        }
        Object stored = storage.section(chunkX, sectionY, chunkZ);
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
                    int s = BlockStorage.cellOf(stored, idx); // compact-/full-/null-aware read
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
    public int getBiome(int x, int z) {
        // Baked world reads the frozen map; pre-bake resolves it on demand from the generator.
        return generated ? biomes.getBiome(x, z) : biomeGen.biomeAt(x, z).id();
    }

    @Override
    public void fillBiomes(int chunkX, int chunkZ, byte[] out) {
        if (generated) {
            biomes.fill(chunkX, chunkZ, out);
            return;
        }
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[(z << 4) | x] = (byte) biomeGen.biomeAt(baseX + x, baseZ + z).id();
            }
        }
    }

    /**
     * Observes every committed block write — the server wires this to broadcast each edit to all
     * online clients, cross-edition, so a script/API edit renders exactly like a player's.
     */
    @FunctionalInterface
    public interface BlockChangeListener {
        void blockChanged(int x, int y, int z, int state);
    }

    /** Registered by the server after bake/load, so the one-time bake never floods it. */
    private volatile BlockChangeListener changeListener;

    public void setChangeListener(BlockChangeListener listener) {
        this.changeListener = listener;
    }

    @Override
    public void setBlockId(int x, int y, int z, int state) {
        // The finite world can't grow: a write outside the bounds (or the vertical range) is dropped
        // here at the storage boundary, so no API or script path can allocate sections past the edge.
        if (y < 0 || y > 255 || !isInsideBounds(x, z)) {
            return;
        }
        if (generated) {
            // Full world in storage: air genuinely clears the cell (there's no terrain to fall back to).
            storage.setId(x, y, z, state);
        } else {
            // Overlay: store the explicit-air sentinel so a broken natural block stays broken (plain
            // air in the overlay reads as "not stored" and would fall through to the generated terrain).
            storage.setId(x, y, z, state == Blocks.AIR ? REMOVED : state);
        }
        dirty = true;
        BlockChangeListener listener = changeListener;
        if (listener != null) {
            listener.blockChanged(x, y, z, state);
        }
    }

    // ===== Persistence =====

    public long getSeed() {
        return seed;
    }

    /** Number of allocated storage sections — for logging and tests. */
    public int loadedSections() {
        return storage.loadedSections();
    }

    /** How many of the {@link #loadedSections} are stored compressed (paletted) — for logging. */
    public int compressedSections() {
        return storage.compressedSections();
    }

    // ===== Finite bounds (the world edge) =====

    /** Inclusive minimum block coordinate on the x and z axes. */
    public int minBound() {
        return (-BOUNDS_CHUNKS / 2) << 4;
    }

    /** Exclusive maximum block coordinate on the x and z axes. */
    public int maxBound() {
        return (BOUNDS_CHUNKS - BOUNDS_CHUNKS / 2) << 4;
    }

    /** Whether a column {@code (x, z)} lies inside the finite world; outside is void (the edge wall). */
    @Override
    public boolean isInsideBounds(double x, double z) {
        return x >= minBound() && x < maxBound() && z >= minBound() && z < maxBound();
    }

    /**
     * Feet-Y at or below which a player is falling in the void and takes void damage. Terrain lives in
     * {@code y ∈ [0, 255]}; below the {@code y = 0} floor is open air, with a few blocks of grace so a
     * player standing on the lowest floor block never triggers it — only an actual drop past the world.
     */
    public static final int VOID_LEVEL = -5;

    /** Whether {@code y} (feet position) is low enough that the player is taking void damage. */
    public boolean isInVoid(double y) {
        return y <= VOID_LEVEL;
    }

    /** True once this world has been baked (or a baked world was loaded). */
    public boolean isGenerated() {
        return generated;
    }

    /** True if the world changed since the last successful save — lets autosave skip a clean world. */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * One-time bake of the finite world into storage using the world's {@link #BOUNDS_CHUNKS} bounds:
     * resolve every cell of the bounded region — procedural terrain plus any pre-bake overlay edits —
     * into full sections, store them, mark the world {@code generated}, then run the
     * {@link WorldDecorator} passes (caves / lakes / trees) over the baked storage. After this the
     * generator is never consulted again; {@link #getBlockId} / {@link #fillSection} read storage only,
     * so a coordinate outside the baked region reads air (the world is finite). A no-op if already baked.
     */
    public void bake() {
        bake(BOUNDS_CHUNKS, true);
    }

    /** Bake a square of {@code chunksPerSide}×{@code chunksPerSide} chunks centred on the origin. */
    void bake(int chunksPerSide) {
        bake(chunksPerSide, true);
    }

    /** Bake, optionally running the {@link WorldDecorator} passes ({@code decorate == false} = bare terrain). */
    void bake(int chunksPerSide, boolean decorate) {
        if (generated) {
            return;
        }
        int half = chunksPerSide / 2;
        int lo = -half;
        int hi = chunksPerSide - half; // exclusive
        short[] scratch = new short[4096];
        for (int cx = lo; cx < hi; cx++) {
            for (int cz = lo; cz < hi; cz++) {
                // Freeze this chunk's biome map (generated is still false, so this reads the generator).
                byte[] bio = new byte[256];
                fillBiomes(cx, cz, bio);
                biomes.putChunk(cx, cz, bio);
                for (int sy = 0; sy < 16; sy++) {
                    // fillSection resolves terrain + any loaded edits; store a private copy so the
                    // reused scratch buffer doesn't alias the section.
                    if (fillSection(cx, sy, cz, scratch)) {
                        storage.putSection(cx, sy, cz, scratch.clone());
                    }
                }
            }
        }
        generated = true; // storage is now the world; decoration reads/writes it in generated mode
        if (decorate) {
            WorldDecorator.decorate(this, seed, lo, hi);
        }
        dirty = true;
    }

    /**
     * Write this world (metadata + the {@link BlockStorage} overlay of player edits) to {@code file}.
     * Everything not stored is still procedural terrain, reproduced from the seed on load.
     */
    public void save(Path file) throws IOException {
        Location s = spawnLocation;
        LevelData meta = new LevelData(LevelIO.FORMAT_VERSION, seed, BOUNDS_CHUNKS, BOUNDS_CHUNKS,
                generated, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        // Clear before writing so an edit arriving mid-save re-marks the world dirty for the next cycle.
        dirty = false;
        LevelIO.save(file, meta, storage, biomes, containers);
    }

    /**
     * Load a saved world into this instance's storage and adopt its {@code generated} flag. Call once
     * at startup before any player joins. Returns the file's header so the caller can validate it
     * (e.g. warn on a seed mismatch).
     */
    public LevelData load(Path file) throws IOException {
        LevelData meta = LevelIO.load(file, storage, biomes, containers);
        this.generated = meta.generated();
        return meta;
    }

    // ===== Block containers (chests) =====
    //
    // A chest is a block plus a 27-slot container keyed by its position. In-memory only for now — chest
    // contents do NOT yet survive a restart (level-file persistence is the next step).

    private final java.util.Map<Long, com.jedrock.core.inventory.Container> containers =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /** The 27-slot container backing the chest at {@code (x, y, z)}, created empty on first access. */
    public com.jedrock.core.inventory.Container getChestContainer(int x, int y, int z) {
        return containers.computeIfAbsent(packPos(x, y, z), k -> new com.jedrock.core.inventory.Container(27));
    }

    /** Drop a chest's container (its block was broken); the contents are lost — no item entities. */
    public void removeChestContainer(int x, int y, int z) {
        containers.remove(packPos(x, y, z));
    }

    /** Mark the world changed (e.g. a chest's contents were edited) so autosave / shutdown persists it. */
    public void markDirty() {
        dirty = true;
    }

    // ===== Player membership (managed by the server on join/quit) =====

    public void addPlayer(Player player) {
        players.add(player);
        // A late joiner walks into the weather everyone else already sees.
        if (weather != com.jedrock.api.world.Weather.CLEAR) {
            player.getConnection().sendWeather(weather);
        }
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }

    // ===== Weather (client-side scenery — one enum, no simulation) =====

    private volatile com.jedrock.api.world.Weather weather = com.jedrock.api.world.Weather.CLEAR;

    @Override
    public com.jedrock.api.world.Weather getWeather() {
        return weather;
    }

    @Override
    public void setWeather(com.jedrock.api.world.Weather weather) {
        if (weather == null || weather == this.weather) {
            return; // no change — don't re-send the sky to everyone
        }
        this.weather = weather;
        for (Player p : players) {
            p.getConnection().sendWeather(weather);
        }
    }
}
