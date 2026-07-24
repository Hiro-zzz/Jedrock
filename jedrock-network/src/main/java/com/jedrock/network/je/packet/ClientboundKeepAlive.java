package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Keep Alive (0x1F) for 1.12.2.
 */
public final class ClientboundKeepAlive implements ClientboundPacket {

    public long keepAliveId;

    public ClientboundKeepAlive(long keepAliveId) {
        this.keepAliveId = keepAliveId;
    }

    public void write(ByteBuf buf) {
        buf.writeLong(keepAliveId);
    }

    public int getPacketId() {
        return 0x1F;
    }
}
