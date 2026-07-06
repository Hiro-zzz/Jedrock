package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_ANIMATE;
import static com.jedrock.network.pe.McpeProtocol.ID_SET_ENTITY_DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Byte-level checks for the reverse-engineered PE animation encodings (protocol 113): Animate (arm
 * swing) and SetEntityData (sneak pose via the DATA_FLAGS long).
 */
class PeAnimationEncodingTest {

    @Test
    void animateBodyMatchesProtocol113() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeAnimate(b, McpeProtocol.ANIMATE_SWING_ARM, 1000L);

        assertEquals(ID_ANIMATE, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(McpeProtocol.ANIMATE_SWING_ARM, ByteBufUtils.readSignedVarInt(b), "action (putVarInt)");
        assertEquals(1000L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertFalse(b.isReadable(), "no trailing float for swing");
        b.release();
    }

    @Test
    void setEntityDataSneakingSetsFlagBitOne() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeSetEntityDataFlags(b, 1000L, true, false);

        assertEquals(ID_SET_ENTITY_DATA, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(1000L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(1, ByteBufUtils.readVarInt(b), "metadata entry count");
        assertEquals(0, ByteBufUtils.readVarInt(b), "key = DATA_FLAGS");
        assertEquals(7, ByteBufUtils.readVarInt(b), "type = LONG");
        assertEquals(1L << 1, readSignedVarLong(b), "sneaking = bit 1");
        b.release();
    }

    @Test
    void setEntityDataCombinesSneakAndSprintBits() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeSetEntityDataFlags(b, 9L, true, true);

        ByteBufUtils.readVarInt(b);   // id
        ByteBufUtils.readVarLong(b);  // runtime id
        ByteBufUtils.readVarInt(b);   // count
        ByteBufUtils.readVarInt(b);   // key
        ByteBufUtils.readVarInt(b);   // type
        assertEquals((1L << 1) | (1L << 3), readSignedVarLong(b), "sneak bit 1 + sprint bit 3");
        b.release();
    }

    @Test
    void setEntityDataClearsFlagsWhenStanding() {
        ByteBuf b = Unpooled.buffer();
        PeSession.writeSetEntityDataFlags(b, 5L, false, false);

        ByteBufUtils.readVarInt(b);   // id
        ByteBufUtils.readVarLong(b);  // runtime id
        ByteBufUtils.readVarInt(b);   // count
        ByteBufUtils.readVarInt(b);   // key
        ByteBufUtils.readVarInt(b);   // type
        assertEquals(0L, readSignedVarLong(b), "no flags when standing");
        b.release();
    }

    // The flags long is written with a zigzag putVarLong; decode it back the same way.
    private static long readSignedVarLong(ByteBuf b) {
        long raw = ByteBufUtils.readVarLong(b);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
