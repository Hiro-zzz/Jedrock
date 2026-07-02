package com.jedrock.network.util;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

/**
 * Small helper for building raw packet buffers the Jedrock way.
 *
 * Usage:
 * <pre>
 * ByteBuf buf = PacketBuffer.create(allocator)
 *     .writeVarInt(packetId)
 *     .writeString("hello")
 *     .toBuffer();
 * connection.send(buf);
 * </pre>
 */
public final class PacketBuffer {

    private final ByteBuf buffer;

    private PacketBuffer(ByteBuf buffer) {
        this.buffer = buffer;
    }

    public static PacketBuffer create(ByteBufAllocator alloc) {
        return new PacketBuffer(alloc.buffer());
    }

    public static PacketBuffer create() {
        return new PacketBuffer(Unpooled.buffer());
    }

    public PacketBuffer writeVarInt(int value) {
        ByteBufUtils.writeVarInt(buffer, value);
        return this;
    }

    public PacketBuffer writeString(String value) {
        ByteBufUtils.writeString(buffer, value);
        return this;
    }

    public PacketBuffer writeShort(int value) {
        buffer.writeShort(value);
        return this;
    }

    public PacketBuffer writeBytes(byte[] bytes) {
        buffer.writeBytes(bytes);
        return this;
    }

    public ByteBuf toBuffer() {
        return buffer;
    }

    public ByteBuf getBuffer() {
        return buffer;
    }
}
