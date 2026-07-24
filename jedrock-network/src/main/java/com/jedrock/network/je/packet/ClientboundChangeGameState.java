package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Change Game State (0x1E) for 1.12.2 — a small reason/value packet. Jedrock uses it for
 * one thing: reason {@link #REASON_CHANGE_GAMEMODE} with the new mode id as the value, so the client
 * flips its HUD (health/hunger bars, break timing) when a player switches game mode live.
 */
public final class ClientboundChangeGameState implements ClientboundPacket {

    /** "End raining" — value unused. Same reason id on 1.8 and 1.12.2. */
    public static final int REASON_END_RAIN = 1;
    /** "Begin raining" — value unused. Same reason id on 1.8 and 1.12.2. */
    public static final int REASON_BEGIN_RAIN = 2;
    /**
     * Rain strength (0..1). The client maps this reason straight to {@code setRainStrength} — begin
     * raining alone only ramps in slowly, so send 1.0 for instantly visible rain (client-verified:
     * sending 0 here after begin-rain silently kills the rain that was just started).
     */
    public static final int REASON_RAIN_STRENGTH = 7;
    /** Thunder strength (0..1) — {@code setThunderStrength}: the darkened thunderstorm sky. */
    public static final int REASON_THUNDER_STRENGTH = 8;

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
