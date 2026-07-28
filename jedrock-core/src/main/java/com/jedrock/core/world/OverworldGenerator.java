package com.jedrock.core.world;

import com.jedrock.api.world.Blocks;

/**
 * The overworld: rolling grass hills over dirt over stone, four biomes, and the caves / lakes / trees
 * of {@link WorldDecorator}. This is the world Jedrock always had — the classification that used to sit
 * inside {@link CoreWorld}, moved out whole when the nether arrived, so its output is unchanged.
 *
 * <p>Its column descriptor is simply the surface height, which is why the packing costs nothing here.
 */
public final class OverworldGenerator implements WorldGenerator {

    /** Depth of the dirt layer below the grass surface; everything deeper is stone. */
    private static final int DIRT_DEPTH = 3;

    private static final int GRASS = Blocks.state(Blocks.GRASS, 0);
    private static final int DIRT = Blocks.state(Blocks.DIRT, 0);
    private static final int STONE = Blocks.state(Blocks.STONE, 0);

    private final TerrainGenerator terrain;
    private final BiomeGenerator biomes;

    public OverworldGenerator(long seed) {
        this.terrain = new TerrainGenerator(seed);
        this.biomes = new BiomeGenerator(seed);
    }

    @Override
    public long column(int x, int z) {
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public int blockAt(int y, long column) {
        int surface = (int) column;
        if (y < 0 || y > 255 || y > surface) {
            return Blocks.AIR;
        }
        // Natural terrain is all meta-0, so each state is simply the id shifted into place.
        if (y == surface) {
            return GRASS;
        }
        return y >= surface - DIRT_DEPTH ? DIRT : STONE;
    }

    @Override
    public int surfaceHeight(int x, int z) {
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public int biomeAt(int x, int z) {
        return biomes.biomeAt(x, z).id();
    }

    @Override
    public int maxY() {
        return 255;
    }

    @Override
    public void decorate(CoreWorld world, long seed, int loChunk, int hiChunk) {
        WorldDecorator.decorate(world, seed, loChunk, hiChunk);
    }
}
