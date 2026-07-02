package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Chat Message (0x0F) for 1.12.2.
 * Used to send system messages.
 */
public final class ClientboundChatMessage implements ClientboundPacket {

    public String jsonMessage;
    public byte position; // 0 = chat, 1 = system, 2 = game info (action bar)

    public ClientboundChatMessage(String jsonMessage) {
        this.jsonMessage = jsonMessage;
        this.position = 1; // system message
    }

    public void write(ByteBuf buf) {
        ByteBufUtils.writeString(buf, jsonMessage);
        buf.writeByte(position);
    }

    public int getPacketId() {
        return 0x0F;
    }
}
