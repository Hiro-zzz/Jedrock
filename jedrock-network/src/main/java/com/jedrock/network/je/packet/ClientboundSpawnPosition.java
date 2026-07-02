package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Spawn Position (0x46) for 1.12.2.
 */
public final class ClientboundSpawnPosition implements ClientboundPacket {

    public int x, y, z;

    public ClientboundSpawnPosition() {
        this.x = 0;
        this.y = 64;
        this.z = 0;
    }

    public void write(ByteBuf buf) {
        // Position is encoded as long (x << 38 | y << 26 | z) in 1.12+
        long pos = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        buf.writeLong(pos);
    }

    public int getPacketId() {
        return 0x46;
    }
}
