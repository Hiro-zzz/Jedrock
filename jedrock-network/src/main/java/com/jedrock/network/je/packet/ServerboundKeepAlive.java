package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Serverbound Keep Alive (0x0B in 1.12.2).
 */
public final class ServerboundKeepAlive {

    public long keepAliveId;

    public static ServerboundKeepAlive fromBuffer(ByteBuf buf) {
        ServerboundKeepAlive p = new ServerboundKeepAlive();
        p.keepAliveId = buf.readLong();
        return p;
    }

    public int getPacketId() {
        return 0x0B;
    }
}
