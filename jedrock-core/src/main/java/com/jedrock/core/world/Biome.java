package com.jedrock.core.world;

/**
 * The small, fixed set of biomes Jedrock's finite world uses. Each carries the legacy Minecraft biome
 * id that every edition understands. All four are grass-surfaced, so they differ only in the client's
 * grass/foliage tint (and, later, in decoration) — the block terrain itself is biome-independent for
 * now. This is the generation-side model; the protocol layer maps {@link #id()} to its own wire form
 * (a biome-id byte on Java / PE 1.1.5, a grass-tint colour on PE 0.14).
 */
public enum Biome {
    PLAINS(1),
    FOREST(4),
    TAIGA(5),
    SAVANNA(35);

    private final int id;

    Biome(int id) {
        this.id = id;
    }

    /** Legacy Minecraft biome id (0..255). */
    public int id() {
        return id;
    }
}
