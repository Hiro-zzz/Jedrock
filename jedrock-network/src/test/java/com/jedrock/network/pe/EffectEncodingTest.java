package com.jedrock.network.pe;

import com.jedrock.api.entity.Effect;
import com.jedrock.network.pe.v014.Mcpe014Packets;
import com.jedrock.network.pe.v014.Pe014Effects;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a Bedrock client it is under something — on two wires that carry the same six fields in
 * completely different widths.
 *
 * <p>Ground truth is PocketMine-MP's {@code MobEffectPacket}: at protocol 113 (tag {@code 1.7dev-27})
 * it is {@code 0x1d} with a runtime id, an event byte and then <b>signed varints</b>; at protocol 45
 * (tree {@code e11b76318}) it is {@code 0xa5} with a big-endian long and everything after it a single
 * <b>byte</b>, bar the duration. The id sits in the one gap between two ids this project has already
 * confirmed against a real client (EntityEvent {@code 0x1c}, UpdateAttributes {@code 0x1e}), and 0.14's
 * lands between {@code 0xa4} and {@code 0xa6}, which are likewise already known good.
 */
class EffectEncodingTest {

    @Test
    @DisplayName("1.1.5 writes the runtime id, an event byte, then three signed varints")
    void bedrock113() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.mobEffect(b, 1L, McpePackets.EFFECT_EVENT_ADD, Effect.SPEED.getId(), 1, true, 600);

        assertEquals(McpeProtocol.ID_MOB_EFFECT, ByteBufUtils.readVarInt(b), "packet id");
        assertEquals(1L, ByteBufUtils.readVarLong(b), "the player's own runtime id");
        assertEquals(McpePackets.EFFECT_EVENT_ADD, b.readUnsignedByte(), "event: 1 = add");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "speed");
        assertEquals(1, ByteBufUtils.readSignedVarInt(b), "amplifier — 0 would be Speed I");
        assertEquals(1, b.readUnsignedByte(), "particles");
        assertEquals(600, ByteBufUtils.readSignedVarInt(b), "duration in ticks");
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    @DisplayName("1.1.5 removes with the same packet and event 3")
    void bedrock113Remove() {
        ByteBuf b = Unpooled.buffer();
        McpePackets.mobEffect(b, 1L, McpePackets.EFFECT_EVENT_REMOVE, Effect.INVISIBILITY.getId(),
                0, false, 0);

        ByteBufUtils.readVarInt(b);
        ByteBufUtils.readVarLong(b);
        assertEquals(3, b.readUnsignedByte(), "event: 3 = remove");
        assertEquals(Effect.INVISIBILITY.getId(), ByteBufUtils.readSignedVarInt(b));
        b.release();
    }

    @Test
    @DisplayName("0.14 writes the same fields big-endian, and everything but the duration is one byte")
    void bedrock014() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.mobEffect(b, 0L, Mcpe014Packets.EFFECT_EVENT_ADD, Effect.STRENGTH.getId(),
                0, true, 400);

        assertEquals(Mcpe014Packets.ID_MOB_EFFECT, b.readUnsignedByte(), "packet id");
        assertEquals(0L, b.readLong(), "this era knows the player as entity 0, not 1");
        assertEquals(1, b.readUnsignedByte(), "event");
        assertEquals(Effect.STRENGTH.getId(), b.readUnsignedByte(), "a byte here, a varint at 113");
        assertEquals(0, b.readUnsignedByte(), "amplifier");
        assertEquals(1, b.readUnsignedByte(), "particles");
        assertEquals(400, b.readInt(), "duration — the one field still four bytes wide");
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    @DisplayName("0.14 knows sixteen effects, and the rest are never sent to it")
    void the014Gate() {
        assertTrue(Pe014Effects.supports(Effect.SPEED));
        assertTrue(Pe014Effects.supports(Effect.INVISIBILITY));
        assertTrue(Pe014Effects.supports(Effect.WITHER));
        // PMMP leaves these as TODO at protocol 45 — and this is the client that crashes rather than
        // shrugs, so the gate matters more here than the equivalent one on 1.1.5 would.
        assertFalse(Pe014Effects.supports(Effect.NIGHT_VISION));
        assertFalse(Pe014Effects.supports(Effect.BLINDNESS));
        assertFalse(Pe014Effects.supports(Effect.ABSORPTION));
        assertFalse(Pe014Effects.supports(Effect.SATURATION));
        assertFalse(Pe014Effects.supports(Effect.HUNGER));
        // Instant health and damage aren't sent either — but they still land, because the server owns
        // health and applies them itself. Nothing is lost on 0.14 but the swirl.
        assertFalse(Pe014Effects.supports(Effect.INSTANT_HEALTH));
        assertFalse(Pe014Effects.supports(Effect.INSTANT_DAMAGE));
    }

    @Test
    @DisplayName("the legacy ids are the ones every edition shares")
    void canonicalIds() {
        assertEquals(1, Effect.SPEED.getId());
        assertEquals(5, Effect.STRENGTH.getId());
        assertEquals(8, Effect.JUMP_BOOST.getId());
        assertEquals(11, Effect.RESISTANCE.getId());
        assertEquals(14, Effect.INVISIBILITY.getId());
        assertEquals(Effect.SPEED, Effect.fromId(1));
        assertEquals(Effect.JUMP_BOOST, Effect.fromString("jump_boost"));
        assertEquals(Effect.SPEED, Effect.fromString("  SPEED "));
    }
}
