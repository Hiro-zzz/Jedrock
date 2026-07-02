package com.jedrock.network.je.packet;

import io.netty.buffer.ByteBuf;

/**
 * Clientbound Player Abilities (0x2C) for 1.12.2.
 */
public final class ClientboundPlayerAbilities implements ClientboundPacket {

    public byte flags;          // 0x01 invulnerable, 0x02 flying, 0x04 allow flying, 0x08 creative
    public float flyingSpeed;
    public float fovModifier;  // also called "walkSpeed" in some client mappings, but it's the fov modifier

    public ClientboundPlayerAbilities() {
        this.flags = 0x0C; // allow flying + creative (good default)
        this.flyingSpeed = 0.05f;
        this.fovModifier = 0.1f;
    }

    public void write(ByteBuf buf) {
        buf.writeByte(flags);
        buf.writeFloat(flyingSpeed);
        buf.writeFloat(fovModifier);
    }

    public int getPacketId() {
        return 0x2C;
    }
}
