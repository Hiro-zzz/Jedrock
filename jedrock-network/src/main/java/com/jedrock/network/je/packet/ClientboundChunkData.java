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
 * sent. Palettes are built per section (indirect, 4 bits/block — enough for our small block set).
 *
 * <p>The section loop is on the network hot path, so it allocates nothing per chunk: block ids come
 * from a bulk {@link World#fillSection} into a reused buffer, and the palette is a small primitive
 * array searched linearly (no {@code List<Integer>} boxing). Both live in a per-thread {@link Scratch}.
 */
public final class ClientboundChunkData implements ClientboundPacket {

    private static final int BITS_PER_BLOCK = 4; // indirect palette, up to 16 states per section

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
        final short[] blocks = new short[4096];   // canonical ids, index (y<<8)|(z<<4)|x
        final int[] palette = new int[16];         // JE states; 4 bits ⇒ at most 16 entries
        final byte[] indices = new byte[4096];     // palette index per block
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
                    idx = 0; // 4-bit palette full (>16 states) — see roadmap; clamp, never overflow
                }
            }
            indices[i] = (byte) idx;
        }

        out.writeByte(BITS_PER_BLOCK);
        ByteBufUtils.writeVarInt(out, paletteSize);
        for (int p = 0; p < paletteSize; p++) {
            ByteBufUtils.writeVarInt(out, palette[p]);
        }

        // 4096 blocks × 4 bits = 256 longs (16 blocks per long, no straddling).
        ByteBufUtils.writeVarInt(out, 256);
        for (int longIndex = 0; longIndex < 256; longIndex++) {
            long value = 0L;
            for (int nibble = 0; nibble < 16; nibble++) {
                long paletteIdx = indices[(longIndex << 4) | nibble] & 0xF;
                value |= paletteIdx << (nibble * 4);
            }
            out.writeLong(value);
        }

        out.writeZero(2048);        // block light (dark)
        out.writeBytes(FULL_LIGHT); // sky light (full)
        return true;
    }

    /**
     * Canonical block id → JE 1.12.2 global palette state (blockId &lt;&lt; 4 | meta). Canonical
     * ids are the classic numeric block ids, so meta-0 blocks map by {@code id << 4}.
     */
    private static int toJavaState(int canonical) {
        return canonical << 4;
    }

    @Override
    public int getPacketId() {
        return 0x20;
    }
}
