package com.jedrock.network.je.packet;

/**
 * Serverbound Use Item (0x20) for JE 1.12.2 — the client started using the held item (right-click:
 * eat / drink / block with a shield / draw a bow). The only field is the hand, which we don't need:
 * the item-use pose is relayed the same way regardless. Release is reported via Player Digging
 * ({@link ServerboundPlayerDigging#STATUS_RELEASE_USE}).
 */
public final class ServerboundUseItem implements ServerboundPacket {

    public static final int PACKET_ID = 0x20;

    @Override
    public int getPacketId() {
        return PACKET_ID;
    }
}
