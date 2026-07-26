package com.jedrock.core.plugin;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.Particle;
import com.jedrock.api.world.Sound;
import com.jedrock.api.world.Weather;
import com.jedrock.api.world.World;

import java.util.Arrays;
import java.util.Locale;

/**
 * The {@code world} object a script sees — block-level access to the shared world. Every write goes
 * through the same path as a player's edit, so it lands in storage, marks the world dirty for
 * autosave, and is broadcast to every online client in its own protocol — a script builds something
 * and a Java player and a Bedrock player both watch it appear.
 *
 * <p>Blocks are addressed as a bare legacy id plus 4-bit metadata (wool colour, wood type, …), the
 * same numbers both editions speak; meta defaults to 0 wherever it's omitted.
 *
 * <pre>{@code
 *   world.setBlock(x, y, z, 1);                  // stone
 *   world.setBlock(x, y, z, 35, 14);             // red wool
 *   world.fill(x, y, z, x + 4, y + 3, z + 4, 20); // a 5×4×5 glass box
 *   const ground = world.getHighestY(x, z);      // build on top of the terrain
 *   if (world.getBlock(x, y, z) === 0) { ... }   // air check
 * }</pre>
 *
 * Shared by every plugin (unlike the per-plugin {@code events} / {@code scheduler} objects) — the
 * world is one, and an edit has no owner to unwind on reload.
 */
public final class ScriptWorld {

    private final World world;

    ScriptWorld(World world) {
        this.world = world;
    }

    /** The world's name (today always {@code "world"}). */
    public String getName() {
        return world.getName();
    }

    /** The bare block id at {@code (x, y, z)} — 0 is air (and everything outside the world). */
    public int getBlock(int x, int y, int z) {
        return Blocks.idOf(world.getBlockId(x, y, z));
    }

    /** The 4-bit metadata (variant) at {@code (x, y, z)} — 0 for a plain block. */
    public int getMeta(int x, int y, int z) {
        return Blocks.metaOf(world.getBlockId(x, y, z));
    }

    /** Set a plain (meta-0) block. Id 0 breaks the block. Broadcast to every client. */
    public void setBlock(int x, int y, int z, int id) {
        setBlock(x, y, z, id, 0);
    }

    /** Set a block with an explicit variant, e.g. {@code setBlock(x, y, z, 35, 14)} — red wool. */
    public void setBlock(int x, int y, int z, int id, int meta) {
        if (!Blocks.isKnown(id)) {
            throw new IllegalArgumentException("block id out of range 0..255: " + id);
        }
        world.setBlock(x, y, z, id, meta);
    }

    /** Fill the box between the two corners (inclusive, any order) with a plain block. */
    public int fill(int x1, int y1, int z1, int x2, int y2, int z2, int id) {
        return fill(x1, y1, z1, x2, y2, z2, id, 0);
    }

    /**
     * Fill the box between the two corners with {@code id:meta}. Unchanged cells are skipped, so
     * refilling doesn't re-send anything. Returns how many blocks actually changed. Each changed
     * block is one packet per client — meant for modest structures, not terrain-scale rewrites.
     */
    public int fill(int x1, int y1, int z1, int x2, int y2, int z2, int id, int meta) {
        if (!Blocks.isKnown(id)) {
            throw new IllegalArgumentException("block id out of range 0..255: " + id);
        }
        return world.fill(x1, y1, z1, x2, y2, z2, Blocks.state(id, meta));
    }

    /** Y of the highest non-air block in the column, or -1 for all-air — for building on the surface. */
    public int getHighestY(int x, int z) {
        return world.getHighestBlockY(x, z);
    }

    /** Legacy biome id (1 plains, 4 forest, 5 taiga, 35 savanna) at the column. */
    public int getBiome(int x, int z) {
        return world.getBiome(x, z);
    }

    /** The world spawn point. */
    public Location getSpawn() {
        return world.getSpawnLocation();
    }

    /** Move the world spawn point — used for future joins, respawns and {@code /spawn}. */
    public void setSpawn(double x, double y, double z) {
        world.setSpawnLocation(new Location(world, x, y, z));
    }

    /** Whether {@code (x, z)} is inside the finite world — outside, reads are air and writes are dropped. */
    public boolean isInside(double x, double z) {
        return world.isInsideBounds(x, z);
    }

    /**
     * Play a canonical sound at a position, audible to every player, cross-edition. The name is a
     * {@link Sound} constant, case-insensitive: {@code 'levelup'}, {@code 'explode'}, {@code 'click'}…
     */
    public void playSound(String sound, double x, double y, double z) {
        playSound(sound, x, y, z, 1.0, 1.0);
    }

    /** As {@link #playSound(String, double, double, double)} with volume and pitch (1 = normal, best-effort per edition). */
    public void playSound(String sound, double x, double y, double z, double volume, double pitch) {
        world.playSound(parse(Sound.class, sound), x, y, z, (float) volume, (float) pitch);
    }

    /**
     * Draw one canonical particle at a position, visible to every player, cross-edition. The name is a
     * {@link Particle} constant, case-insensitive: {@code 'heart'}, {@code 'flame'}, {@code 'portal'}…
     */
    public void spawnParticle(String particle, double x, double y, double z) {
        spawnParticle(particle, x, y, z, 1, 0.0);
    }

    /** A burst: {@code count} particles scattered within ±{@code spread} blocks (PE caps the count per burst). */
    public void spawnParticle(String particle, double x, double y, double z, int count, double spread) {
        world.spawnParticle(parse(Particle.class, particle), x, y, z, count, spread);
    }

    /** The current weather, as a lower-case name: {@code 'clear'}, {@code 'rain'} or {@code 'thunder'}. */
    public String getWeather() {
        return world.getWeather().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Set the weather (case-insensitive: {@code 'clear'} / {@code 'rain'} / {@code 'thunder'}) —
     * broadcast to every player, cross-edition, and pushed to later joiners. Purely cosmetic; it
     * stays until set again (schedule your own cycle if you want one).
     */
    public void setWeather(String weather) {
        world.setWeather(parse(Weather.class, weather));
    }

    /** Parse a case-insensitive enum name, failing with the full list of valid names — a script-friendly error. */
    static <E extends Enum<E>> E parse(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName().toLowerCase(Locale.ROOT)
                    + " '" + name + "' — one of: " + Arrays.toString(type.getEnumConstants()));
        }
    }
}
