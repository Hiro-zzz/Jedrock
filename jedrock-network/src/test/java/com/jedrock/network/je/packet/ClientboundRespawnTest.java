package com.jedrock.network.je.packet;

import com.jedrock.api.world.Dimension;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Java Respawn body — Join Game's tail, and the packet that makes a client throw its terrain away.
 *
 * <p>The dimension is a big-endian <b>int</b>, not a byte and not a varint: the nether's {@code -1} is
 * four bytes of {@code 0xFF}, which is the detail a hand-rolled encoder gets wrong first.
 */
class ClientboundRespawnTest {

    @Test
    void respawnBodyIsIntDimensionByteDifficultyByteModeAndAString() {
        ByteBuf b = Unpooled.buffer();
        new ClientboundRespawn(Dimension.NETHER.getId(), 1).write(b);

        assertEquals(-1, b.readInt(), "dimension: a signed big-endian int");
        assertEquals(2, b.readUnsignedByte(), "difficulty: normal, matching Join Game");
        assertEquals(1, b.readUnsignedByte(), "game mode");
        int length = readVarInt(b);
        byte[] levelType = new byte[length];
        b.readBytes(levelType);
        assertEquals("default", new String(levelType, StandardCharsets.UTF_8));
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void theIdIsTheOneTwelveTwoAssignment() {
        assertEquals(0x35, new ClientboundRespawn(0, 0).getPacketId());
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte read;
        do {
            read = buf.readByte();
            value |= (read & 0x7F) << shift;
            shift += 7;
        } while ((read & 0x80) != 0);
        return value;
    }
}
