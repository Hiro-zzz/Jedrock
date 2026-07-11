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

    /** Reads a pose packet into [flags, handStates] after asserting the framing. */
    private static int[] readPose(ByteBuf buf, int expectedEntityId) {
        assertEquals(expectedEntityId, ByteBufUtils.readVarInt(buf), "entity id");
        assertEquals(0, buf.readUnsignedByte(), "metadata index 0 (flags)");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "type 0 (byte)");
        int flags = buf.readUnsignedByte();
        assertEquals(6, buf.readUnsignedByte(), "metadata index 6 (hand states)");
        assertEquals(0, ByteBufUtils.readVarInt(buf), "type 0 (byte)");
        int handStates = buf.readUnsignedByte();
        assertEquals(0xFF, buf.readUnsignedByte(), "metadata terminator");
        assertFalse(buf.isReadable(), "no trailing bytes");
        return new int[]{flags, handStates};
    }

    @Test
    void poseCarriesCrouchFlag() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(1000, true, false, false).write(buf);
        int[] pose = readPose(buf, 1000);
        assertEquals(0x02, pose[0], "crouched flag");
        assertEquals(0x00, pose[1], "not using an item");
        buf.release();
    }

    @Test
    void poseCombinesCrouchSprintAndItemUse() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(7, true, true, true).write(buf);
        int[] pose = readPose(buf, 7);
        assertEquals(0x02 | 0x08, pose[0], "crouch + sprint bits");
        assertEquals(0x01, pose[1], "hand active (using item)");
        buf.release();
    }

    @Test
    void poseClearsEverythingWhenIdle() {
        ByteBuf buf = Unpooled.buffer();
        ClientboundEntityMetadata.pose(7, false, false, false).write(buf);
        int[] pose = readPose(buf, 7);
        assertEquals(0x00, pose[0], "no flags");
        assertEquals(0x00, pose[1], "no hand state");
        buf.release();
    }

    @Test
    void entityStatusWritesPlainIntIdAndHurtStatus() {
        ByteBuf buf = Unpooled.buffer();
        new ClientboundEntityStatus(1000, ClientboundEntityStatus.STATUS_HURT).write(buf);

        assertEquals(1000, buf.readInt(), "entity id (plain int32, not a VarInt)");
        assertEquals(2, buf.readUnsignedByte(), "status 2 = living entity hurt");
        assertFalse(buf.isReadable(), "no trailing bytes");
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
