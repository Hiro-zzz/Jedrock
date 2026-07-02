package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * A Java Edition packet the server sends to the client.
 *
 * <p>Deliberately tiny: an ID plus a way to write the payload. Framing (VarInt length)
 * and the packet-ID VarInt are added by the connection/pipeline, so implementations only
 * write their own body into {@code buf}.
 */
public interface ClientboundPacket {

    /** Protocol-specific packet identifier for JE 1.12.2. */
    int getPacketId();

    /** Write only this packet's payload (no ID, no length prefix). */
    void write(ByteBuf buf);
}
