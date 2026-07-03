package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural checks for the 1.12.2 Chunk Data (0x20) encoding. Can't talk to a real client
 * here, so this verifies the wire structure is internally consistent with the spec.
 */
class ClientboundChunkDataTest {

    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void tearDown() {
        buf.release();
    }

    @Test
    void encodesAConsistentFlatChunk() {
        new ClientboundChunkData(2, -3).write(buf);

        assertEquals(2, buf.readInt(), "chunk X");
        assertEquals(-3, buf.readInt(), "chunk Z");
        assertTrue(buf.readBoolean(), "ground-up continuous");
        assertEquals(1 << (ClientboundChunkData.FLOOR_Y >> 4), ByteBufUtils.readVarInt(buf), "primary bit mask");

        int size = ByteBufUtils.readVarInt(buf);
        int dataStart = buf.readerIndex();

        // --- one chunk section ---
        assertEquals(4, buf.readUnsignedByte(), "bits per block");
        assertEquals(2, ByteBufUtils.readVarInt(buf), "palette length");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "palette[0] = air");
        assertEquals(1 << 4, ByteBufUtils.readVarInt(buf), "palette[1] = stone");

        assertEquals(256, ByteBufUtils.readVarInt(buf), "data array length (longs)");
        int localFloorY = ClientboundChunkData.FLOOR_Y & 15;
        for (int i = 0; i < 256; i++) {
            long expected = (i >> 4) == localFloorY ? 0x1111111111111111L : 0L;
            assertEquals(expected, buf.readLong(), "long " + i);
        }
        buf.skipBytes(2048); // block light
        buf.skipBytes(2048); // sky light

        // --- biomes: 256 bytes ---
        for (int i = 0; i < 256; i++) {
            assertEquals(1, buf.readUnsignedByte(), "biome " + i);
        }

        assertEquals(size, buf.readerIndex() - dataStart, "declared size matches actual data bytes");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "block entity count");
        assertFalse(buf.isReadable(), "no trailing bytes");
    }
}
