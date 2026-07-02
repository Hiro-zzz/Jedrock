package com.jedrock.api.world;

/**
 * Extremely minimal block state abstraction.
 * In a real implementation this can be a flyweight or int-packed.
 * 
 * The goal is complete abstraction: no hardcoded block IDs in API.
 */
public record BlockState(int protocolId, String identifier) {

    public static final BlockState AIR = new BlockState(0, "minecraft:air");

    public BlockState {
        if (identifier == null) identifier = "minecraft:air";
    }
}
