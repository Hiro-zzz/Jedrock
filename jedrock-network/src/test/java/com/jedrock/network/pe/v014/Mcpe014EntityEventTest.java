package com.jedrock.network.pe.v014;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The 0.14 EntityEvent (0xa4) wire format: big-endian eid + event byte, no trailing data (protocol 45). */
class Mcpe014EntityEventTest {

    @Test
    void encodesHurtEvent() {
        ByteBuf b = Unpooled.buffer();
        Mcpe014Packets.entityEvent(b, 1000L, Mcpe014Packets.ENTITY_EVENT_HURT);

        assertEquals(0xa4, b.readUnsignedByte(), "EntityEvent id");
        assertEquals(1000L, b.readLong(), "entity id (big-endian long)");
        assertEquals(2, b.readUnsignedByte(), "event = hurt (2)");
        assertFalse(b.isReadable(), "no trailing data at protocol 45");
        b.release();
    }
}
