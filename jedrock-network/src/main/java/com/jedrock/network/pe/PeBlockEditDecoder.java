package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import io.netty.buffer.ByteBuf;

import static com.jedrock.network.pe.McpeProtocol.FACE_DX;
import static com.jedrock.network.pe.McpeProtocol.FACE_DY;
import static com.jedrock.network.pe.McpeProtocol.FACE_DZ;

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
    /** A right-click on a block: the clicked block cell plus the placement it implies (may be null). */
    record UseItem(int x, int y, int z, BlockEdit placement) {}

    /**
     * Decode the Win10 1.1.5 UseItem (0x23) into the clicked block cell and the placement it implies.
     * The caller checks the clicked cell first (a chest opens instead of placing), then applies the
     * placement. A paired (0,0,0) packet is a sync echo → null.
     */
    static UseItem decodeUseItemInteraction(ByteBuf pk) {
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
            return new UseItem(bx, by, bz, place(bx, by, bz, face, itemState));
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
}
