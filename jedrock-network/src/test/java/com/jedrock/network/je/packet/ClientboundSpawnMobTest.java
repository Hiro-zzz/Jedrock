package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A hologram line: an armor stand carrying the text as its custom name. Pins the 1.12.2 metadata
     * dialect — a plain String at index 2 (type 3; OptChat is 1.13+) and armor-stand flags at index 11
     * (15 is the modern index) — since a wrong type id derails the whole metadata stream.
     */
    @Test
    void textLineSpawnsAnInvisibleMarkerArmorStandCarryingTheText() {
        UUID uuid = UUID.randomUUID();
        ClientboundSpawnMob p = ClientboundSpawnMob.textLine(1001, uuid, 8.0, 70.0, 8.0, "§6Hello");

        ByteBuf b = Unpooled.buffer();
        p.write(b);

        assertEquals(1001, ByteBufUtils.readVarInt(b), "entity id");
        b.readLong();
        b.readLong();
        assertEquals(30, ByteBufUtils.readVarInt(b), "type (armor stand)");
        assertEquals(8.0, b.readDouble(), "x");
        assertEquals(69.5, b.readDouble(), "y: the stand hangs below so the name lands where asked");
        assertEquals(8.0, b.readDouble(), "z");
        b.skipBytes(3 + 3 * 2); // angles + velocity

        assertEquals(0, b.readByte(), "flags index");
        assertEquals(0, ByteBufUtils.readVarInt(b), "flags type: byte");
        assertEquals(0x20, b.readByte(), "invisible");

        assertEquals(2, b.readByte(), "custom name index");
        assertEquals(3, ByteBufUtils.readVarInt(b), "custom name type: String at 1.12.2");
        assertEquals("§6Hello", ByteBufUtils.readString(b), "the floating text");

        assertEquals(3, b.readByte(), "custom name visible index");
        assertEquals(6, ByteBufUtils.readVarInt(b), "custom name visible type: Boolean");
        assertTrue(b.readBoolean(), "the name is shown");

        assertEquals(5, b.readByte(), "no gravity index");
        assertEquals(6, ByteBufUtils.readVarInt(b), "no gravity type: Boolean");
        assertTrue(b.readBoolean(), "no gravity");

        assertEquals(11, b.readByte(), "armor stand flags index (11 at 1.12.2, not the modern 15)");
        assertEquals(0, ByteBufUtils.readVarInt(b), "armor stand flags type: byte");
        assertEquals(0x10 | 0x01 | 0x08, b.readByte(), "marker | small | no base plate");

        assertEquals((byte) 0xFF, b.readByte(), "metadata terminator");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }
}
