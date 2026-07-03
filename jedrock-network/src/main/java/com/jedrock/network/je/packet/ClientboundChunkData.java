package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Chunk Data (0x20) for JE 1.12.2.
 *
 * <p>Sends a single 16×16×256 chunk column. To let a client actually spawn we only need
 * a floor to stand on, so this emits a flat chunk: one stone layer at {@link #FLOOR_Y}
 * (matching the y=64 spawn), everything else air, plains biome, full sky light.
 *
 * <p>The whole world is currently this illusion — later this should serialize real block
 * ids from {@code CoreWorld}/{@code BlockStorage} instead of a hard-coded floor.
 */
public final class ClientboundChunkData implements ClientboundPacket {

    /** World Y of the stone floor. The spawn packets put the player at y=64, one block above. */
    public static final int FLOOR_Y = 63;

    // 1.12.2 global palette ids: state = (blockId << 4) | meta. Air = 0, Stone (block 1) = 16.
    private static final int AIR_STATE = 0;
    private static final int STONE_STATE = 1 << 4;

    private static final int BITS_PER_BLOCK = 4;             // 4 bits index a 2-entry palette
    private static final int SECTION_INDEX = FLOOR_Y >> 4;   // which 16-tall section holds the floor
    private static final int PRIMARY_BIT_MASK = 1 << SECTION_INDEX;
    private static final int LOCAL_FLOOR_Y = FLOOR_Y & 15;   // floor's Y inside its section

    private final int chunkX;
    private final int chunkZ;

    public ClientboundChunkData(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeBoolean(true);                        // ground-up continuous (full chunk incl. biomes)
        ByteBufUtils.writeVarInt(buf, PRIMARY_BIT_MASK);

        ByteBuf data = buf.alloc().buffer();
        try {
            writeFloorSection(data);
            for (int i = 0; i < 256; i++) {
                data.writeByte(1);                     // biomes: plains
            }

            ByteBufUtils.writeVarInt(buf, data.readableBytes());
            buf.writeBytes(data);
        } finally {
            data.release();
        }

        ByteBufUtils.writeVarInt(buf, 0);              // no block entities
    }

    /** A single chunk section: a stone layer at {@link #LOCAL_FLOOR_Y}, air elsewhere. */
    private static void writeFloorSection(ByteBuf data) {
        data.writeByte(BITS_PER_BLOCK);

        // Indirect palette: index 0 = air, index 1 = stone.
        ByteBufUtils.writeVarInt(data, 2);
        ByteBufUtils.writeVarInt(data, AIR_STATE);
        ByteBufUtils.writeVarInt(data, STONE_STATE);

        // 4096 blocks × 4 bits = 256 longs (16 blocks per long, no straddling for bpb=4).
        // Block index = (y << 8) | (z << 4) | x, so long i covers local y = i / 16.
        long floorLong = 0x1111111111111111L; // all 16 nibbles = palette index 1 (stone)
        ByteBufUtils.writeVarInt(data, 256);
        for (int i = 0; i < 256; i++) {
            data.writeLong((i >> 4) == LOCAL_FLOOR_Y ? floorLong : 0L);
        }

        // Block light (dark) then sky light (full) — 2048 bytes each (4096 nibbles).
        for (int i = 0; i < 2048; i++) data.writeByte(0x00);
        for (int i = 0; i < 2048; i++) data.writeByte(0xFF);
    }

    @Override
    public int getPacketId() {
        return 0x20;
    }
}
