package com.jedrock.network.pe.v014;

import com.jedrock.api.player.Player;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.v014.Mcpe014Packets.ID_TEXT;
import static com.jedrock.network.pe.v014.Mcpe014Packets.TEXT_TYPE_POPUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the 0.14 popup (TextPacket {@code TYPE_POPUP}) the Bedrock sidebar rides on,
 * verbatim from PMMP {@code TextPacket} at protocol 45. Structurally the same as 113 — {@code type} byte,
 * then {@code source} and {@code message} — but the strings are this era's big-endian short-length form,
 * so the bytes differ even though the shape doesn't.
 */
class Pe014PopupEncodingTest {

    @Test
    void popupBodyMatchesProtocol45() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.popup(b, "§6Stats", "§fKills: 3\n§7Deaths: 1");

        assertEquals(ID_TEXT, b.readUnsignedByte(), "packet id (0.14 ids are plain bytes)");
        assertEquals(TEXT_TYPE_POPUP, b.readUnsignedByte(), "type");
        assertEquals("§6Stats", Mcpe014Codec.readString(b), "source = the title line");
        assertEquals("§fKills: 3\n§7Deaths: 1", Mcpe014Codec.readString(b), "message = the rows under it");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void aStringLengthIsTwoBigEndianBytes() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.popup(b, "ab", "");

        b.readUnsignedByte(); // id
        b.readUnsignedByte(); // type
        assertEquals(0, b.readUnsignedByte(), "length high byte");
        assertEquals(2, b.readUnsignedByte(), "length low byte — big-endian, unlike 113's varint");
        b.release();
    }

    @Test
    void theRowsAreNewlineJoinedAndCappedAtTheApiLimit() {
        assertEquals("", PeSession014.joinSidebarLines(null, 0, 0));
        assertEquals("a\nb", PeSession014.joinSidebarLines(new String[]{"a", "b"}, 0, 0));

        String[] tooMany = new String[Player.SIDEBAR_MAX_LINES + 3];
        java.util.Arrays.fill(tooMany, "row");
        assertEquals(Player.SIDEBAR_MAX_LINES,
                PeSession014.joinSidebarLines(tooMany, 0, 0).split("\n", -1).length,
                "capped like the Java sidebar and the 1.1.5 one");
    }

    @Test
    void raiseAndShiftPadTheSameWayAsOn115() {
        assertEquals("a\n \n ", PeSession014.joinSidebarLines(new String[]{"a"}, 2, 0));
        assertEquals(" \na", PeSession014.joinSidebarLines(new String[]{"a"}, -1, 0));
        assertEquals("  a", PeSession014.joinSidebarLines(new String[]{"a"}, 0, 2));
        assertEquals("a  ", PeSession014.joinSidebarLines(new String[]{"a"}, 0, -2));
        assertEquals(" x", PeSession014.pad("x", 1));
    }
}
