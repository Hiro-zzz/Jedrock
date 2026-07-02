package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serverbound Login Start (0x00 in LOGIN state).
 * Contains the player's username.
 */
public final class ServerboundLoginStart {

    public String username;

    public static ServerboundLoginStart fromBuffer(ByteBuf buf) {
        ServerboundLoginStart p = new ServerboundLoginStart();
        p.username = ByteBufUtils.readString(buf);
        return p;
    }

    public int getPacketId() {
        return 0x00;
    }
}
