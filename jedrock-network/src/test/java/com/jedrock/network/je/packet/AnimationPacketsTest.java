package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wire encoding of the JE 1.12.2 animation packets: Animation (0x06) and Entity Metadata (0x3C). */
class AnimationPacketsTest {

    @Test
    void animationWritesEntityAndSwing() {
        ByteBuf buf = Unpooled.buffer();
        new ClientboundAnimation(1000, ClientboundAnimation.SWING_MAIN_ARM).write(buf);

        assertEquals(1000, ByteBufUtils.readVarInt(buf), "entity id");
        assertEquals(0, buf.readUnsignedByte(), "swing main arm");
        assertFalse(buf.isReadable());
        buf.release();
    }

    @Test
    void entityMetadataCarriesCrouchFlagThenTerminator() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(1000, true, false).write(buf);

        assertEquals(1000, ByteBufUtils.readVarInt(buf), "entity id");
        assertEquals(0, buf.readUnsignedByte(), "metadata index 0 (flags)");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "type 0 (byte)");
        assertEquals(0x02, buf.readUnsignedByte(), "crouched flag bit");
        assertEquals(0xFF, buf.readUnsignedByte(), "metadata terminator");
        assertFalse(buf.isReadable());
        buf.release();
    }

    @Test
    void entityMetadataCombinesCrouchAndSprintInOneByte() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(7, true, true).write(buf);

        ByteBufUtils.readVarInt(buf);          // entity id
        buf.readUnsignedByte();                // index
        ByteBufUtils.readVarInt(buf);          // type
        assertEquals(0x02 | 0x08, buf.readUnsignedByte(), "crouch + sprint bits together");
        assertEquals(0xFF, buf.readUnsignedByte());
        buf.release();
    }

    @Test
    void entityMetadataClearsFlagsWhenStanding() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(7, false, false).write(buf);

        ByteBufUtils.readVarInt(buf);          // entity id
        buf.readUnsignedByte();                // index
        ByteBufUtils.readVarInt(buf);          // type
        assertEquals(0x00, buf.readUnsignedByte(), "no flags when standing");
        assertEquals(0xFF, buf.readUnsignedByte());
        buf.release();
    }

    @Test
    void entityActionParsesSneakToggle() {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeVarInt(buf, 42);  // entity id
        ByteBufUtils.writeVarInt(buf, ServerboundEntityAction.START_SNEAKING);
        ByteBufUtils.writeVarInt(buf, 0);   // jump boost

        ServerboundEntityAction a = ServerboundEntityAction.fromBuffer(buf);
        assertEquals(42, a.entityId);
        assertEquals(ServerboundEntityAction.START_SNEAKING, a.actionId);
        buf.release();
    }
}
