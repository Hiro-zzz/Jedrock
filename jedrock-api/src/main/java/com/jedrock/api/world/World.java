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
     * Set a block from a bare id and 4-bit metadata (wool colour, wood type, …) — sugar over
     * {@link #setBlockId} with the canonical packed state, so callers don't hand-roll the
     * {@code (id << 4) | meta} arithmetic. On a live server world the edit reaches every online
     * client, cross-edition, exactly like a player's edit.
     */
    default void setBlock(int x, int y, int z, int id, int meta) {
        setBlockId(x, y, z, Blocks.state(id, meta));
    }

    /**
     * Fill the axis-aligned box spanned by the two corners (inclusive, in any order) with one canonical
     * {@code state}. Cells already holding {@code state} are skipped, so refilling a region doesn't
     * re-broadcast unchanged blocks. Meant for modest script-driven structures (platforms, walls,
     * clearing a site) — each changed cell is one block edit, so a huge box means that many packets
     * per client. Returns the number of blocks actually changed.
     */
    default int fill(int x1, int y1, int z1, int x2, int y2, int z2, int state) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(0, Math.min(y1, y2)), maxY = Math.min(255, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int changed = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (getBlockId(x, y, z) != state) {
                        setBlockId(x, y, z, state);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Bulk-read one 16³ section into a caller-provided array — the chunk-serialization hot path,
     * built to avoid the per-block overhead (virtual call, height-cache boxing, one map lookup each)
     * of calling {@link #getBlockId} 4096 times.
     *
     * <p>{@code out} must have length 4096; on return {@code out[(y << 8) | (z << 4) | x]} holds the
     * canonical block id at section-local {@code (x, y, z)} ∈ [0, 16). Returns {@code true} if any
     * block is non-air, so an all-air section can be skipped by the caller.
     *
     * <p>The default implementation simply loops {@link #getBlockId}; {@link com.jedrock.api.world}
     * implementations backed by real storage should override it with a single section fetch and one
     * height evaluation per column.
     */
    default boolean fillSection(int chunkX, int sectionY, int chunkZ, short[] out) {
        int baseX = chunkX << 4, baseY = sectionY << 4, baseZ = chunkZ << 4;
        boolean any = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = getBlockId(baseX + x, baseY + y, baseZ + z);
                    out[(y << 8) | (z << 4) | x] = (short) id;
                    if (id != Blocks.AIR) {
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /**
     * Legacy Minecraft biome id (0..255) at a column. Protocol-agnostic: each edition's chunk
     * serializer maps it to its own wire form (a biome-id byte on Java / PE 1.1.5, a grass-tint colour
     * on PE 0.14). Defaults to {@code 1} (plains) so a minimal {@link World} needs no biome data.
     */
    default int getBiome(int x, int z) {
        return 1; // plains
    }

    /**
     * Bulk-read a chunk's 16×16 biome map into {@code out} (length 256), ordered {@code out[(z<<4)|x]}
     * — the chunk-serialization companion to {@link #fillSection}. The default loops {@link #getBiome};
     * storage-backed worlds override it with a single lookup.
     */
    default void fillBiomes(int chunkX, int chunkZ, byte[] out) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[(z << 4) | x] = (byte) getBiome(baseX + x, baseZ + z);
            }
        }
    }

    /**
     * The Y of the highest non-air block in a column, or {@code -1} for an all-air column. The default
     * scans down from the top with {@link #getBlockId}; a storage-backed world overrides it with its
     * surface-height cache. Handy for placing something safely on top of the terrain.
     */
    default int getHighestBlockY(int x, int z) {
        for (int y = 255; y >= 0; y--) {
            if (getBlockId(x, y, z) != Blocks.AIR) {
                return y;
            }
        }
        return -1;
    }

    /**
     * Whether the column {@code (x, z)} lies inside this world's bounds. The default says yes — an
     * unbounded world. A finite world overrides it; outside the bounds reads are air and writes are
     * dropped, so callers can probe before building near the edge.
     */
    default boolean isInsideBounds(double x, double z) {
        return true;
    }

    /**
     * The current weather. The default is an eternally clear minimal world; a real world overrides
     * both weather methods to store the state and broadcast changes.
     */
    default Weather getWeather() {
        return Weather.CLEAR;
    }

    /**
     * Set the weather, visible to every player in this world (and pushed to later joiners). Purely
     * cosmetic — no timer, no simulation; it stays until set again. Default: a no-op.
     */
    default void setWeather(Weather weather) {}

    /**
     * The time of day, in the 0–23999 ticks a Minecraft day has run on since forever: 0 is sunrise,
     * 6000 noon, 12000 sunset, 18000 midnight.
     *
     * <p>Like the weather, this is scenery rather than simulation. The server holds a number and tells
     * clients what it is; the <em>client</em> is what animates the sun across the sky between updates,
     * which is why a day passes here without anything on this side ticking to make it.
     */
    default long getTime() {
        return 0L;
    }

    /** Set the time of day, wrapping into 0–23999. Every player in this world sees the sky move. */
    default void setTime(long timeOfDay) {}

    /**
     * Whether the sun is moving. {@code false} freezes it where it is — for a lobby that is always noon,
     * or a horror map that is always midnight.
     *
     * <p>Freezing is the client's own mechanism rather than something enforced here: every edition has a
     * way of being told "this time, and stop counting", so the sky stops without the server sending
     * anything further.
     */
    default boolean isDaylightCycle() {
        return true;
    }

    default void setDaylightCycle(boolean cycling) {}

    /**
     * Play a canonical {@link Sound} at a position, audible to every player in this world — each
     * client renders it in its own protocol. Volume and pitch are best-effort per edition (1 = normal).
     */
    default void playSound(Sound sound, double x, double y, double z, float volume, float pitch) {
        for (Player p : getPlayers()) {
            p.getConnection().playSound(sound, x, y, z, volume, pitch);
        }
    }

    /** As {@link #playSound(Sound, double, double, double, float, float)} at normal volume and pitch. */
    default void playSound(Sound sound, double x, double y, double z) {
        playSound(sound, x, y, z, 1.0f, 1.0f);
    }

    /**
     * Draw a burst of {@code count} canonical {@link Particle}s around a position, scattered within
     * roughly {@code spread} blocks, visible to every player in this world. Keep counts modest — the
     * PE eras send one packet per particle (implementations cap the count).
     */
    default void spawnParticle(Particle particle, double x, double y, double z, int count, double spread) {
        for (Player p : getPlayers()) {
            p.getConnection().spawnParticle(particle, x, y, z, count, spread);
        }
    }

    /** One particle, exactly at the position. */
    default void spawnParticle(Particle particle, double x, double y, double z) {
        spawnParticle(particle, x, y, z, 1, 0.0);
    }

    /**
     * Spawn location for this world.
     */
    Location getSpawnLocation();

    /**
     * Move this world's spawn point. Default is a no-op (a minimal / test world has a fixed spawn); a
     * real world overrides it to store the new point (used for future joins and {@code /spawn}).
     */
    default void setSpawnLocation(Location location) {}
}
