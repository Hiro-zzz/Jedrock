package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Server Difficulty (0x0D) for JE 1.12.2.
 *
 * Format (protocol 340):
 *   difficulty : Unsigned Byte   (0=peaceful, 1=easy, 2=normal, 3=hard)
 *
 * Note: There is NO difficultyLocked boolean in 1.12.2 (added in later versions).
 */
public final class ClientboundServerDifficulty implements ClientboundPacket {

    public int difficulty; // 0 peaceful ... 3 hard

    public ClientboundServerDifficulty() {
        this.difficulty = 2; // normal
    }

    public void write(ByteBuf buf) {
        buf.writeByte(difficulty);
        // Only 1 byte for 1.12.2. Do NOT write extra fields.
    }

    public int getPacketId() {
        return 0x0D;
    }
}
