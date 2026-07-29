package com.jedrock.core.rcon;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One Source RCON packet, and the whole of the wire format.
 *
 * <pre>
 *   int32  length   little-endian, of everything after this field
 *   int32  id       the client's request id, echoed in the reply
 *   int32  type     what this packet is (below)
 *   bytes  body     the password, the command, or the output
 *   byte   0        terminates the body
 *   byte   0        terminates the packet
 * </pre>
 *
 * <p>Two nulls, not one, and the length excludes itself: both are the kind of detail that makes a
 * hand-written client half-work, so they are pinned by a byte test rather than trusted.
 *
 * <p>Every integer is <b>little-endian</b>, which is why this reads them by hand — Java's
 * {@code DataInputStream} is big-endian and would silently produce enormous lengths from small ones.
 *
 * <p>Sizes are bounded on read. A length field is the first thing a hostile client controls, and
 * {@code new byte[length]} on an unchecked int is how a single 12-byte packet asks for two gigabytes.
 */
public record RconPacket(int id, int type, String body) {

    /** Server → client: command output, or the empty packet that precedes an auth answer. */
    public static final int TYPE_RESPONSE_VALUE = 0;
    /** Client → server: run this command. */
    public static final int TYPE_EXEC_COMMAND = 2;
    /** Server → client: the answer to an auth attempt. Shares its number with EXEC_COMMAND, by design. */
    public static final int TYPE_AUTH_RESPONSE = 2;
    /** Client → server: here is the password. */
    public static final int TYPE_AUTH = 3;

    /** The id a server puts in an AUTH_RESPONSE to mean "no". Every real id is non-negative. */
    public static final int AUTH_FAILED_ID = -1;

    /** The body limit both ends of this protocol have always assumed. Longer output is split. */
    public static final int MAX_BODY_BYTES = 4096;

    /** id + type + the two terminating nulls: what a packet costs before it says anything. */
    private static final int OVERHEAD = 4 + 4 + 1 + 1;

    /** The bytes of this packet, length prefix included — ready to write to a socket. */
    public byte[] encode() {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + OVERHEAD + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(OVERHEAD + payload.length); // length counts everything after itself
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payload);
        buf.put((byte) 0); // end of body
        buf.put((byte) 0); // end of packet
        return buf.array();
    }

    /**
     * Read one packet from {@code in}, blocking until it is whole.
     *
     * @return the packet, or {@code null} at a clean end of stream (the client hung up between packets)
     * @throws IOException if the stream ends mid-packet or the client claims an impossible size
     */
    public static RconPacket read(InputStream in) throws IOException {
        byte[] header = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null; // hung up politely, between packets
        }
        header[0] = (byte) first;
        readFully(in, header, 1, 3);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < OVERHEAD || length > MAX_BODY_BYTES + OVERHEAD) {
            throw new IOException("RCON packet claims a length of " + length + " bytes");
        }

        byte[] rest = new byte[length];
        readFully(in, rest, 0, length);
        ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        int type = buf.getInt();
        // The body runs to its null terminator; a client that lies about where that is only shortens
        // its own command, so the rest of the packet is simply ignored.
        int bodyLength = 0;
        while (bodyLength < rest.length - 8 - 1 && rest[8 + bodyLength] != 0) {
            bodyLength++;
        }
        String body = new String(rest, 8, bodyLength, StandardCharsets.UTF_8);
        return new RconPacket(id, type, body);
    }

    private static void readFully(InputStream in, byte[] into, int offset, int count) throws IOException {
        int read = 0;
        while (read < count) {
            int n = in.read(into, offset + read, count - read);
            if (n < 0) {
                throw new EOFException("RCON stream ended mid-packet");
            }
            read += n;
        }
    }
}
