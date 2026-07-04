package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Unload Chunk (0x1D) for JE 1.12.2 — tells the client to drop a chunk column that
 * has moved out of view distance.
 */
public final class ClientboundUnloadChunk implements ClientboundPacket {

    private final int chunkX;
    private final int chunkZ;

    public ClientboundUnloadChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    @Override
    public int getPacketId() {
        return 0x1D;
    }
}
