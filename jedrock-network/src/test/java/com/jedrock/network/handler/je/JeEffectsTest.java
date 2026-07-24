package com.jedrock.network.handler.je;

import com.jedrock.api.world.Particle;
import com.jedrock.api.world.Sound;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JE effect tables: every canonical sound has a name in both eras; particle ids stay in the safe range. */
class JeEffectsTest {

    @Test
    void everySoundHasANameInBothEras() {
        for (Sound s : Sound.values()) {
            assertFalse(JeEffects.soundName1_8(s).isBlank(), s + " has a 1.8 name");
            assertFalse(JeEffects.soundName1_12(s).isBlank(), s + " has a 1.12.2 name");
            // The eras renamed everything — a 1.8 name leaking into 1.12.2 (or vice versa) is silent no-sound.
            assertTrue(JeEffects.soundName1_12(s).contains("."), s + " 1.12.2 name is namespaced-style");
        }
    }

    @Test
    void everyParticleIdIsBelowTheDataCarryingRange() {
        // Ids 36+ (iconcrack/blockcrack/blockdust) append extra varints the body writer doesn't emit —
        // a canonical particle mapping there would corrupt the packet stream.
        for (Particle p : Particle.values()) {
            int id = JeEffects.particleId(p);
            assertTrue(id >= 0 && id < 36, p + " id " + id + " needs no extra data varints");
        }
    }

    @Test
    void particleBodyLayoutMatchesMinecraftData() {
        ByteBuf b = Unpooled.buffer();
        JeEffects.writeParticleBody(b, 34, 1.0, 65.0, -1.0, 8, 0.5); // 8 hearts, ±0.5

        assertEquals(34, b.readInt(), "particle id (i32)");
        assertEquals(0, b.readByte(), "longDistance = false");
        assertEquals(1.0f, b.readFloat(), "x");
        assertEquals(65.0f, b.readFloat(), "y");
        assertEquals(-1.0f, b.readFloat(), "z");
        assertEquals(0.5f, b.readFloat(), "offsetX");
        assertEquals(0.5f, b.readFloat(), "offsetY");
        assertEquals(0.5f, b.readFloat(), "offsetZ");
        assertEquals(0.0f, b.readFloat(), "particleData (speed)");
        assertEquals(8, b.readInt(), "count");
        assertFalse(b.isReadable(), "no trailing bytes (ids < 36 carry no data varints)");
        b.release();
    }
}
