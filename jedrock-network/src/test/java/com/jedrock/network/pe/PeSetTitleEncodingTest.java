package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_SET_TITLE;
import static com.jedrock.network.pe.McpeProtocol.TITLE_TYPE_ACTIONBAR;
import static com.jedrock.network.pe.McpeProtocol.TITLE_TYPE_CLEAR;
import static com.jedrock.network.pe.McpeProtocol.TITLE_TYPE_TITLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the SetTitle (0x59) encoding, verbatim from PMMP {@code SetTitlePacket} at protocol
 * 113: type (signed varint), text (string), fadeIn / stay / fadeOut (signed varints, ticks).
 */
class PeSetTitleEncodingTest {

    @Test
    void titleBodyMatchesProtocol113() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeSetTitle(b, TITLE_TYPE_TITLE, "Hi", 5, 40, 5);

        assertEquals(ID_SET_TITLE, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(TITLE_TYPE_TITLE, ByteBufUtils.readSignedVarInt(b), "type (putVarInt = zigzag)");
        assertEquals("Hi", ByteBufUtils.readString(b), "text");
        assertEquals(5, ByteBufUtils.readSignedVarInt(b), "fadeIn");
        assertEquals(40, ByteBufUtils.readSignedVarInt(b), "stay");
        assertEquals(5, ByteBufUtils.readSignedVarInt(b), "fadeOut");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void actionBarAndClearUseTheirTypes() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeSetTitle(b, TITLE_TYPE_ACTIONBAR, "Wave", 1, 20, 1);
        ByteBufUtils.readVarInt(b); // id
        assertEquals(TITLE_TYPE_ACTIONBAR, ByteBufUtils.readSignedVarInt(b), "action-bar type");
        assertEquals("Wave", ByteBufUtils.readString(b));
        b.release();

        ByteBuf c = Unpooled.buffer();
        PeSession.writeSetTitle(c, TITLE_TYPE_CLEAR, "", 0, 0, 0);
        ByteBufUtils.readVarInt(c); // id
        assertEquals(TITLE_TYPE_CLEAR, ByteBufUtils.readSignedVarInt(c), "clear type");
        assertEquals("", ByteBufUtils.readString(c), "empty text for a clear");
        c.release();
    }
}
