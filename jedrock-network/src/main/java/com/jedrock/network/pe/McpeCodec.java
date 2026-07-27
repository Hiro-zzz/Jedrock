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
        writeSlot(b, state, count, null);
    }

    /** As above, with a custom item's name and lore in the NBT field ({@code null} = an ordinary item). */
    static void writeSlot(ByteBuf b, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        int id = Blocks.idOf(state);
        ByteBufUtils.writeSignedVarInt(b, id);
        if (id == Blocks.AIR) {
            return; // air carries no further fields
        }
        int aux = (Blocks.metaOf(state) << 8) | (count & 0xFF);
        ByteBufUtils.writeSignedVarInt(b, aux);          // meta << 8 | count
        McpeItemNbt.writeSlotNbt(b, display);            // length-prefixed; 0 for a plain item
        ByteBufUtils.writeVarInt(b, 0);                  // can place on: none
        ByteBufUtils.writeVarInt(b, 0);                  // can destroy: none
    }

    /** A decoded network Item: canonical {@code (id << 4) | meta} state (0 = air) and its stack count. */
    record Item(int state, int count) {}

    /**
     * Read one network Item (protocol 113): id, aux ({@code meta << 8 | count}), optional NBT, and the
     * can-place-on / can-destroy string lists. Consumes the whole item so the read stays aligned.
     */
    static Item readItem(ByteBuf pk) {
        int id = ByteBufUtils.readSignedVarInt(pk);
        if (id == 0) {
            return new Item(Blocks.AIR, 0); // air — no further fields
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
        return new Item(Blocks.state(id, aux >> 8), aux & 0xFF);
    }

    /** As {@link #readItem} but returning only the canonical state (0 = air). */
    static int readItemState(ByteBuf pk) {
        return readItem(pk).state();
    }

    /**
     * Write a chest block-entity compound as protocol-113 <em>network</em> NBT (unsigned-varint string
     * lengths, zigzag-varint ints): the minimal {@code {id:"Chest", x, y, z}} a 1.1.5 client needs to
     * bind a chest GUI to the block. Without the tile the client CRASHES on opening the chest.
     */
    static void writeChestTile(ByteBuf b, int x, int y, int z) {
        b.writeByte(0x0A);                 // TAG_Compound (root)
        writeNbtString(b, "");             // root name (empty)
        b.writeByte(0x08);                 // TAG_String
        writeNbtString(b, "id");
        writeNbtString(b, "Chest");
        writeNbtInt(b, "x", x);
        writeNbtInt(b, "y", y);
        writeNbtInt(b, "z", z);
        b.writeByte(0x00);                 // TAG_End (closes the root compound)
    }

    private static void writeNbtString(ByteBuf b, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBufUtils.writeVarInt(b, bytes.length); // network NBT: unsigned-varint length
        b.writeBytes(bytes);
    }

    private static void writeNbtInt(ByteBuf b, String name, int value) {
        b.writeByte(0x03);                 // TAG_Int
        writeNbtString(b, name);
        ByteBufUtils.writeSignedVarInt(b, value);  // network NBT: zigzag varint
    }
}
