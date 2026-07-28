package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Respawn (0x35) for 1.12.2 — the packet that moves a client to another world.
 *
 * <p>Its body is Join Game's tail, and that is the point: the client tears its world down and rebuilds
 * it from the chunks that follow, which is the only way to replace terrain it already holds. Sending it
 * is what makes a nether look like a nether — the dimension field drives the sky, the fog and the
 * compass, none of which a teleport alone would change.
 *
 * <p>1.8 (protocol 47) has the same body under id {@code 0x07}; it is written inline by the 1.8 handler
 * against its own id table rather than through this class.
 */
public final class ClientboundRespawn implements ClientboundPacket {

    public final int dimension;   // -1 nether, 0 overworld, 1 end
    public final int difficulty;  // 0-3
    public final int gamemode;    // 0 survival, 1 creative, …
    public final String levelType;

    public ClientboundRespawn(int dimension, int gamemode) {
        this(dimension, 2, gamemode, "default"); // difficulty 2 = Normal, matching Join Game
    }

    public ClientboundRespawn(int dimension, int difficulty, int gamemode, String levelType) {
        this.dimension = dimension;
        this.difficulty = difficulty;
        this.gamemode = gamemode;
        this.levelType = levelType;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeByte(difficulty);
        buf.writeByte(gamemode);
        ByteBufUtils.writeString(buf, levelType);
    }

    @Override
    public int getPacketId() {
        return 0x35;
    }
}
