package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic block ids (0 = air).
 * Uses dynamic range validation to natively support all legacy blocks (0-255)
 * without hardcoding them, adhering to the high-throughput illusionist architecture.
 */
public final class Blocks {

    // Keep core functional constants for fast internal logic references
    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int GRASS = 2;
    public static final int DIRT = 3;
    public static final int COBBLESTONE = 4;
    public static final int PLANKS = 5;
    public static final int SAND = 12;
    public static final int LOG = 17;
    public static final int GLASS = 20;

    /** Maximum numeric ID that safely fits into a single byte for the legacy protocol chunk matrix. */
    public static final int MAX_LEGACY_ID = 255;

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
