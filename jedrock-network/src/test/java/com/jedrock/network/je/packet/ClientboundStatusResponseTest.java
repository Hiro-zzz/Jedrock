package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status Response is hand-built JSON, so verify its shape and that string fields are escaped
 * (an unescaped quote in the MOTD would produce a malformed blob the client rejects).
 */
class ClientboundStatusResponseTest {

    private static String encodedJson(ClientboundStatusResponse packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            packet.write(buf);
            return ByteBufUtils.readString(buf); // VarInt-length-prefixed UTF-8
        } finally {
            buf.release();
        }
    }

    @Test
    void buildsExpectedJsonShape() {
        String json = encodedJson(ClientboundStatusResponse.of("1.12.2", 340, 20, 3, "Jedrock"));
        assertEquals(0x00, ClientboundStatusResponse.of("1.12.2", 340, 0, 0, "x").getPacketId());
        assertTrue(json.contains("\"name\":\"1.12.2\""), json);
        assertTrue(json.contains("\"protocol\":340"), json);
        assertTrue(json.contains("\"max\":20"), json);
        assertTrue(json.contains("\"online\":3"), json);
        assertTrue(json.contains("\"sample\":[]"), json);
        assertTrue(json.contains("\"description\":{\"text\":\"Jedrock\"}"), json);
    }

    @Test
    void escapesQuotesAndBackslashesInMotd() {
        String json = encodedJson(ClientboundStatusResponse.of("1.12.2", 340, 20, 0, "a\"b\\c"));
        // The MOTD a"b\c must appear as a\"b\\c inside the JSON string.
        assertTrue(json.contains("\"text\":\"a\\\"b\\\\c\""), json);
    }
}
