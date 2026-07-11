package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Confirm Transaction (0x11) for JE 1.12.2 — acknowledges a Click Window. Jedrock is
 * server-authoritative and resyncs the affected slots itself, so it always confirms {@code accepted},
 * avoiding the client's reject/apologise round-trip; the explicit slot resync is the real correction.
 */
public final class ClientboundConfirmTransaction implements ClientboundPacket {

    private final int windowId;
    private final int actionNumber;
    private final boolean accepted;

    public ClientboundConfirmTransaction(int windowId, int actionNumber, boolean accepted) {
        this.windowId = windowId;
        this.actionNumber = actionNumber;
        this.accepted = accepted;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(windowId);
        buf.writeShort(actionNumber);
        buf.writeBoolean(accepted);
    }

    @Override
    public int getPacketId() {
        return 0x11;
    }
}
