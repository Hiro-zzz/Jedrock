package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Handshake (0x00 in HANDSHAKE state).
 * JE 1.12.2 protocol 340.
 */
public final class ServerboundHandshake {

    public int protocolVersion;
    public String serverAddress;
    public int serverPort;
    public int nextState; // 1 = STATUS, 2 = LOGIN

    public static ServerboundHandshake fromBuffer(ByteBuf buf) {
        ServerboundHandshake p = new ServerboundHandshake();
        p.protocolVersion = ByteBufUtils.readVarInt(buf);
        p.serverAddress = ByteBufUtils.readString(buf);
        p.serverPort = buf.readUnsignedShort();
        p.nextState = ByteBufUtils.readVarInt(buf);
        return p;
    }

    public int getPacketId() {
        return 0x00;
    }
}
