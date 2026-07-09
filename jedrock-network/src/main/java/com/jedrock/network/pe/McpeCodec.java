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

    /**
     * Write one network Item slot from a canonical {@code (id << 4) | meta} state. The protocol-113
     * aux field packs {@code meta << 8 | count}, so a variant (wool colour, wood type, …) rides in the
     * high byte and round-trips with {@link #readItemState}. No NBT / can-place / can-destroy.
     */
    static void writeSlot(ByteBuf b, int state, int count) {
        int id = Blocks.idOf(state);
        ByteBufUtils.writeSignedVarInt(b, id);
        if (id == Blocks.AIR) {
            return; // air carries no further fields
        }
        int aux = (Blocks.metaOf(state) << 8) | (count & 0xFF);
        ByteBufUtils.writeSignedVarInt(b, aux);          // meta << 8 | count
        b.writeShortLE(0);                               // NBT length
        ByteBufUtils.writeVarInt(b, 0);                  // can place on: none
        ByteBufUtils.writeVarInt(b, 0);                  // can destroy: none
    }

    /**
     * Read one network Item (protocol 113) and return its canonical state {@code (id << 4) | meta}
     * (0 = air). The Bedrock aux field packs {@code meta << 8 | count}, so the block variant is the
     * high byte. Consumes the whole item so the read position stays aligned: id, aux, optional NBT,
     * and the can-place-on / can-destroy string lists.
     */
    static int readItemState(ByteBuf pk) {
        int id = ByteBufUtils.readSignedVarInt(pk);
        if (id == 0) {
            return Blocks.AIR; // air — no further fields
        }
        int aux = ByteBufUtils.readSignedVarInt(pk); // meta << 8 | count
        int nbtLen = pk.readShortLE() & 0xFFFF;      // NBT length (little-endian)
        if (nbtLen > 0) {
            pk.skipBytes(nbtLen);
        }
        int canPlaceOn = ByteBufUtils.readVarInt(pk);
        if (!PacketGuard.saneCount(canPlaceOn)) {
            throw new IllegalArgumentException("canPlaceOn count out of bounds: " + canPlaceOn);
        }
        for (int i = 0; i < canPlaceOn; i++) ByteBufUtils.readString(pk);
        int canDestroy = ByteBufUtils.readVarInt(pk);
        if (!PacketGuard.saneCount(canDestroy)) {
            throw new IllegalArgumentException("canDestroy count out of bounds: " + canDestroy);
        }
        for (int i = 0; i < canDestroy; i++) ByteBufUtils.readString(pk);
        return Blocks.state(id, aux >> 8);
    }

    /** As {@link #readItemState} but discarding the metadata — for items we only need to skip. */
    static int readItemId(ByteBuf pk) {
        return Blocks.idOf(readItemState(pk));
    }
}
