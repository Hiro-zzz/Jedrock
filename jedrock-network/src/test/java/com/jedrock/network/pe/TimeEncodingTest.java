package com.jedrock.network.pe;

import com.jedrock.network.pe.v014.Mcpe014Packets;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Telling a Bedrock client what time it is — on two wires that disagree about how long a day is.
 *
 * <p>Ground truth is PocketMine-MP: protocol 113 (tag {@code 1.7dev-27}) sends {@code SetTime} as
 * {@code 0x0a} with a single {@code putVarInt}, which is the zigzag form; protocol 45 sends {@code 0x94}
 * as a big-endian int plus a started flag, and its day is <b>19200</b> ticks rather than the 24000 every
 * other target here uses. That rescaling is the whole reason this file exists — the same o'clock is a
 * different number on the two eras, and getting it wrong puts the sun in the wrong place rather than
 * failing loudly.
 */
class TimeEncodingTest {

    @Test
    void bedrock113SendsOneSignedVarInt() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.setTime(b, 6000);

        assertEquals(McpeProtocol.ID_SET_TIME, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(6000, ByteBufUtils.readSignedVarInt(b), "time — PMMP's putVarInt is the zigzag one");
        assertEquals(0, b.readableBytes(), "and nothing else: this wire has no freeze flag");
        b.release();
    }

    @Test
    void bedrock014RescalesToItsShorterDay() {
        // Noon: half way through the day either way, but the numbers differ because the days do.
        assertEquals(9600, scaled(12000), "half of 19200, not half of 24000");
        assertEquals(0, scaled(0));
        assertEquals(4800, scaled(6000), "a quarter of the way round");
        assertNotEquals(12000, scaled(12000), "sending the canonical number would be an hour or six out");
    }

    @Test
    void bedrock014CarriesTheFreezeFlagThisEraActuallyHas() {
        ByteBuf running = Unpooled.buffer();
        ByteBuf frozen = Unpooled.buffer();
        Mcpe014Packets.setTime(running, 9600, true);
        Mcpe014Packets.setTime(frozen, 9600, false);

        assertEquals(0x94, running.readUnsignedByte(), "packet id");
        assertEquals(9600, running.readInt(), "big-endian int — this era predates VarInts entirely");
        assertEquals(1, running.readUnsignedByte(), "started");

        frozen.readUnsignedByte();
        frozen.readInt();
        assertEquals(0, frozen.readUnsignedByte(), "…which 1.1.5 has no equivalent of");
        running.release();
        frozen.release();
    }

    /** The conversion {@code PeSession014} applies on the way out, kept here so the arithmetic is pinned. */
    private static int scaled(long canonicalTicks) {
        return (int) (Math.floorMod(canonicalTicks, 24000L) * 19200L / 24000L);
    }
}
