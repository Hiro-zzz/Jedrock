package com.jedrock.core.world;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.world.WorldSaveEvent;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The world's life on disk: load it, bake it once if there is nothing to load, and write it back.
 *
 * <p>Kept apart from the server because it is the whole of what "bake once" means in practice — after
 * {@link #prepare()} the generator is never consulted again, and every later boot is a file read. The
 * autosave is deliberately timid: it skips a world nobody has changed and refuses to start a second
 * save while one is still running, so a slow disk costs a skipped round rather than a pile of threads.
 */
public final class LevelManager {

    private static final JLogger LOGGER = JLogger.getLogger(LevelManager.class);

    private final CoreWorld world;
    private final EventBus events;
    private final AtomicBoolean saving = new AtomicBoolean(false);

    public LevelManager(CoreWorld world, EventBus events) {
        this.world = world;
        this.events = events;
    }

    /** Path of the level file for the world, e.g. {@code world/level.jdw}. */
    private Path levelFile() {
        return Path.of(world.getName(), "level.jdw");
    }

    /**
     * Bring the world up: load a saved level if present, then bake if it isn't generated yet (a fresh
     * world, or a pre-bake file). A freshly baked world is saved immediately. A file we can't read is
     * left untouched and we bake a fresh world over it in memory rather than clobber it.
     */
    public void prepare() {
        Path file = levelFile();
        if (Files.isRegularFile(file)) {
            try {
                LevelData meta = world.load(file);
                if (meta.seed() != world.getSeed()) {
                    LOGGER.warn("Saved world seed " + meta.seed() + " differs from configured seed "
                            + world.getSeed() + " — edits were made against the saved terrain");
                }
                LOGGER.info("Loaded world from " + file.toAbsolutePath()
                        + " (" + world.loadedSections() + " sections, "
                        + world.compressedSections() + " compressed)");
            } catch (IOException e) {
                LOGGER.error("Failed to load world from " + file.toAbsolutePath()
                        + " — leaving it untouched and generating a fresh world in memory", e);
            }
        } else {
            LOGGER.info("No saved world at " + file.toAbsolutePath() + " — generating a fresh one");
        }

        if (!world.isGenerated()) {
            LOGGER.info("Baking finite " + CoreWorld.BOUNDS_CHUNKS + "x" + CoreWorld.BOUNDS_CHUNKS
                    + "-chunk world (one-time)...");
            long t0 = System.nanoTime();
            world.bake();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOGGER.info("Baked " + world.loadedSections() + " sections ("
                    + world.compressedSections() + " compressed) in " + ms + " ms");
            save(); // persist the freshly baked world so the next run just loads it
        }
    }

    /** Save the world synchronously (used on shutdown). Best-effort: an I/O failure is logged, not fatal. */
    public void save() {
        Path file = levelFile();
        // Let plugins flush any world-tied state they want persisted alongside the terrain.
        if (events.hasListeners(WorldSaveEvent.class)) {
            events.post(new WorldSaveEvent(world));
        }
        try {
            world.save(file);
            LOGGER.info("Saved world to " + file.toAbsolutePath()
                    + " (" + world.loadedSections() + " sections)");
        } catch (IOException e) {
            LOGGER.error("Failed to save world to " + file.toAbsolutePath(), e);
        }
    }

    /** Save only if there is something to save — what shutdown wants. */
    public void saveIfDirty() {
        if (world.isDirty()) {
            save();
        }
    }

    /** Periodic autosave: runs {@link #save()} off-thread if the world changed, else skips. */
    public void autosave() {
        if (!world.isDirty()) {
            return; // nothing changed since the last save — don't rewrite the level file
        }
        if (!saving.compareAndSet(false, true)) {
            return; // a previous save is still running — skip this round rather than pile up
        }
        Thread t = new Thread(() -> {
            try {
                save();
            } finally {
                saving.set(false);
            }
        }, "jedrock-autosave");
        t.setDaemon(true);
        t.start();
    }
}
