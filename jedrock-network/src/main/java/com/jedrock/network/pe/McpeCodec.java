package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * Low-level MCPE wire helpers shared across the PE layer: UUIDs and the network Item format (used
 * both when writing the creative hotbar and when skipping items while decoding inbound transactions).
 * Packet framing into a batch lives in {@code PeSession}.
 */
final class McpeCodec {

    private McpeCodec() {}

    /** MCPE UUIDs travel as two little-endian longs (msb, lsb). */
    static void writeUuid(ByteBuf b, UUID uuid) {
        b.writeLongLE(uuid.getMostSignificantBits());
        b.writeLongLE(uuid.getLeastSignificantBits());
    }

    /** Write one network Item slot (id, aux = meta&lt;&lt;8 | count, no NBT / can-place / can-destroy). */
    static void writeSlot(ByteBuf b, int id, int count) {
        ByteBufUtils.writeSignedVarInt(b, id);
        if (id == Blocks.AIR) {
            return; // air carries no further fields
        }
        ByteBufUtils.writeSignedVarInt(b, count & 0xFF); // meta 0, count in the low byte
        b.writeShortLE(0);                               // NBT length
        ByteBufUtils.writeVarInt(b, 0);                  // can place on: none
        ByteBufUtils.writeVarInt(b, 0);                  // can destroy: none
    }

    /**
     * Read one network Item (protocol 113) and return its id (0 = air). Consumes the whole item so
     * the read position stays aligned: id, aux (meta/count), optional NBT, and the can-place-on /
     * can-destroy string lists.
     */
    static int readItemId(ByteBuf pk) {
        int id = ByteBufUtils.readSignedVarInt(pk);
        if (id == 0) {
            return 0; // air — no further fields
        }
        ByteBufUtils.readSignedVarInt(pk);        // aux value (meta << 8 | count)
        int nbtLen = pk.readShortLE() & 0xFFFF;   // NBT length (little-endian)
        if (nbtLen > 0) {
            pk.skipBytes(nbtLen);
        }
        int canPlaceOn = ByteBufUtils.readVarInt(pk);
        for (int i = 0; i < canPlaceOn; i++) ByteBufUtils.readString(pk);
        int canDestroy = ByteBufUtils.readVarInt(pk);
        for (int i = 0; i < canDestroy; i++) ByteBufUtils.readString(pk);
        return id;
    }
}
