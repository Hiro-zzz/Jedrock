package com.jedrock.network.je.packet;

import com.jedrock.api.player.GameMode;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound Player Abilities (0x2C) for 1.12.2.
 */
public final class ClientboundPlayerAbilities implements ClientboundPacket {

    public byte flags;          // 0x01 invulnerable, 0x02 flying, 0x04 allow flying, 0x08 creative
    public float flyingSpeed;
    public float fovModifier;  // also called "walkSpeed" in some client mappings, but it's the fov modifier

    public ClientboundPlayerAbilities() {
        this(GameMode.CREATIVE);
    }

    /**
     * Abilities matching a game mode: creative gets the creative + allow-flight bits (and stays
     * invulnerable), survival/adventure get neither, so the client can't free-fly outside creative.
     */
    public ClientboundPlayerAbilities(GameMode mode) {
        int f = 0;
        if (mode == GameMode.CREATIVE) {
            f |= 0x08 | 0x04 | 0x01; // creative | allow-fly | invulnerable
        } else if (mode.allowsFlight()) {
            f |= 0x04; // spectator: fly allowed, not creative
        }
        this.flags = (byte) f;
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
