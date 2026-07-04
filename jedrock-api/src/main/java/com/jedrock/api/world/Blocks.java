package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic block ids (0 = air). The core stores the world in these ids;
 * each protocol layer maps them to its own palette (Java global state / Bedrock id+meta),
 * so a Java and a Bedrock client render the same blocks.
 */
public final class Blocks {

    // Canonical ids are the classic numeric block ids, which Java and legacy Bedrock share for
    // basic blocks — so a protocol layer can map canonical → its palette by near-identity.
    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int GRASS = 2;
    public static final int DIRT = 3;
    public static final int COBBLESTONE = 4;
    public static final int PLANKS = 5;
    public static final int SAND = 12;
    public static final int LOG = 17;
    public static final int GLASS = 20;

    /** @return true if this canonical id is one both protocol layers can serialize today. */
    public static boolean isKnown(int id) {
        return id == AIR || id == STONE || id == GRASS || id == DIRT || id == COBBLESTONE
                || id == PLANKS || id == SAND || id == LOG || id == GLASS;
    }

    private Blocks() {}
}
