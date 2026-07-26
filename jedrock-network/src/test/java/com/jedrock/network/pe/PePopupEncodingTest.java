package com.jedrock.network.pe;

import com.jedrock.api.player.Player;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_TEXT;
import static com.jedrock.network.pe.McpeProtocol.TEXT_TYPE_POPUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level checks for the popup (TextPacket {@code TYPE_POPUP}) the Bedrock sidebar rides on, verbatim
 * from PMMP {@code TextPacket} at protocol 113: {@code type} (byte), then — because POPUP falls through to
 * the message case — {@code source} and {@code message}, both strings. It is what {@code sendPopup(message,
 * subtitle)} sends, so the sidebar puts its title in {@code source} and its rows in {@code message}.
 */
class PePopupEncodingTest {

    @Test
    void popupBodyMatchesProtocol113() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.popup(b, "§6Stats", "§fKills: 3\n§7Deaths: 1");

        assertEquals(ID_TEXT, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(TEXT_TYPE_POPUP, b.readUnsignedByte(), "type (a plain byte, not a varint)");
        assertEquals("§6Stats", ByteBufUtils.readString(b), "source = the title line");
        assertEquals("§fKills: 3\n§7Deaths: 1", ByteBufUtils.readString(b), "message = the rows under it");
        assertFalse(b.isReadable(), "no trailing bytes — 113's TextPacket has no xuid field");
        b.release();
    }

    @Test
    void clearingSendsAnEmptyPopup() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.popup(b, null, null);

        ByteBufUtils.readVarInt(b); // id
        assertEquals(TEXT_TYPE_POPUP, b.readUnsignedByte());
        assertEquals("", ByteBufUtils.readString(b), "a null title writes an empty string, not a crash");
        assertEquals("", ByteBufUtils.readString(b));
        b.release();
    }

    @Test
    void theRowsAreNewlineJoinedAndCappedAtTheApiLimit() {
        assertEquals("", PeSession.joinSidebarLines(null, 0, 0), "no lines at all");
        assertEquals("a\nb", PeSession.joinSidebarLines(new String[]{"a", "b"}, 0, 0));
        assertEquals("a\n\nc", PeSession.joinSidebarLines(new String[]{"a", null, "c"}, 0, 0),
                "a null row is an empty row, not the text 'null'");

        String[] tooMany = new String[Player.SIDEBAR_MAX_LINES + 4];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = "row" + i;
        }
        String joined = PeSession.joinSidebarLines(tooMany, 0, 0);
        assertEquals(Player.SIDEBAR_MAX_LINES, joined.split("\n", -1).length, "capped like the Java sidebar");
        assertTrue(joined.startsWith("row0"));
        assertFalse(joined.contains("row" + Player.SIDEBAR_MAX_LINES), "the overflow rows are dropped");
    }

    @Test
    void raiseAndShiftPadThePanelIntoPlace() {
        // The client places the popup; padding the text is the only lever, so raise pads rows under it
        // (lifting it off the hotbar) and shift pads spaces on each row.
        assertEquals("a\n \n ", PeSession.joinSidebarLines(new String[]{"a"}, 2, 0),
                "two pad rows below — a space, not an empty line a renderer could drop");
        assertEquals(" \n \na", PeSession.joinSidebarLines(new String[]{"a"}, -2, 0),
                "a negative raise pads above instead");
        assertEquals("  a\n  b", PeSession.joinSidebarLines(new String[]{"a", "b"}, 0, 2),
                "a positive shift pads the left of every row");
        assertEquals("a  \nb  ", PeSession.joinSidebarLines(new String[]{"a", "b"}, 0, -2),
                "a negative shift pads the right");
        assertEquals("a\nb", PeSession.joinSidebarLines(new String[]{"a", "b"}, 0, 0),
                "both at zero is the raw, centred panel");

        assertEquals(" x", PeSession.pad("x", 1), "the title takes the same sideways pad");
        assertEquals("", PeSession.pad("", 4), "an empty row stays empty — padding it would be a stray space");
    }
}
