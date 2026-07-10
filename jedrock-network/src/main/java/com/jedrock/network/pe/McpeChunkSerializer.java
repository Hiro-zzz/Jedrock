package com.jedrock.network.pe;

import com.jedrock.api.world.World;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;

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
 * </pre>
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

    /** Serialize the given chunk column of {@code world} into a network-format payload. */
    static byte[] serialize(World world, int chunkX, int chunkZ) {
        short[] blocks = SCRATCH.get();
        byte[] meta = META.get();

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
                            payload.writeByte((state >> 4) & 0xFF);              // block id
                            meta[i >> 1] |= (state & 0xF) << ((i & 1) << 2);     // low nibble, then high
                            i++;
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

            byte[] out = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), out);
            return out;
        } finally {
            payload.release();
        }
    }
}
