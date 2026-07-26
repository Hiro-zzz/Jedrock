package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static com.jedrock.network.pe.McpeProtocol.ID_ANIMATE;
import static com.jedrock.network.pe.McpeProtocol.ID_ENTITY_EVENT;
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
        McpePackets.animate(b, McpeProtocol.ANIMATE_SWING_ARM, 1000L);

        assertEquals(ID_ANIMATE, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(McpeProtocol.ANIMATE_SWING_ARM, ByteBufUtils.readSignedVarInt(b), "action (putVarInt)");
        assertEquals(1000L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertFalse(b.isReadable(), "no trailing float for swing");
        b.release();
    }

    @Test
    void setEntityDataSneakingSetsFlagBitOne() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.setEntityPose(b, 1000L, true, false, false);

        assertEquals(ID_SET_ENTITY_DATA, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(1000L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(1, ByteBufUtils.readVarInt(b), "metadata entry count");
        assertEquals(0, ByteBufUtils.readVarInt(b), "key = DATA_FLAGS");
        assertEquals(7, ByteBufUtils.readVarInt(b), "type = LONG");
        assertEquals(McpeProtocol.BASE_ENTITY_FLAGS | (1L << 1), readSignedVarLong(b),
                "nametag flags + sneaking bit 1");
        b.release();
    }

    @Test
    void setEntityDataCombinesSneakSprintAndItemUse() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.setEntityPose(b, 9L, true, true, true);

        ByteBufUtils.readVarInt(b);   // id
        ByteBufUtils.readVarLong(b);  // runtime id
        ByteBufUtils.readVarInt(b);   // count
        ByteBufUtils.readVarInt(b);   // key
        ByteBufUtils.readVarInt(b);   // type
        assertEquals(McpeProtocol.BASE_ENTITY_FLAGS | (1L << 1) | (1L << 3) | (1L << 4),
                readSignedVarLong(b), "nametag + sneak(1) + sprint(3) + action(4)");
        b.release();
    }

    @Test
    void setEntityDataItemUseSetsActionBit() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.setEntityPose(b, 3L, false, false, true);

        ByteBufUtils.readVarInt(b);   // id
        ByteBufUtils.readVarLong(b);  // runtime id
        ByteBufUtils.readVarInt(b);   // count
        ByteBufUtils.readVarInt(b);   // key
        ByteBufUtils.readVarInt(b);   // type
        assertEquals(McpeProtocol.BASE_ENTITY_FLAGS | (1L << 4), readSignedVarLong(b),
                "nametag flags + action bit 4 (using item)");
        b.release();
    }

    @Test
    void setEntityDataKeepsNametagFlagsWhenIdle() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.setEntityPose(b, 5L, false, false, false);

        ByteBufUtils.readVarInt(b);   // id
        ByteBufUtils.readVarLong(b);  // runtime id
        ByteBufUtils.readVarInt(b);   // count
        ByteBufUtils.readVarInt(b);   // key
        ByteBufUtils.readVarInt(b);   // type
        assertEquals(McpeProtocol.BASE_ENTITY_FLAGS, readSignedVarLong(b),
                "only the nametag-visibility flags when idle");
        b.release();
    }

    @Test
    void entityEventHurtBodyMatchesProtocol113() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.entityEvent(b, 1000L, McpeProtocol.ENTITY_EVENT_HURT, 0);

        assertEquals(ID_ENTITY_EVENT, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(1000L, ByteBufUtils.readVarLong(b), "entity runtime id");
        assertEquals(McpeProtocol.ENTITY_EVENT_HURT, b.readUnsignedByte(), "event = hurt (2)");
        assertEquals(0, ByteBufUtils.readSignedVarInt(b), "data field (putVarInt)");
        assertFalse(b.isReadable(), "no trailing bytes");
        b.release();
    }

    // The flags long is written with a zigzag putVarLong; decode it back the same way.
    private static long readSignedVarLong(ByteBuf b) {
        long raw = ByteBufUtils.readVarLong(b);
        return (raw >>> 1) ^ -(raw & 1);
    }
}
