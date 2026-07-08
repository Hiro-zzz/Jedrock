package com.jedrock.network.handler.je.v1_8;

import com.jedrock.api.world.World;
import com.jedrock.network.je.packet.ClientboundPacket;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Chunk Data (0x21) for JE 1.8 — the pre-flattening column format.
 *
 * <p>1.8 stores blocks as little-endian {@code (id<<4)|meta} shorts, which <em>is</em> the world's
 * canonical state, so no palette or translation is needed. A column is 16 sections (y=0..255),
 * matching {@code BlockStorage} 1:1. The section data is <b>grouped</b>: all present sections' block
 * arrays, then all their block-light arrays, then all their sky-light arrays, then a 256-byte biome
 * map (when ground-up-continuous).
 *
 * <p>NOTE: the 1.8 grouped layout below is implemented from the protocol spec; it should be
 * cross-checked against minecraft-data / a real 1.8 client before being relied on.
 */
public final class Java1_8ChunkData implements ClientboundPacket {

    private static final int SECTIONS = 16;
    private static final byte[] FULL_LIGHT_SECTION = new byte[2048];
    static {
        java.util.Arrays.fill(FULL_LIGHT_SECTION, (byte) 0xFF);
    }

    private final World world;
    private final int chunkX;
    private final int chunkZ;

    public Java1_8ChunkData(World world, int chunkX, int chunkZ) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    @Override
    public int getPacketId() {
        return Java1_8Protocol.CB_CHUNK_DATA;
    }

    @Override
    public void write(ByteBuf buf) {
        short[] blocks = new short[4096];
        ByteBuf data = buf.alloc().buffer();
        try {
            int mask = 0;
            int present = 0;

            // Block arrays for every non-air section (ascending y), each 4096 LE (id<<4|meta) shorts.
            for (int sy = 0; sy < SECTIONS; sy++) {
                if (world.fillSection(chunkX, sy, chunkZ, blocks)) {
                    mask |= (1 << sy);
                    present++;
                    for (int i = 0; i < 4096; i++) {
                        data.writeShortLE(blocks[i] & 0xFFFF);
                    }
                }
            }

            // Block light: dark (zeros), one 2048-byte array per present section.
            data.writeZero(present * 2048);
            // Sky light: full daylight, one 2048-byte array per present section.
            for (int s = 0; s < present; s++) {
                data.writeBytes(FULL_LIGHT_SECTION);
            }
            // Biome map: 256 columns (index (z<<4)|x). Sent because this is a ground-up-continuous column.
            byte[] biomes = new byte[256];
            world.fillBiomes(chunkX, chunkZ, biomes);
            data.writeBytes(biomes);

            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            buf.writeBoolean(true);              // ground-up continuous (full column)
            buf.writeShort(mask & 0xFFFF);       // primary bit mask
            ByteBufUtils.writeVarInt(buf, data.readableBytes());
            buf.writeBytes(data);
        } finally {
            data.release();
        }
    }

    /** A 1.8 "unload" is a ground-up column with an empty bit mask and no data. */
    public static ClientboundPacket unload(int chunkX, int chunkZ) {
        return new ClientboundPacket() {
            @Override public int getPacketId() { return Java1_8Protocol.CB_CHUNK_DATA; }
            @Override public void write(ByteBuf buf) {
                buf.writeInt(chunkX);
                buf.writeInt(chunkZ);
                buf.writeBoolean(true);          // ground-up
                buf.writeShort(0);               // no sections
                ByteBufUtils.writeVarInt(buf, 0);// no data
            }
        };
    }
}
