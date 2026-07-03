package com.jedrock.network.je.packet;

/**
 * Marker for Java Edition packets sent by the client to the server.
 *
 * For now this is mainly documentation + future extension point.
 * A full registry-based approach can use this interface later:
 *
 * <pre>
 * Map&lt;Integer, Function&lt;ByteBuf, ServerboundPacket&gt;&gt; parsers = ...
 * </pre>
 */
public interface ServerboundPacket {

    /**
     * The packet identifier for the current protocol state.
     * Note: packet IDs can overlap between states (HANDSHAKE vs PLAY).
     */
    int getPacketId();
}
