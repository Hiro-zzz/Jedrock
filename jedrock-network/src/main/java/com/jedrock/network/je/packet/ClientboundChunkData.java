package com.jedrock.network.je.packet;

import com.jedrock.api.world.World;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.Arrays;

/**
 * Clientbound Chunk Data (0x20) for JE 1.12.2, serialized from the shared {@link World}.
 *
 * <p>Reads canonical block ids from the world and maps them to the JE global palette, so a
 * Java client renders the same blocks a Bedrock client sees. Only non-empty 16³ sections are
 * sent. Each section builds an indirect palette and picks the smallest legal bits-per-block for
 * it (4..8), so a section with many distinct states no longer overflows a fixed 16-entry palette.
 *
 * <p>The section loop is on the network hot path, so it allocates nothing per chunk: block ids come
 * from a bulk {@link World#fillSection} into a reused buffer, and the palette is a small primitive
 * array searched linearly (no {@code List<Integer>} boxing). Both live in a per-thread {@link Scratch}.
 */
public final class ClientboundChunkData implements ClientboundPacket {

    /** JE indirect-palette bounds: the format mandates ≥4 bits; ≤8 keeps us in the indirect palette. */
    private static final int MIN_BITS_PER_BLOCK = 4;
    private static final int MAX_BITS_PER_BLOCK = 8;

    /** 2048 bytes of full sky light (0xF per nibble); block light is all-dark, written as zeros. */
    private static final byte[] FULL_LIGHT = new byte[2048];
    /** 256 biome bytes, all "plains" (1). */
    private static final byte[] PLAINS_BIOMES = new byte[256];
    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
        Arrays.fill(PLAINS_BIOMES, (byte) 1);
    }

    /** Per-thread reusable working buffers so serializing a chunk's 16 sections allocates nothing. */
    private static final class Scratch {
        final short[] blocks = new short[4096];    // canonical ids, index (y<<8)|(z<<4)|x
        final int[] palette = new int[256];        // JE states; 8-bit indirect ⇒ at most 256 entries
        final byte[] indices = new byte[4096];      // palette index per block (0..255, read unsigned)
        final long[] data = new long[64 * MAX_BITS_PER_BLOCK]; // packed indices; sized for 8 bits/block
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final World world;
    private final int chunkX;
    private final int chunkZ;

    public ClientboundChunkData(World world, int chunkX, int chunkZ) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeBoolean(true); // ground-up continuous (full chunk incl. biomes)

        ByteBuf data = buf.alloc().buffer();
        try {
            int primaryBitMask = 0;
            for (int sectionY = 0; sectionY < 16; sectionY++) {
                if (writeSection(data, sectionY)) {
                    primaryBitMask |= (1 << sectionY);
                }
            }
            data.writeBytes(PLAINS_BIOMES); // biomes: plains

            ByteBufUtils.writeVarInt(buf, primaryBitMask);
            ByteBufUtils.writeVarInt(buf, data.readableBytes());
            buf.writeBytes(data);
        } finally {
            data.release();
        }

        ByteBufUtils.writeVarInt(buf, 0); // no block entities
    }

    /** Serialize one 16³ section from the world; returns false (and writes nothing) if it is all air. */
    private boolean writeSection(ByteBuf out, int sectionY) {
        Scratch s = SCRATCH.get();
        if (!world.fillSection(chunkX, sectionY, chunkZ, s.blocks)) {
            return false; // all air — skip the section entirely
        }

        // Build the section's palette and per-block index in one pass. The palette is a tiny
        // primitive array searched linearly (our block set is small), so no boxing or growth.
        int[] palette = s.palette;
        byte[] indices = s.indices;
        int paletteSize = 0;
        for (int i = 0; i < 4096; i++) {
            int state = toJavaState(s.blocks[i] & 0xFFFF);
            int idx = -1;
            for (int p = 0; p < paletteSize; p++) {
                if (palette[p] == state) {
                    idx = p;
                    break;
                }
            }
            if (idx < 0) {
                if (paletteSize < palette.length) {
                    idx = paletteSize;
                    palette[paletteSize++] = state;
                } else {
                    idx = 0; // >256 distinct states in one section — unreachable with our palette; clamp
                }
            }
            indices[i] = (byte) idx;
        }

        int bitsPerBlock = bitsFor(paletteSize);

        out.writeByte(bitsPerBlock);
        ByteBufUtils.writeVarInt(out, paletteSize);
        for (int p = 0; p < paletteSize; p++) {
            ByteBufUtils.writeVarInt(out, palette[p]);
        }

        // Pack the 4096 palette indices into a compact bit array. In 1.12.2 the entries are tightly
        // packed and an entry may straddle two longs (unlike 1.13+, which pads to avoid straddling).
        int longCount = 64 * bitsPerBlock; // = 4096 * bitsPerBlock / 64, always exact
        long[] data = s.data;
        java.util.Arrays.fill(data, 0, longCount, 0L);
        for (int i = 0; i < 4096; i++) {
            long value = indices[i] & 0xFF;
            int bitIndex = i * bitsPerBlock;
            int longIndex = bitIndex >> 6;
            int bitOffset = bitIndex & 63;
            data[longIndex] |= value << bitOffset;
            int spill = bitOffset + bitsPerBlock - 64;
            if (spill > 0) {
                // The top `spill` bits didn't fit — carry them into the low bits of the next long.
                data[longIndex + 1] |= value >>> (bitsPerBlock - spill);
            }
        }
        ByteBufUtils.writeVarInt(out, longCount);
        for (int i = 0; i < longCount; i++) {
            out.writeLong(data[i]);
        }

        out.writeZero(2048);        // block light (dark)
        out.writeBytes(FULL_LIGHT); // sky light (full)
        return true;
    }

    /** Smallest legal JE indirect bits-per-block for {@code paletteSize} entries, clamped to 4..8. */
    private static int bitsFor(int paletteSize) {
        int bits = paletteSize <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (bits < MIN_BITS_PER_BLOCK) return MIN_BITS_PER_BLOCK;
        if (bits > MAX_BITS_PER_BLOCK) return MAX_BITS_PER_BLOCK;
        return bits;
    }

    /**
     * Canonical state → JE 1.12.2 global palette id. The world's canonical value is already the
     * legacy {@code (blockId << 4) | meta}, which <i>is</i> the JE global palette id, so this is an
     * identity — kept as a named seam in case a future block needs a non-legacy mapping.
     */
    private static int toJavaState(int canonical) {
        return canonical;
    }

    @Override
    public int getPacketId() {
        return 0x20;
    }
}
