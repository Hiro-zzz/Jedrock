package com.jedrock.utils;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Lightweight ByteBuf helpers for Minecraft protocols.
 * Focused on zero-copy where possible and lazy-friendly patterns.
 */
public final class ByteBufUtils {

    /** Hard cap on length-prefixed data, guarding against malformed/hostile VarInt sizes. */
    private static final int MAX_ARRAY_LENGTH = 2 * 1024 * 1024; // 2 MiB

    private ByteBufUtils() {}

    // ========== VarInt (Java Edition) ==========

    public static int readVarInt(ByteBuf buf) {
        int result = 0;
        int bytesRead = 0;
        byte current;
        do {
            current = buf.readByte();
            result |= (current & 0x7F) << (bytesRead * 7);
            bytesRead++;
            if (bytesRead > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((current & 0x80) != 0);
        return result;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static int varIntSize(int value) {
        int size = 0;
        while (true) {
            size++;
            if ((value & ~0x7F) == 0) break;
            value >>>= 7;
        }
        return size;
    }

    // ========== Strings ==========

    public static String readString(ByteBuf buf) {
        return new String(readByteArray(buf), StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    // ========== Byte arrays (common for NBT, chunks etc) ==========

    public static byte[] readByteArray(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > MAX_ARRAY_LENGTH) {
            throw new IllegalArgumentException("Declared length out of bounds: " + len);
        }
        if (len > buf.readableBytes()) {
            throw new IllegalArgumentException("Declared length " + len + " exceeds readable bytes " + buf.readableBytes());
        }
        byte[] arr = new byte[len];
        buf.readBytes(arr);
        return arr;
    }

    public static void writeByteArray(ByteBuf buf, byte[] array) {
        writeVarInt(buf, array.length);
        buf.writeBytes(array);
    }

    // ========== Position (JE packed long) ==========

    public static long readPosition(ByteBuf buf) {
        return buf.readLong();
    }

    public static void writePosition(ByteBuf buf, long position) {
        buf.writeLong(position);
    }
}
