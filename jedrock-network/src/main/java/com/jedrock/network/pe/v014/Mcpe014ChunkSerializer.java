package com.jedrock.network.pe.v014;

import com.jedrock.api.world.World;

/**
 * Serializes one 128-tall chunk column into the MCPE 0.14 (protocol 45) FullChunkData {@code data}
 * blob, ORDER_COLUMNS layout. Verified against PocketMine-MP {@code anvil/ChunkRequestTask.php} at
 * protocol 45.
 *
 * <p>0.14 worlds are 128 blocks tall (not the 256 / 16³ sub-chunks of 1.1.5), and the column is a flat
 * concatenation:
 * <pre>
 *   blockIds[32768] ++ meta[16384] ++ skyLight[16384] ++ blockLight[16384] ++ heightMap[256] ++ biome[1024]
 * </pre>
 * Ordering is column-major, x outer / z inner, each column 128 tall: {@code blockIds[(x*16+z)*128 + y]}.
 * The nibble arrays are half-columns (64 bytes/col): byte {@code k} packs {@code val(2k) | val(2k+1)<<4}.
 * skyLight is full daylight (0xFF), blockLight is 0. The heightMap and per-column biome value are indexed
 * {@code (z<<4)+x}; the biome is a big-endian uint32 whose top byte is the legacy biome id and low three
 * bytes are its grass tint — 0.14 renders grass from this colour, so per-biome tints show up client-side.
 *
 * <p>The world exposes the canonical {@code (id<<4)|meta} state via {@link World#getBlockId}; 0.14 wants
 * the legacy id byte plus a 4-bit meta nibble, which is exactly that state split.
 */
public final class Mcpe014ChunkSerializer {

    private static final int HEIGHT = 128;
    private static final int BLOCKS = 16 * 16 * HEIGHT;      // 32768
    private static final int NIBBLES = BLOCKS / 2;           // 16384
    private static final int BLOB = BLOCKS + NIBBLES * 3 + 256 + 1024; // 103200

    private Mcpe014ChunkSerializer() {}

    /** Legacy grass tint (24-bit RGB) for a biome id — MCPE 0.14 sends grass colours, not biome ids. */
    private static int grassRgb(int biomeId) {
        return switch (biomeId) {
            case 4 -> 0x59AE30;   // forest
            case 5 -> 0x86B87F;   // taiga
            case 35 -> 0xBFB755;  // savanna
            default -> 0x85B24A;  // plains (and anything unknown)
        };
    }

    public static byte[] serialize(World world, int chunkX, int chunkZ) {
        byte[] out = new byte[BLOB];

        int idsBase = 0;
        int metaBase = idsBase + BLOCKS;
        int skyBase = metaBase + NIBBLES;
        int lightBase = skyBase + NIBBLES;
        int heightBase = lightBase + NIBBLES;
        int biomeBase = heightBase + 256;

        // skyLight = full daylight.
        java.util.Arrays.fill(out, skyBase, skyBase + NIBBLES, (byte) 0xFF);

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (x * 16 + z);
                int idCol = idsBase + col * HEIGHT;
                int halfCol = col * (HEIGHT / 2);
                int topSolid = 0;

                for (int y = 0; y < HEIGHT; y++) {
                    int state = world.getBlockId(baseX + x, y, baseZ + z);
                    int id = (state >> 4) & 0xFF;
                    int meta = state & 0x0F;

                    // 0.14 crashes on a block id it doesn't know, even inside a chunk — so a block a
                    // Java/1.1.5 player placed that 0.14 can't render is sent as air instead.
                    if (id != 0 && !Pe014Blocks.supports(id)) {
                        id = 0;
                        meta = 0;
                    }

                    out[idCol + y] = (byte) id;
                    if (meta != 0) {
                        int nibbleIdx = halfCol + (y >> 1);
                        if ((y & 1) == 0) {
                            out[metaBase + nibbleIdx] |= (byte) meta;
                        } else {
                            out[metaBase + nibbleIdx] |= (byte) (meta << 4);
                        }
                    }
                    if (id != 0) {
                        topSolid = y + 1;
                    }
                }

                int hz = (z << 4) + x;
                out[heightBase + hz] = (byte) Math.min(topSolid, 255);
                int biomeId = world.getBiome(baseX + x, baseZ + z);
                int color = (biomeId << 24) | grassRgb(biomeId); // [biomeId, R, G, B]
                int bo = biomeBase + hz * 4;
                out[bo] = (byte) (color >> 24);
                out[bo + 1] = (byte) (color >> 16);
                out[bo + 2] = (byte) (color >> 8);
                out[bo + 3] = (byte) color;
            }
        }
        return out;
    }
}
