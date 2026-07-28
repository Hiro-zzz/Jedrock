package com.jedrock.core.world;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.World;
import com.jedrock.api.world.WorldTemplate;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every world the server has, and the only thing allowed to make one.
 *
 * <p>A world here is a folder: {@code <name>/level.jdw} beside the process, holding its own terrain,
 * chests, spawn and — since format v5 — its own dimension. That makes the set of worlds <b>discoverable
 * rather than registered</b>: at boot this scans for folders with a level file and reads each header,
 * so there is no second list to keep in step with the files and no way for the two to disagree. Copying
 * a world folder in is all it takes to add one.
 *
 * <p>Creation goes through a {@link WorldTemplate} — a recipe, not a saved world. The manager owns the
 * named templates (four built in, scripts may register more), turns one into a {@link CoreWorld}, bakes
 * or loads it through its own {@link LevelManager}, and wires the two things a live world needs: the
 * event bus (so a weather change can be vetoed) and the block-change relay that pushes an edit to every
 * client <em>in that world</em> — which is the whole reason the relay could not stay a single lambda on
 * the server.
 */
public final class WorldManager {

    private static final JLogger LOGGER = JLogger.getLogger(WorldManager.class);

    /** The level file inside a world's folder; a folder without one is not a world. */
    static final String LEVEL_FILE = "level.jdw";

    /** Folder names we never treat as worlds, however they got a level file. */
    private static final List<String> RESERVED = List.of("plugins", "target", "logs", "src");

    private final EventBus events;
    private final PlayerRegistry players;
    /** Where world folders live — the working directory in production, a temp dir in a test. */
    private final Path root;

    /** lower-cased name → world. Insertion-ordered so listings are stable. */
    private final Map<String, CoreWorld> worlds = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, LevelManager> levels = new ConcurrentHashMap<>();
    private final Map<String, WorldTemplate> templates = Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile CoreWorld defaultWorld;

    public WorldManager(EventBus events, PlayerRegistry players, Path root) {
        this.events = events;
        this.players = players;
        this.root = root;
        for (WorldTemplate t : List.of(WorldTemplate.overworld(), WorldTemplate.nether(),
                WorldTemplate.smallOverworld(), WorldTemplate.smallNether(), WorldTemplate.flatland())) {
            templates.put(t.name().toLowerCase(Locale.ROOT), t);
        }
    }

    // ===== Templates =====

    /** Register (or replace) a named template. Scripts declare their own through the {@code worlds} global. */
    public void registerTemplate(WorldTemplate template) {
        templates.put(template.name().toLowerCase(Locale.ROOT), template);
    }

    /** A template by name, or empty. */
    public Optional<WorldTemplate> template(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(templates.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Every registered template, built-ins first. */
    public Collection<WorldTemplate> templates() {
        synchronized (templates) {
            return List.copyOf(templates.values());
        }
    }

    // ===== The worlds =====

    /** Every loaded world, the default first. */
    public Collection<CoreWorld> all() {
        synchronized (worlds) {
            return List.copyOf(worlds.values());
        }
    }

    /** A loaded world by name, case-insensitively. */
    public Optional<CoreWorld> get(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(worlds.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The world a player joins into, and the fallback when another world goes away. */
    public CoreWorld getDefault() {
        return defaultWorld;
    }

    /** The level manager owning a world's file — used for a targeted save. */
    public Optional<LevelManager> levelOf(World world) {
        return world == null ? Optional.empty()
                : Optional.ofNullable(levels.get(world.getName().toLowerCase(Locale.ROOT)));
    }

    /**
     * Bring up the world every player joins into. Created from the overworld template if its folder
     * isn't there yet, loaded from disk if it is — the configured seed only decides the former, since a
     * baked world's terrain is the file's, not the config's.
     */
    public CoreWorld openDefault(String name, long seed) {
        return openDefault(name, WorldTemplate.overworld().withSeed(seed));
    }

    /** As {@link #openDefault(String, long)} from an explicit template — how a test gets a small one. */
    public CoreWorld openDefault(String name, WorldTemplate template) {
        CoreWorld world = openOrCreate(name, template);
        this.defaultWorld = world;
        return world;
    }

    /**
     * Load every other world folder found beside the default. Called once at boot, after
     * {@link #openDefault}: a world that existed last run is back before the first player can ask for it.
     *
     * @return how many worlds were loaded
     */
    public int discover() {
        int loaded = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                if (RESERVED.contains(name.toLowerCase(Locale.ROOT))
                        || worlds.containsKey(name.toLowerCase(Locale.ROOT))
                        || !Files.isRegularFile(dir.resolve(LEVEL_FILE))) {
                    continue;
                }
                try {
                    openExisting(name);
                    loaded++;
                } catch (IOException | RuntimeException e) {
                    LOGGER.error("Could not open the world in " + dir.toAbsolutePath()
                            + " — skipping it; the folder is left untouched", e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not scan " + root.toAbsolutePath() + " for worlds", e);
        }
        return loaded;
    }

    /**
     * Create a brand-new world from a template.
     *
     * @param name     the world's name, which is also its folder
     * @param template the recipe
     * @param seed     the seed to grow it from, or {@code null} to take the template's (or a random one)
     * @throws IllegalArgumentException if the name is unusable
     * @throws IllegalStateException    if a world (or a folder holding one) already goes by that name
     */
    public CoreWorld create(String name, WorldTemplate template, Long seed) {
        String key = validateName(name);
        if (worlds.containsKey(key)) {
            throw new IllegalStateException("A world named '" + name + "' is already loaded");
        }
        if (Files.isRegularFile(levelFile(name))) {
            throw new IllegalStateException("A world already exists on disk at " + levelFile(name)
                    + " — load it instead of creating it");
        }
        long actualSeed = seed != null ? seed
                : template.seed() != null ? template.seed()
                : ThreadLocalRandom.current().nextLong();
        CoreWorld world = new CoreWorld(name, template.dimension(), actualSeed, template.sizeChunks());
        LOGGER.info("Creating world '" + name + "' (" + template.dimension() + ", seed " + actualSeed
                + ", " + template.sizeChunks() + "x" + template.sizeChunks() + " chunks, template '"
                + template.name() + "')");
        register(world, template.decorate());
        return world;
    }

    /** Load a world that already has a folder, taking its kind and seed from the level file's header. */
    public CoreWorld openExisting(String name) throws IOException {
        String key = validateName(name);
        CoreWorld already = worlds.get(key);
        if (already != null) {
            return already;
        }
        LevelData meta = LevelIO.readHeader(levelFile(name));
        Dimension dimension = dimensionOf(meta.dimensionId());
        int bounds = meta.boundsChunksX() > 0 ? meta.boundsChunksX() : CoreWorld.BOUNDS_CHUNKS;
        CoreWorld world = new CoreWorld(name, dimension, meta.seed(), bounds);
        register(world, true);
        return world;
    }

    /** Load it if the folder is there, create it from the template if it isn't. */
    public CoreWorld openOrCreate(String name, WorldTemplate template) {
        Optional<CoreWorld> loaded = get(name);
        if (loaded.isPresent()) {
            return loaded.get();
        }
        if (Files.isRegularFile(levelFile(name))) {
            try {
                return openExisting(name);
            } catch (IOException e) {
                LOGGER.error("Failed to read the level header of '" + name
                        + "' — falling back to the template, leaving the file untouched", e);
            }
        }
        return create(name, template, template.seed());
    }

    /**
     * Take a world out of memory, saving it first. The default world never unloads, and neither does one
     * with players still in it — the caller is expected to move them out first.
     *
     * @return {@code true} if it was unloaded
     */
    public boolean unload(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        CoreWorld world = worlds.get(key);
        if (world == null || world == defaultWorld) {
            return false;
        }
        for (CorePlayer p : players.online()) {
            if (p.getWorld() == world) {
                return false; // someone is standing in it
            }
        }
        LevelManager level = levels.remove(key);
        if (level != null) {
            level.saveIfDirty();
        }
        worlds.remove(key);
        world.setChangeListener(null);
        LOGGER.info("Unloaded world '" + world.getName() + "'");
        return true;
    }

    // ===== Persistence across every world =====

    /** Autosave each dirty world (each on its own background write; a clean world is skipped). */
    public void autosaveAll() {
        for (LevelManager level : levels.values()) {
            level.autosave();
        }
    }

    /** Save every dirty world synchronously — what shutdown wants. */
    public void saveAllIfDirty() {
        for (LevelManager level : levels.values()) {
            level.saveIfDirty();
        }
    }

    // ===== Wiring =====

    /** Load-or-bake the world, then give it the two things a live world needs. */
    private void register(CoreWorld world, boolean decorate) {
        String key = world.getName().toLowerCase(Locale.ROOT);
        world.setEventBus(events); // so a weather change can be vetoed wherever it came from
        LevelManager level = new LevelManager(world, events, root);
        levels.put(key, level);
        worlds.put(key, world);
        level.prepare(decorate);
        // Only now, with the bake done: registering earlier would fire the relay millions of times for
        // a generation nobody is watching. From here every edit — a player's, a script's, the api's —
        // reaches each client standing in THIS world, and no client standing in another.
        world.setChangeListener((x, y, z, state) -> {
            for (CorePlayer p : players.online()) {
                if (p.getWorld() == world) {
                    p.getConnection().sendBlockChange(x, y, z, state);
                }
            }
        });
    }

    private Path levelFile(String name) {
        return root.resolve(name).resolve(LEVEL_FILE);
    }

    private static Dimension dimensionOf(int id) {
        for (Dimension d : Dimension.values()) {
            if (d.getId() == id) {
                return d;
            }
        }
        return Dimension.OVERWORLD;
    }

    /**
     * A world name is a folder name, so it is checked like one: letters, digits, underscore and hyphen
     * only. This is the boundary a script's string crosses to become a path, and the one place that can
     * stop {@code worlds.create('../../etc')} from meaning anything.
     */
    static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("world name must not be blank");
        }
        if (name.length() > 32) {
            throw new IllegalArgumentException("world name must be at most 32 characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                throw new IllegalArgumentException(
                        "world name may only contain letters, digits, '_' and '-': " + name);
            }
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("'" + name + "' is reserved and can't be a world name");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /** Every loaded world as the api sees them — for {@code Server.getWorlds()}. */
    public Collection<World> asApi() {
        List<World> out = new ArrayList<>(worlds.size());
        synchronized (worlds) {
            out.addAll(worlds.values());
        }
        return Collections.unmodifiableList(out);
    }
}
