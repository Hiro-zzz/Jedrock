package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.text.JsonText;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Status Response (0x00, STATUS state) for 1.12.2 — the JSON blob the multiplayer
 * list renders: server version, player counts and the MOTD. Built with {@link #of} so the tiny,
 * fixed-shape JSON needs no dependency; string fields are escaped for embedding.
 */
public final class ClientboundStatusResponse implements ClientboundPacket {

    private final String json;

    public ClientboundStatusResponse(String json) {
        this.json = json;
    }

    /** Assemble the standard status JSON from its parts (an empty player sample). */
    public static ClientboundStatusResponse of(String versionName, int protocol,
                                               int maxPlayers, int online, String motd) {
        String json = "{"
                + "\"version\":{\"name\":\"" + JsonText.escape(versionName) + "\",\"protocol\":" + protocol + "},"
                + "\"players\":{\"max\":" + maxPlayers + ",\"online\":" + online + ",\"sample\":[]},"
                + "\"description\":{\"text\":\"" + JsonText.escape(motd) + "\"}"
                + "}";
        return new ClientboundStatusResponse(json);
    }

    public void write(ByteBuf buf) {
        ByteBufUtils.writeString(buf, json);
    }

    public int getPacketId() {
        return 0x00;
    }
}
