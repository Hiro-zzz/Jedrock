package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Update Health (0x41) for 1.12.2 — sets the health, food and saturation bars. Jedrock
 * uses it for server-tracked fall damage; food and saturation are pinned full (no hunger simulation).
 */
public final class ClientboundUpdateHealth implements ClientboundPacket {

    private final float health;

    /** @param health 0..20 (half-heart points). */
    public ClientboundUpdateHealth(int health) {
        this.health = health;
    }

    @Override
    public void write(ByteBuf buf) {
        buf.writeFloat(health);
        ByteBufUtils.writeVarInt(buf, 20); // food (full — no hunger model)
        buf.writeFloat(5.0f);              // food saturation
    }

    @Override
    public int getPacketId() {
        return 0x41;
    }
}
