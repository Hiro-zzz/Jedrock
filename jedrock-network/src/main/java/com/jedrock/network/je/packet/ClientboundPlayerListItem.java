package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Clientbound Player List Item (0x2E) for JE 1.12.2 — adds or removes a single entry
 * in the tab list. Used to show every online player (Java and Bedrock) to Java clients.
 */
public final class ClientboundPlayerListItem implements ClientboundPacket {

    private static final int ACTION_ADD = 0;
    private static final int ACTION_REMOVE = 4;

    private final int action;
    private final UUID uuid;
    private final String name;   // add only
    private final int gamemode;  // add only

    private ClientboundPlayerListItem(int action, UUID uuid, String name, int gamemode) {
        this.action = action;
        this.uuid = uuid;
        this.name = name;
        this.gamemode = gamemode;
    }

    public static ClientboundPlayerListItem add(UUID uuid, String name, int gamemode) {
        return new ClientboundPlayerListItem(ACTION_ADD, uuid, name, gamemode);
    }

    public static ClientboundPlayerListItem remove(UUID uuid) {
        return new ClientboundPlayerListItem(ACTION_REMOVE, uuid, null, 0);
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, action);
        ByteBufUtils.writeVarInt(buf, 1); // one entry
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        if (action == ACTION_ADD) {
            ByteBufUtils.writeString(buf, name);
            ByteBufUtils.writeVarInt(buf, 0);        // property count
            ByteBufUtils.writeVarInt(buf, gamemode);
            ByteBufUtils.writeVarInt(buf, 0);        // ping (ms)
            buf.writeBoolean(false);                 // no custom display name
        }
    }

    @Override
    public int getPacketId() {
        return 0x2E;
    }
}
