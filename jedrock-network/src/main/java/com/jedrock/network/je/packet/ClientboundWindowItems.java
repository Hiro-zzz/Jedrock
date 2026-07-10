package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Window Items (0x14) for 1.12.2 — replaces the whole contents of a window. Jedrock sends
 * it for window 0 (the player inventory) to reflect the minimal survival inventory. The slot layout is
 * encoded by the shared JE inventory codec, so this packet just carries the pre-encoded body.
 */
public final class ClientboundWindowItems implements ClientboundPacket {

    private final byte[] body;

    /** @param body the already-encoded packet body (windowId + count + slots). */
    public ClientboundWindowItems(byte[] body) {
        this.body = body;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeBytes(body);
    }

    @Override
    public int getPacketId() {
        return 0x14;
    }
}
