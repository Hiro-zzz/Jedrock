package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Change Game State (0x1E) for 1.12.2 — a small reason/value packet. Jedrock uses it for
 * one thing: reason {@link #REASON_CHANGE_GAMEMODE} with the new mode id as the value, so the client
 * flips its HUD (health/hunger bars, break timing) when a player switches game mode live.
 */
public final class ClientboundChangeGameState implements ClientboundPacket {

    /** "Change game mode" — the float value is the target {@link com.jedrock.api.player.GameMode} id. */
    public static final int REASON_CHANGE_GAMEMODE = 3;

    private final int reason;
    private final float value;

    public ClientboundChangeGameState(int reason, float value) {
        this.reason = reason;
        this.value = value;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeByte(reason);
        buf.writeFloat(value);
    }

    @Override
    public int getPacketId() {
        return 0x1E;
    }
}
