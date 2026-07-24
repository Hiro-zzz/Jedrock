package com.jedrock.network.pe;

import com.jedrock.api.world.Particle;
import com.jedrock.api.world.Sound;
import com.jedrock.network.pe.v014.Mcpe014Packets;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level checks for the sound / particle wire, verbatim from PMMP: protocol 113 LevelEvent (0x1a)
 * and LevelSoundEvent (0x19) at tag 1.7dev-27, and the 0.14 (protocol 45) LevelEvent (0xa2, big-endian)
 * at the 0.14 tree — plus sanity over the canonical mapping tables.
 */
class PeEffectsEncodingTest {

    @Test
    void levelEvent113BodyMatchesPmmp() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeLevelEvent(b, 1000, 1.5, 64.0, -2.5, 1000); // a click at pitch 1

        assertEquals(0x1a, ByteBufUtils.readVarInt(b), "packet id LEVEL_EVENT");
        assertEquals(1000, ByteBufUtils.readSignedVarInt(b), "event id (signed varint)");
        assertEquals(1.5f, b.readFloatLE(), "x (LE float)");
        assertEquals(64.0f, b.readFloatLE(), "y");
        assertEquals(-2.5f, b.readFloatLE(), "z");
        assertEquals(1000, ByteBufUtils.readSignedVarInt(b), "data = pitch×1000");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void levelSoundEvent113BodyMatchesPmmp() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeLevelSoundEvent(b, 55, 0.5, 65.0, 0.5); // SOUND_LEVELUP

        assertEquals(0x19, ByteBufUtils.readVarInt(b), "packet id LEVEL_SOUND_EVENT");
        assertEquals(55, b.readUnsignedByte(), "sound id (byte)");
        assertEquals(0.5f, b.readFloatLE(), "x");
        assertEquals(65.0f, b.readFloatLE(), "y");
        assertEquals(0.5f, b.readFloatLE(), "z");
        assertEquals(-1, ByteBufUtils.readSignedVarInt(b), "extraData = -1 (none)");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "pitch = 1 (normal)");
        assertEquals(0, b.readByte(), "isBabyMob = false");
        assertEquals(0, b.readByte(), "disableRelativeVolume = false");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void levelEvent014BodyIsBigEndian() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.levelEvent(b, 0x4000 | 15, 3.0, 70.0, -3.0, 0); // a heart particle

        assertEquals(0xa2, b.readUnsignedByte(), "packet id LEVEL_EVENT (0.14)");
        assertEquals(0x4000 | 15, b.readUnsignedShort(), "event id (BE short)");
        assertEquals(3.0f, b.readFloat(), "x (BE float)");
        assertEquals(70.0f, b.readFloat(), "y");
        assertEquals(-3.0f, b.readFloat(), "z");
        assertEquals(0, b.readInt(), "data (BE int)");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    @Test
    void everySoundMapsOnBothPeEras() {
        for (Sound s : Sound.values()) {
            int evid113 = PeEffects.levelEventSound113(s);
            if (evid113 < 0) {
                assertTrue(PeEffects.levelSound113(s) >= 0, s + " has a LevelSoundEvent id at 113");
            } else {
                assertTrue(evid113 >= 1000 && evid113 < 2000, s + " is a 1000-series sound at 113");
            }
            int evid014 = PeEffects.levelEventSound014(s);
            assertTrue(evid014 >= 1000 && evid014 <= 1022, s + " fits the 0.14 palette (1000..1022)");
        }
    }

    @Test
    void everyParticleMapsOnBothPeEras() {
        for (Particle p : Particle.values()) {
            assertTrue(PeEffects.particle113(p) >= 1, p + " has a 113 type id");
            int t014 = PeEffects.particle014(p);
            assertTrue(t014 >= 1 && t014 <= 32, p + " fits the 0.14 particle table (1..32)");
        }
    }
}
