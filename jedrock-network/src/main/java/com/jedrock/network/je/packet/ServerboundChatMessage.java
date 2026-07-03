package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Chat Message (0x02 in PLAY) for JE 1.12.2 — the raw text a player typed.
 */
public final class ServerboundChatMessage implements ServerboundPacket {

    public static final int PACKET_ID = 0x02;

    /** Max chat length the vanilla 1.12.2 client sends; longer messages are rejected. */
    private static final int MAX_LENGTH = 256;

    public String message;

    public static ServerboundChatMessage fromBuffer(ByteBuf buf) {
        ServerboundChatMessage p = new ServerboundChatMessage();
        p.message = ByteBufUtils.readString(buf);
        if (p.message.length() > MAX_LENGTH) {
            p.message = p.message.substring(0, MAX_LENGTH);
        }
        return p;
    }

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
