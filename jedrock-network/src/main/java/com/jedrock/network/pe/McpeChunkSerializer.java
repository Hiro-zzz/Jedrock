package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.World;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Serializes a chunk column into the MCPE 1.0/1.1 (protocol 113) network chunk format: a run of
 * 16³ sub-chunks from y=0, each carrying block ids + metadata + sky light + block light, then a
 * heightmap, biome map, border and extra-data markers.
 *
 * <p>Layout per the MCPE 1.0 network chunk spec (dktapps):
 * <pre>
 *   byte  subChunkCount
 *   per sub-chunk:
 *     byte      version (0)
 *     byte[4096] block ids   (XZY order)
 *     byte[2048] block meta  (4-bit)
 *     byte[2048] sky light   (4-bit)
 *     byte[2048] block light (4-bit)
 *   byte[512] heightmap (256 shorts)
 *   byte[256] biome ids
 *   byte      border block count
 *   varint    extra-data count
 *   nbt...    block-entity tiles (concatenated network-NBT compounds, read to end; chests only)
 * </pre>
 *
 * <p>The trailing tile section is what lets a 1.1.5 client open a chest: the retail client materializes
 * a chest's block-entity only from this chunk tail, not from a standalone BlockEntityData, so a chest
 * with no tile here crashes the client on open. Tiles are network NBT (unsigned-varint lengths, zigzag
 * ints) — the same {@code write(true)} dialect PMMP uses for the chunk's tile list — with no count
 * prefix; the client reads compounds until the buffer is exhausted.
 *
 * <p>Omitting the two light arrays and the heightmap desyncs the client's read pointer after the
 * first sub-chunk and renders the whole column as garbage — hence they are always written.
 */
final class McpeChunkSerializer {

    private McpeChunkSerializer() {}

    /** 2048 nibble-bytes of full light (0xF per cell) — a lit sub-chunk so the world isn't dark. */
    private static final byte[] FULL_LIGHT = new byte[2048];
    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
    }

    /** Per-thread section + metadata-nibble buffers so serializing a column allocates nothing. */
    private static final ThreadLocal<short[]> SCRATCH = ThreadLocal.withInitial(() -> new short[4096]);
    private static final ThreadLocal<byte[]> META = ThreadLocal.withInitial(() -> new byte[2048]);
    private static final ThreadLocal<byte[]> BIOMES = ThreadLocal.withInitial(() -> new byte[256]);
    /** Reused per-thread scratch for the chest positions found in a column — cleared each call, so a
     *  chest-free column (the common case) allocates nothing. */
    private static final ThreadLocal<List<int[]>> TILES = ThreadLocal.withInitial(ArrayList::new);

    /** Serialize the given chunk column of {@code world} into a network-format payload. */
    static byte[] serialize(World world, int chunkX, int chunkZ) {
        short[] blocks = SCRATCH.get();
        byte[] meta = META.get();
        List<int[]> tiles = TILES.get();
        tiles.clear(); // reused scratch — start empty for this column

        // Pass 1: find the highest non-empty section. Scan top-down and stop at the first hit — the
        // terrain surface sits low (a few sections up), so this fills far fewer sections than a full
        // 0..15 sweep, and never more. fillSection is allocation-free (one storage lookup + one height
        // eval per column, no boxing).
        int topSection = -1;
        for (int sy = 15; sy >= 0; sy--) {
            if (world.fillSection(chunkX, sy, chunkZ, blocks)) {
                topSection = sy;
                break;
            }
        }
        int subChunkCount = topSection + 1; // sub-chunks are sent contiguously from y=0

        ByteBuf payload = Unpooled.buffer();
        try {
            payload.writeByte(subChunkCount);
            // Pass 2: emit sub-chunks 0..top, re-filling each (only the few that actually exist).
            for (int sy = 0; sy < subChunkCount; sy++) {
                world.fillSection(chunkX, sy, chunkZ, blocks);
                payload.writeByte(0); // sub-chunk format version (legacy)
                // Block ids in Bedrock XZY order (x outer, y inner); scratch is indexed (y<<8)|(z<<4)|x.
                // The canonical value is a packed state, so id = state >> 4 and meta = state & 0xF; the
                // metadata travels as a parallel 4-bit nibble array in the same iteration order.
                Arrays.fill(meta, (byte) 0);
                int i = 0;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            int state = blocks[(y << 8) | (z << 4) | x] & 0xFFFF;
                            int id = (state >> 4) & 0xFF;
                            payload.writeByte(id);                              // block id
                            meta[i >> 1] |= (state & 0xF) << ((i & 1) << 2);     // low nibble, then high
                            i++;
                            // A chest needs a block-entity tile in the tail (below) or the client crashes on
                            // open. Record its absolute position now while we're already visiting the cell.
                            if (id == Blocks.CHEST) {
                                tiles.add(new int[]{(chunkX << 4) + x, (sy << 4) + y, (chunkZ << 4) + z});
                            }
                        }
                    }
                }
                payload.writeBytes(meta);       // block metadata (4-bit nibbles, XZY order)
                payload.writeBytes(FULL_LIGHT); // sky light (full daylight)
                payload.writeZero(2048);        // block light (none)
            }
            payload.writeZero(512);               // heightmap (256 shorts; client recomputes)
            byte[] biomes = BIOMES.get();
            world.fillBiomes(chunkX, chunkZ, biomes);
            payload.writeBytes(biomes);           // biome map (256 columns, index (z<<4)|x)
            payload.writeByte(0);                 // border block count
            ByteBufUtils.writeVarInt(payload, 0); // extra data count

            // Block-entity tiles: concatenated network-NBT compounds, no count prefix (read to end). Only
            // chests today; a chest without its tile here crashes a 1.1.5 client the moment it opens it.
            for (int[] t : tiles) {
                McpeCodec.writeChestTile(payload, t[0], t[1], t[2]);
            }

            byte[] out = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), out);
            return out;
        } finally {
            payload.release();
        }
    }
}
