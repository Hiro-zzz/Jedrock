package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic block ids (0 = air) and the packed <b>block state</b> the world
 * actually stores.
 *
 * <p>A state is the 12-bit legacy identifier {@code (id << 4) | meta} — exactly how both target
 * protocols address a block (JE global palette id, Bedrock id + 4-bit metadata). The constants
 * below are bare ids; combine one with a meta via {@link #state(int, int)} and split a state back
 * with {@link #idOf(int)} / {@link #metaOf(int)}. Meta-0 blocks have {@code state == id << 4}.
 */
public final class Blocks {

    // Keep core functional constants for fast internal logic references
    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int GRASS = 2;
    public static final int DIRT = 3;
    public static final int COBBLESTONE = 4;
    public static final int PLANKS = 5;
    public static final int WOOL = 35;
    public static final int SAND = 12;
    public static final int LOG = 17;
    public static final int GLASS = 20;

    /** Maximum numeric ID that safely fits into a single byte for the legacy protocol chunk matrix. */
    public static final int MAX_LEGACY_ID = 255;

    /** Pack a block id and its 4-bit metadata into the canonical state the world stores. */
    public static int state(int id, int meta) {
        return (id << 4) | (meta & 0xF);
    }

    /** The block id carried by a canonical state. */
    public static int idOf(int state) {
        return state >> 4;
    }

    /** The 4-bit metadata carried by a canonical state. */
    public static int metaOf(int state) {
        return state & 0xF;
    }

    /** * Dynamically verifies if the block id fits inside the structural wire bounds of both protocols.
     * As long as it's within 0-255, chunk serialization will never misalign or crash the client.
     */
    public static boolean isKnown(int id) {
        return id >= 0 && id <= MAX_LEGACY_ID;
    }

    /**
     * Guarantees a safe fallback block state if the ID violates protocol bounds,
     * protecting the pipeline from network desynchronization.
     */
    public static int getSafeId(int id) {
        if (!isKnown(id)) {
            return STONE; // Fallback to safe solid stone instead of breaking the loop
        }
        return id;
    }

    private Blocks() {}
}
