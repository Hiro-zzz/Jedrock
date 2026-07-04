package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Spawn Position (0x46) for 1.12.2.
 */
public final class ClientboundSpawnPosition implements ClientboundPacket {

    public int x, y, z;

    public ClientboundSpawnPosition() {
        this(0, 64, 0);
    }

    public ClientboundSpawnPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
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
