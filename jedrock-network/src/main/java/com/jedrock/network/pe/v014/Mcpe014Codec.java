package com.jedrock.network.pe.v014;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire codec for Bedrock/MCPE 0.14 (protocol 45) — the pre-VarInt, big-endian era.
 *
 * <p>Confirmed against a real 0.14 client (see {@code mcpe-protocol-45-reference}): fixed-width
 * integers are big-endian, which is exactly Netty's {@link ByteBuf} default, so {@code writeInt} /
 * {@code readInt} / {@code writeLong} / {@code writeFloat} need no wrapper here. Only the composite
 * types differ from 1.1.5's VarInt forms: a <b>string</b> is a 2-byte big-endian unsigned-short length
 * followed by its UTF-8 bytes, and a <b>UUID</b> is 16 raw bytes. This is the 0.14 counterpart of
 * {@code McpeCodec} (which speaks the protocol-113 VarInt forms).
 */
public final class Mcpe014Codec {

    private Mcpe014Codec() {}

    /** Read a 0.14 string: 2-byte BE length + UTF-8 bytes. */
    public static String readString(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Write a 0.14 string: 2-byte BE length + UTF-8 bytes. */
    public static void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    /** Read a length-prefixed raw byte blob (2-byte BE length), e.g. the inline skin texture. */
    public static byte[] readByteArray(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return b;
    }

    /** Read a 16-byte UUID (most-significant long first). */
    public static UUID readUuid(ByteBuf buf) {
        long hi = buf.readLong();
        long lo = buf.readLong();
        return new UUID(hi, lo);
    }

    /** Write a 16-byte UUID (most-significant long first). */
    public static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }
}
