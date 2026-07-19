package com.jedrock.network.pe.v014;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the MCPE 0.14 (protocol 45) AddEntity packet and the floating-text hack built on
 * it, pinned against PocketMine-MP at {@code CURRENT_PROTOCOL = 45} (commit e11b76318).
 *
 * <p>Two things here are easy to get backwards and impossible to see without a client, hence the test:
 * the metadata block comes <em>before</em> the entity-links short, and a string inside metadata carries a
 * <em>little-endian</em> length while every other 0.14 string is big-endian.
 */
class Mcpe014AddEntityTest {

    @Test
    void addEntityWritesMetadataBeforeTheLinksShort() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.addEntity(b, 1000L, 32, 10f, 64f, -5f, 90f, 0f);

        assertEquals(0x98, b.readUnsignedByte(), "AddEntity id");
        assertEquals(1000L, b.readLong(), "entity id");
        assertEquals(32, b.readInt(), "type (zombie), a 4-byte big-endian int at protocol 45");
        assertEquals(10f, b.readFloat(), "x");
        assertEquals(64f, b.readFloat(), "y (feet)");
        assertEquals(-5f, b.readFloat(), "z");
        assertEquals(0f, b.readFloat(), "speed x");
        assertEquals(0f, b.readFloat(), "speed y");
        assertEquals(0f, b.readFloat(), "speed z");
        assertEquals(90f, b.readFloat(), "yaw");
        assertEquals(0f, b.readFloat(), "pitch");
        assertEquals(0x7f, b.readUnsignedByte(), "metadata terminator — the metadata block comes first");
        assertEquals(0, b.readShort(), "entity links: none — and last");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void textLineIsAnInvisibleImmobileItemEntityWithANametag() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.addTextLine(b, 1001L, 8f, 70f, 8f, "§6Hello");

        assertEquals(0x98, b.readUnsignedByte(), "AddEntity id");
        assertEquals(1001L, b.readLong(), "entity id");
        assertEquals(64, b.readInt(), "type: an item entity — Bedrock's legacy eras have no armor stand");
        assertEquals(8f, b.readFloat(), "x");
        assertEquals(69.25f, b.readFloat(), "y: PocketMine's own -0.75 so the text lands where asked");
        assertEquals(8f, b.readFloat(), "z");
        b.skipBytes(3 * 4 + 2 * 4); // speed + rotation

        assertEquals((0 << 5) | 0, b.readUnsignedByte(), "DATA_FLAGS key: byte type, index 0");
        assertEquals(0x20, b.readUnsignedByte(), "invisible (bit 5)");

        assertEquals((4 << 5) | 2, b.readUnsignedByte(), "DATA_NAMETAG key: string type, index 2 at 0.14");
        byte[] expected = "§6Hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(expected.length, b.readShortLE(), "nametag length is LITTLE-endian inside metadata");
        byte[] text = new byte[expected.length];
        b.readBytes(text);
        assertEquals("§6Hello", new String(text, StandardCharsets.UTF_8), "the floating text");

        assertEquals((0 << 5) | 3, b.readUnsignedByte(), "DATA_SHOW_NAMETAG key: byte type, index 3");
        assertEquals(1, b.readUnsignedByte(), "always show it");

        assertEquals((0 << 5) | 15, b.readUnsignedByte(), "DATA_NO_AI key: byte type, index 15");
        assertEquals(1, b.readUnsignedByte(), "immobile");

        assertEquals(0x7f, b.readUnsignedByte(), "metadata terminator");
        assertEquals(0, b.readShort(), "entity links: none");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void nameTagIsWrittenAtTheZeroOneFourIndexNotTheOneOneFiveOne() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.setEntityNameTag(b, 1000L, "Bob");

        assertEquals(0xad, b.readUnsignedByte(), "SetEntityData id");
        assertEquals(1000L, b.readLong(), "entity id");
        assertEquals((4 << 5) | 2, b.readUnsignedByte(), "index 2 — 1.1.5's index 4 would silently do nothing");
        assertEquals(3, b.readShortLE(), "little-endian length");
        b.skipBytes(3);
        assertEquals((0 << 5) | 3, b.readUnsignedByte(), "show-nametag index");
        assertEquals(1, b.readUnsignedByte(), "shown");
        assertEquals(0x7f, b.readUnsignedByte(), "metadata terminator");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }
}
