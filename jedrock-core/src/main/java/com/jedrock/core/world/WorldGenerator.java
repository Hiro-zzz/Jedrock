package com.jedrock.core.world;

import com.jedrock.api.world.Dimension;

/**
 * What a world is made of, before anything is baked — the one thing that differs between an overworld
 * and a nether. {@link CoreWorld} owns storage, bounds and persistence identically for both; only this
 * decides what a cell contains and how tall the world is.
 *
 * <p><b>The column contract</b> is the whole reason this isn't just {@code blockAt(x, y, z)}. Baking a
 * 48×48 world touches millions of cells, so the shape of a column is evaluated <em>once</em> and packed
 * into a {@code long} the caller carries down the y-axis: an overworld packs its surface height, the
 * nether packs a floor and a ceiling. Everything expensive (noise) happens in {@link #column}; the
 * per-cell {@link #blockAt} is then pure arithmetic, allocation-free and branch-cheap. A generator that
 * needed more state per column would pack more bits, not change the signature.
 */
public interface WorldGenerator {

    /**
     * Evaluate the shape of one column, packed into a {@code long} only this generator interprets.
     * Called once per {@code (x, z)} on the bake and chunk-serialization paths.
     */
    long column(int x, int z);

    /** The block state at {@code y} in a column whose {@link #column} descriptor is {@code column}. */
    int blockAt(int y, long column);

    /**
     * The y of the walkable ground in a column — the grass surface of an overworld, the cavern floor of
     * a nether. Decoration stands things on it and the spawn point sits above it; it is <em>not</em>
     * necessarily the highest solid block (the nether has a roof above its floor).
     */
    int surfaceHeight(int x, int z);

    /** The y a player can stand at in this column: one above the ground, clear of any lava sea. */
    default int spawnHeight(int x, int z) {
        return surfaceHeight(x, z) + 1;
    }

    /** Legacy biome id for a column — the value every edition's chunk serializer maps to its own form. */
    int biomeAt(int x, int z);

    /** Highest buildable y. 255 for an overworld; the nether is a 128-tall world on every edition. */
    int maxY();

    /**
     * Run the bake-time decoration passes over the chunk range {@code [loChunk, hiChunk)} on each axis,
     * with the world already {@code generated} — so a pass reads and writes the same storage the client
     * will see. Called once, right after the terrain bake; never at runtime.
     */
    void decorate(CoreWorld world, long seed, int loChunk, int hiChunk);

    /** The generator a dimension is made of. The End is not a world type Jedrock generates. */
    static WorldGenerator forDimension(Dimension dimension, long seed) {
        return switch (dimension) {
            case OVERWORLD -> new OverworldGenerator(seed);
            case NETHER -> new NetherGenerator(seed);
            case END -> throw new IllegalArgumentException(
                    "The End is not a world type Jedrock generates — use OVERWORLD or NETHER");
        };
    }
}
