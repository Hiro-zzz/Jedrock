package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Join Game (0x23) for 1.12.2.
 * This tells the client the dimension, game mode, etc.
 */
public final class ClientboundJoinGame implements ClientboundPacket {

    public int entityId;
    public int gamemode;      // 0 survival, 1 creative, etc. + 0x8 for hardcore
    public int dimension;     // -1 nether, 0 overworld, 1 end
    public int difficulty;    // 0-3
    public int maxPlayers;
    public String levelType;  // "default", "flat", etc.
    public boolean reducedDebugInfo;

    public ClientboundJoinGame() {
        this(10, 1);
    }

    public ClientboundJoinGame(int maxPlayers, int gamemode) {
        // Reasonable defaults for a minimal server
        this.entityId = 1;
        this.gamemode = gamemode; // the player's join game mode (from config)
        this.dimension = 0;
        this.difficulty = 2;     // Normal
        this.maxPlayers = maxPlayers;
        this.levelType = "default";
        this.reducedDebugInfo = false;
    }

    public void write(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByte(gamemode);
        buf.writeInt(dimension);
        buf.writeByte(difficulty);
        buf.writeByte(maxPlayers);
        ByteBufUtils.writeString(buf, levelType);
        buf.writeBoolean(reducedDebugInfo);
    }

    public int getPacketId() {
        return 0x23;
    }
}
