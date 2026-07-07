package com.jedrock.network.pe.v014;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/**
 * Builds an MCPE 0.14 (protocol 45) BatchPacket (id {@code 0x92}) body, used for large packets
 * (chunks) that shouldn't be sent raw. Verified against PocketMine-MP {@code BatchPacket} +
 * {@code Server::batchPackets} at protocol 45.
 *
 * <p>Wire (before the caller prepends the {@code 0x8e} game wrapper):
 * <pre>
 *   [0x92][int32 BE compressedLen][ zlib-deflate( for each pkt: [int32 BE len][pkt bytes] ) ]
 * </pre>
 * The inner packets are their own {@code [id][body]} with NO {@code 0x8e}. {@code zlib_encode(..DEFLATE)}
 * is the zlib format, i.e. {@link Deflater} with {@code nowrap=false}.
 */
public final class Mcpe014Batch {

    private Mcpe014Batch() {}

    /** Build the batch body ({@code 0x92}…) from one or more already-encoded {@code [id][body]} packets. */
    public static byte[] of(byte[]... packets) {
        // payload = concat of [int32 len][pkt]
        int payloadLen = 0;
        for (byte[] p : packets) {
            payloadLen += 4 + p.length;
        }
        byte[] payload = new byte[payloadLen];
        int pos = 0;
        for (byte[] p : packets) {
            payload[pos] = (byte) (p.length >> 24);
            payload[pos + 1] = (byte) (p.length >> 16);
            payload[pos + 2] = (byte) (p.length >> 8);
            payload[pos + 3] = (byte) p.length;
            pos += 4;
            System.arraycopy(p, 0, payload, pos, p.length);
            pos += p.length;
        }

        byte[] compressed = deflate(payload);

        byte[] out = new byte[1 + 4 + compressed.length];
        out[0] = (byte) Mcpe014Packets.ID_BATCH;
        out[1] = (byte) (compressed.length >> 24);
        out[2] = (byte) (compressed.length >> 16);
        out[3] = (byte) (compressed.length >> 8);
        out[4] = (byte) compressed.length;
        System.arraycopy(compressed, 0, out, 5, compressed.length);
        return out;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION); // zlib format (nowrap=false)
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }
}
