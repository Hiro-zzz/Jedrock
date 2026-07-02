package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Clientbound Login Success (0x02).
 * Switches the client from LOGIN to PLAY state.
 */
public final class ClientboundLoginSuccess implements ClientboundPacket {

    public UUID uuid;
    public String username;

    public ClientboundLoginSuccess(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public void write(ByteBuf buf) {
        // In 1.12.2, UUID is sent as string (hyphenated)
        ByteBufUtils.writeString(buf, uuid.toString());
        ByteBufUtils.writeString(buf, username);
    }

    public int getPacketId() {
        return 0x02;
    }
}
