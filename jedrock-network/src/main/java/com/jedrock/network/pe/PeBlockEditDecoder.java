package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import io.netty.buffer.ByteBuf;

import static com.jedrock.network.pe.McpeProtocol.FACE_DX;
import static com.jedrock.network.pe.McpeProtocol.FACE_DY;
import static com.jedrock.network.pe.McpeProtocol.FACE_DZ;
import static com.jedrock.network.pe.McpeProtocol.SOURCE_CONTAINER;
import static com.jedrock.network.pe.McpeProtocol.SOURCE_CREATIVE;
import static com.jedrock.network.pe.McpeProtocol.SOURCE_TODO;
import static com.jedrock.network.pe.McpeProtocol.SOURCE_WORLD;
import static com.jedrock.network.pe.McpeProtocol.TRANSACTION_USE_ITEM;
import static com.jedrock.network.pe.McpeProtocol.USE_ITEM_BREAK_BLOCK;
import static com.jedrock.network.pe.McpeProtocol.USE_ITEM_CLICK_BLOCK;

/**
 * Decodes the three ways the Win10 1.1.5 client reports a world edit into a single canonical
 * {@link BlockEdit}. Each decoder consumes only what it needs and returns {@code null} when the
 * packet is not a usable edit (a sync echo, a non-world transaction, a malformed read, …), so the
 * fiddly reverse-engineered byte parsing is isolated here and stays independently testable.
 */
final class PeBlockEditDecoder {

    private static final JLogger LOGGER = JLogger.getLogger(PeBlockEditDecoder.class);

    private PeBlockEditDecoder() {}

    /** A decoded world edit: the target cell and the new canonical state id&lt;&lt;4|meta (AIR = break). */
    record BlockEdit(int x, int y, int z, int state) {}

    /**
     * Decode the Win10 1.1.5 block-placement packet (0x23). Layout, decoded from captured bytes:
     * block position (x svarint, y uvarint, z svarint), clicked face (svarint), hotbar slot
     * (svarint), player + click Vector3f (6 floats) and one flag byte, then the held Item. The new
     * block goes at the clicked block offset by the face. A paired (0,0,0) packet is a sync echo.
     */
    static BlockEdit decodeUseItem(ByteBuf pk) {
        try {
            int bx = ByteBufUtils.readSignedVarInt(pk);
            int by = ByteBufUtils.readVarInt(pk);   // block y is unsigned
            int bz = ByteBufUtils.readSignedVarInt(pk);
            int face = ByteBufUtils.readSignedVarInt(pk);
            ByteBufUtils.readSignedVarInt(pk);         // hotbar slot — unused
            pk.skipBytes(25);                          // player + click Vector3f (24) + 1 flag
            int itemState = McpeCodec.readItemState(pk); // block in hand (id + meta)

            if (bx == 0 && by == 0 && bz == 0) {
                return null;                        // sync echo
            }
            return place(bx, by, bz, face, itemState);
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse UseItem place: " + e);
            return null;
        }
    }

    /** A decoded PlayerAction: the action id and the block it targets (0,0,0 for non-block actions). */
    record PlayerAction(int action, int x, int y, int z) {}

    /**
     * Decode a Bedrock PlayerAction (protocol 113): entity runtime id, action, block position, face.
     * The caller dispatches on the action — in creative a start/continue-break means "remove this
     * block", while start/stop-sneak toggles the crouch pose. This is how the 1.1.5 Win10 client
     * reports both (breaks aren't sent via transaction).
     */
    static PlayerAction decodePlayerAction(ByteBuf pk) {
        try {
            ByteBufUtils.readVarLong(pk);          // entity runtime id (unsigned)
            int action = ByteBufUtils.readSignedVarInt(pk);
            int bx = ByteBufUtils.readSignedVarInt(pk);
            int by = ByteBufUtils.readVarInt(pk);  // block y is unsigned
            int bz = ByteBufUtils.readSignedVarInt(pk);
            ByteBufUtils.readSignedVarInt(pk);     // face — unused
            return new PlayerAction(action, bx, by, bz);
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse PlayerAction: " + e);
            return null;
        }
    }

    /**
     * Decode a Bedrock InventoryTransaction (protocol 113 layout). The packet crams several
     * concepts together: a transaction type, a list of inventory actions (each with two nested
     * items), then the UseItem data (action type, block position, face, held item, positions). We
     * parse the item format exactly to skip the actions and reach the UseItem block coordinates.
     */
    static BlockEdit decodeInventoryTransaction(ByteBuf pk) {
        try {
            int transactionType = ByteBufUtils.readVarInt(pk); // unsigned
            int actionCount = ByteBufUtils.readVarInt(pk);     // unsigned
            for (int i = 0; i < actionCount; i++) {
                if (!skipInventoryAction(pk)) {
                    return null; // unknown source type — can't skip safely, bail before misaligning
                }
            }
            if (transactionType != TRANSACTION_USE_ITEM) {
                return null; // normal/mismatch/use-on-entity/release — not a world edit
            }

            int actionType = ByteBufUtils.readVarInt(pk);   // unsigned
            int bx = ByteBufUtils.readSignedVarInt(pk);
            int by = ByteBufUtils.readVarInt(pk);           // block y is an unsigned varint
            int bz = ByteBufUtils.readSignedVarInt(pk);
            int face = ByteBufUtils.readSignedVarInt(pk);
            ByteBufUtils.readSignedVarInt(pk);              // hotbar slot — unused
            int itemState = McpeCodec.readItemState(pk);    // block in hand (id + meta) for placement
            // Remaining player/click Vector3f are not needed.

            if (actionType == USE_ITEM_BREAK_BLOCK) {
                return new BlockEdit(bx, by, bz, Blocks.AIR);
            }
            if (actionType == USE_ITEM_CLICK_BLOCK) {
                return place(bx, by, bz, face, itemState);
            }
            return null;
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[PE] could not parse InventoryTransaction: " + e);
            return null;
        }
    }

    /**
     * Build a placement edit at the clicked block offset by the face, or {@code null} if the held
     * item is not a placeable block.
     */
    private static BlockEdit place(int bx, int by, int bz, int face, int itemState) {
        if (!Blocks.isKnown(Blocks.idOf(itemState)) || itemState == Blocks.AIR) {
            return null;
        }
        int f = (face >= 0 && face < 6) ? face : 1;
        return new BlockEdit(bx + FACE_DX[f], by + FACE_DY[f], bz + FACE_DZ[f], itemState);
    }

    /**
     * Parse (to skip) one NetworkInventoryAction. Returns false if the source type is unknown, in
     * which case the caller must stop — we can no longer track the read position.
     */
    private static boolean skipInventoryAction(ByteBuf pk) {
        int sourceType = ByteBufUtils.readVarInt(pk); // unsigned
        switch (sourceType) {
            case SOURCE_CONTAINER, SOURCE_TODO -> ByteBufUtils.readSignedVarInt(pk); // windowId
            case SOURCE_WORLD -> ByteBufUtils.readVarInt(pk);                        // source flags
            case SOURCE_CREATIVE -> { /* no extra field */ }
            default -> {
                LOGGER.debug(() -> "[PE] unknown inventory action source " + sourceType);
                return false;
            }
        }
        ByteBufUtils.readVarInt(pk); // inventory slot (unsigned)
        McpeCodec.readItemId(pk);    // old item
        McpeCodec.readItemId(pk);    // new item
        return true;
    }
}
