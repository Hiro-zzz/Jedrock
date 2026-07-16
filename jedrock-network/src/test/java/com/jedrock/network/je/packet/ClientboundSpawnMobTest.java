package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level check for the JE 1.12.2 Spawn Mob (0x03) puppet-spawn packet: entity id, uuid, numeric type,
 * double position, three angle-bytes, three velocity shorts, and an empty {@code 0xff} metadata terminator.
 */
class ClientboundSpawnMobTest {

    @Test
    void bytesMatchProtocol340SpawnMob() {
        UUID uuid = new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        ClientboundSpawnMob p = new ClientboundSpawnMob(1000, uuid, 54, 10.0, 64.0, -5.0, 90f, 0f);
        assertEquals(0x03, p.getPacketId(), "Spawn Mob id");

        ByteBuf b = Unpooled.buffer();
        p.write(b);

        assertEquals(1000, ByteBufUtils.readVarInt(b), "entity id");
        assertEquals(0x1122334455667788L, b.readLong(), "uuid msb");
        assertEquals(0x99AABBCCDDEEFF00L, b.readLong(), "uuid lsb");
        assertEquals(54, ByteBufUtils.readVarInt(b), "type (zombie)");
        assertEquals(10.0, b.readDouble(), "x");
        assertEquals(64.0, b.readDouble(), "y");
        assertEquals(-5.0, b.readDouble(), "z");
        b.readByte(); // yaw angle
        b.readByte(); // pitch angle
        b.readByte(); // head pitch angle
        assertEquals(0, b.readShort(), "velocity x");
        assertEquals(0, b.readShort(), "velocity y");
        assertEquals(0, b.readShort(), "velocity z");
        assertEquals((byte) 0xFF, b.readByte(), "metadata terminator");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }
}
