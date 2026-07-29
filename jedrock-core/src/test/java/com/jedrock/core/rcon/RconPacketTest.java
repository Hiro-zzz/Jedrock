package com.jedrock.core.rcon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The RCON wire format, byte for byte. Every detail pinned here is one that makes a hand-written client
 * half-work if we get it wrong: the integers are little-endian, the length excludes itself, and there are
 * two trailing nulls rather than the one a body needs.
 */
class RconPacketTest {

    @Test
    void theBodyIsFramedExactlyAsTheProtocolSays() {
        byte[] bytes = new RconPacket(7, RconPacket.TYPE_EXEC_COMMAND, "list").encode();

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4 + 4 + 4 + 2, buf.getInt(), "length counts everything after itself: id, type, body, 2 nulls");
        assertEquals(7, buf.getInt(), "request id, echoed back by the server");
        assertEquals(2, buf.getInt(), "SERVERDATA_EXECCOMMAND");
        byte[] body = new byte[4];
        buf.get(body);
        assertEquals("list", new String(body, StandardCharsets.UTF_8));
        assertEquals(0, buf.get(), "body terminator");
        assertEquals(0, buf.get(), "packet terminator");
        assertEquals(4 + 4 + 4 + 4 + 2, bytes.length, "and nothing else");
    }

    @Test
    void theIntegersAreLittleEndian() {
        byte[] bytes = new RconPacket(1, RconPacket.TYPE_AUTH, "").encode();
        // id = 1 as LE is 01 00 00 00; read big-endian it would be 16777216, which is how a server that
        // gets this wrong ends up echoing an id no client recognises.
        assertEquals(0x01, bytes[4]);
        assertEquals(0x00, bytes[5]);
        assertEquals(0x00, bytes[6]);
        assertEquals(0x00, bytes[7]);
    }

    @Test
    void whatIsWrittenIsWhatIsRead() throws IOException {
        for (RconPacket original : new RconPacket[]{
                new RconPacket(0, RconPacket.TYPE_AUTH, "hunter2"),
                new RconPacket(-1, RconPacket.TYPE_AUTH_RESPONSE, ""),
                new RconPacket(42, RconPacket.TYPE_RESPONSE_VALUE, "players (2):\n  Steve\n  Alex"),
                new RconPacket(3, RconPacket.TYPE_EXEC_COMMAND, "say привет")}) {
            RconPacket read = RconPacket.read(new ByteArrayInputStream(original.encode()));
            assertEquals(original, read);
        }
    }

    @Test
    void backToBackPacketsAreReadInOrder() throws IOException {
        byte[] first = new RconPacket(1, RconPacket.TYPE_AUTH, "pw").encode();
        byte[] second = new RconPacket(2, RconPacket.TYPE_EXEC_COMMAND, "status").encode();
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        ByteArrayInputStream in = new ByteArrayInputStream(both);
        assertEquals("pw", RconPacket.read(in).body());
        assertEquals("status", RconPacket.read(in).body());
        assertNull(RconPacket.read(in), "a clean end of stream is not an error");
    }

    @Test
    void anImpossibleLengthIsRefusedRatherThanAllocated() {
        // The length field is the first thing a hostile client controls, and `new byte[length]` on an
        // unchecked int is how twelve bytes ask for two gigabytes.
        byte[] hostile = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MAX_VALUE).array();
        assertThrows(IOException.class, () -> RconPacket.read(new ByteArrayInputStream(hostile)));

        byte[] tooSmall = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array();
        assertThrows(IOException.class, () -> RconPacket.read(new ByteArrayInputStream(tooSmall)));
    }

    @Test
    void aTruncatedPacketIsAnErrorNotAGuess() {
        byte[] whole = new RconPacket(1, RconPacket.TYPE_EXEC_COMMAND, "stop").encode();
        byte[] half = new byte[whole.length - 3];
        System.arraycopy(whole, 0, half, 0, half.length);
        assertThrows(EOFException.class, () -> RconPacket.read(new ByteArrayInputStream(half)));
    }
}
