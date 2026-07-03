package com.jedrock.api.world;

/**
 * Canonical, protocol-agnostic block ids (0 = air). The core stores the world in these ids;
 * each protocol layer maps them to its own palette (Java global state / Bedrock id+meta),
 * so a Java and a Bedrock client render the same blocks.
 */
public final class Blocks {

    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int GRASS = 2;
    public static final int DIRT = 3;

    private Blocks() {}
}
