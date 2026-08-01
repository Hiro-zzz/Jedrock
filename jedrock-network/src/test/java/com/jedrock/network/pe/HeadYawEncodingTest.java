package com.jedrock.network.pe;

import com.jedrock.network.je.packet.ClientboundEntityHeadLook;
import com.jedrock.network.pe.v014.Mcpe014Packets;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a puppet is <em>looking</em> versus which way its body is turned, on each wire that carries both.
 *
 * <p>Every one of these packets has had a head-yaw field all along; the server was writing the body yaw
 * into it twice, so nothing could glance and no test noticed, because writing the same number twice looks
 * correct from either end. These assert the two numbers arrive <b>different</b>, which is the only way
 * that class of bug shows up in bytes.
 */
class HeadYawEncodingTest {

    /** The byte-angle a float degree becomes ({@code ByteBufUtils.writeAngle}), truncating as it does. */
    private static byte angle(float degrees) {
        return (byte) (int) (degrees * 256.0f / 360.0f);
    }

    @Test
    void java1_12_2SendsTheHeadAloneInItsOwnPacket() {
        ByteBuf b = Unpooled.buffer();
        new ClientboundEntityHeadLook(1234, 90f).write(b);

        assertEquals(1234, ByteBufUtils.readVarInt(b), "entity id");
        assertEquals(angle(90f), b.readByte(), "the head yaw, as a byte angle");
        assertEquals(0, b.readableBytes(), "and nothing else — a glance costs no position");
        b.release();
    }

    @Test
    void bedrock113MoveEntityCarriesBodyAndHeadSeparately() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.moveEntity(b, 7L, 1.0, 2.0, 3.0, 30f, 10f, 120f);

        ByteBufUtils.readVarInt(b);                        // packet id
        assertEquals(7L, ByteBufUtils.readVarLong(b), "runtime id");
        assertEquals(1.0f, b.readFloatLE(), 1e-6, "x");
        assertEquals(2.0f, b.readFloatLE(), 1e-6, "y (feet)");
        assertEquals(3.0f, b.readFloatLE(), 1e-6, "z");
        assertEquals(angle(10f), b.readByte(), "pitch");
        assertEquals(angle(30f), b.readByte(), "body yaw");
        assertEquals(angle(120f), b.readByte(), "head yaw — the field that used to repeat the body's");
        assertNotEquals(angle(30f), angle(120f), "…and the test would pass on a bug if these matched");
        b.release();
    }

    @Test
    void bedrock113KeepsItsOldBytesWhenNothingHasGlanced() {
        ByteBuf plain = Unpooled.buffer();
        ByteBuf explicit = Unpooled.buffer();
        McpePackets.moveEntity(plain, 7L, 1.0, 2.0, 3.0, 30f, 10f);
        McpePackets.moveEntity(explicit, 7L, 1.0, 2.0, 3.0, 30f, 10f, 30f);

        assertEquals(explicit, plain, "an ordinary move must be byte-identical to what it always was");
        plain.release();
        explicit.release();
    }

    @Test
    void bedrock014MoveEntityCarriesBodyAndHeadSeparately() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.moveEntity(b, 7L, 1.0f, 2.0f, 3.0f, 30f, 10f, 120f);

        b.readByte();                                      // packet id
        assertEquals(1, b.readInt(), "entity count (this era batches moves)");
        assertEquals(7L, b.readLong(), "eid, big-endian");
        assertEquals(1.0f, b.readFloat(), 1e-6, "x");
        assertEquals(2.0f, b.readFloat(), 1e-6, "y");
        assertEquals(3.0f, b.readFloat(), 1e-6, "z");
        assertEquals(30f, b.readFloat(), 1e-4, "body yaw");
        assertEquals(120f, b.readFloat(), 1e-4, "head yaw");
        assertEquals(10f, b.readFloat(), 1e-4, "pitch — last on this era, unlike 113");
        b.release();
    }

    @Test
    void bedrock014KeepsItsOldBytesWhenNothingHasGlanced() {
        ByteBuf plain = Unpooled.buffer();
        ByteBuf explicit = Unpooled.buffer();
        Mcpe014Packets.moveEntity(plain, 7L, 1f, 2f, 3f, 30f, 10f);
        Mcpe014Packets.moveEntity(explicit, 7L, 1f, 2f, 3f, 30f, 10f, 30f);

        assertEquals(explicit, plain);
        plain.release();
        explicit.release();
    }
}
