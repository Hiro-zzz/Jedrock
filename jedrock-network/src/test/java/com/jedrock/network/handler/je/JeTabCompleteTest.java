package com.jedrock.network.handler.je;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte layout of the shared clientbound Tab-Complete body (VarInt count + strings), the encoding both JE
 * versions use behind their own packet ids.
 */
class JeTabCompleteTest {

    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void tearDown() {
        buf.release();
    }

    @Test
    void writesCountThenEachMatchAsAString() {
        JeTabComplete.write(buf, List.of("/gamemode", "/gm"));

        assertEquals(2, ByteBufUtils.readVarInt(buf), "match count");
        assertEquals("/gamemode", ByteBufUtils.readString(buf));
        assertEquals("/gm", ByteBufUtils.readString(buf));
        assertFalse(buf.isReadable(), "no trailing bytes");
    }

    @Test
    void anEmptyListIsJustAZeroCount() {
        JeTabComplete.write(buf, List.of());

        assertEquals(0, ByteBufUtils.readVarInt(buf));
        assertFalse(buf.isReadable());
    }

    @Test
    void argumentMatchesAreBareTokens() {
        // Past the command name, completions are bare (the client replaces the last word "cr" with it).
        JeTabComplete.write(buf, List.of("creative"));

        assertEquals(1, ByteBufUtils.readVarInt(buf));
        assertEquals("creative", ByteBufUtils.readString(buf));
    }
}
