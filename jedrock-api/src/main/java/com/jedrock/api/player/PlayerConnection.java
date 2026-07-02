package com.jedrock.api.player;

import com.jedrock.api.protocol.ProtocolVersion;

/**
 * Absolute abstraction for a player's network connection.
 * This should never expose concrete packet classes or ByteBufs directly in the API.
 *
 * Implementations can use lazy parsing under the hood.
 */
public interface PlayerConnection {

    /**
     * The protocol this connection is using.
     */
    ProtocolVersion getProtocolVersion();

    /**
     * Remote address representation (abstracted).
     */
    String getAddress();

    /**
     * Send a message / packet abstraction.
     * In practice, higher level modules will use a PacketSender or similar.
     */
    void sendPacket(Object packet); // Object to keep API completely protocol agnostic

    /**
     * Send a system/chat message to the client.
     * Protocol-agnostic on purpose: the implementation decides how to encode it,
     * so higher layers (e.g. the core player) never touch concrete packets.
     */
    void sendMessage(String message);

    /**
     * Close the connection.
     */
    void close(String reason);

    /**
     * @return true if the underlying channel is active.
     */
    boolean isActive();
}
