package com.jedrock.network.je.packet;

import com.jedrock.api.world.World;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Clientbound Chunk Data (0x20) for JE 1.12.2, serialized from the shared {@link World}.
 *
 * <p>Reads canonical block ids from the world and maps them to the JE global palette, so a
 * Java client renders the same blocks a Bedrock client sees. Only non-empty 16³ sections are
 * sent. Palettes are built per section (indirect, 4 bits/block — enough for our small block set).
 */
public final class ClientboundChunkData implements ClientboundPacket {

    private static final int BITS_PER_BLOCK = 4; // indirect palette, up to 16 states per section

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
            for (int i = 0; i < 256; i++) {
                data.writeByte(1); // biomes: plains
            }

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
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        List<Integer> palette = new ArrayList<>();
        int[] indexPerBlock = new int[4096];
        boolean anyNonAir = false;

        for (int localY = 0; localY < 16; localY++) {
            int worldY = (sectionY << 4) | localY;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int state = toJavaState(world.getBlockId(baseX + x, worldY, baseZ + z));
                    if (state != 0) {
                        anyNonAir = true;
                    }
                    int idx = palette.indexOf(state);
                    if (idx < 0) {
                        palette.add(state);
                        idx = palette.size() - 1;
                    }
                    indexPerBlock[(localY << 8) | (z << 4) | x] = idx;
                }
            }
        }
        if (!anyNonAir) {
            return false;
        }

        out.writeByte(BITS_PER_BLOCK);
        ByteBufUtils.writeVarInt(out, palette.size());
        for (int state : palette) {
            ByteBufUtils.writeVarInt(out, state);
        }

        // 4096 blocks × 4 bits = 256 longs (16 blocks per long, no straddling).
        ByteBufUtils.writeVarInt(out, 256);
        for (int longIndex = 0; longIndex < 256; longIndex++) {
            long value = 0L;
            for (int nibble = 0; nibble < 16; nibble++) {
                long paletteIdx = indexPerBlock[(longIndex << 4) | nibble] & 0xF;
                value |= paletteIdx << (nibble * 4);
            }
            out.writeLong(value);
        }

        for (int i = 0; i < 2048; i++) out.writeByte(0x00); // block light (dark)
        for (int i = 0; i < 2048; i++) out.writeByte(0xFF); // sky light (full)
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
