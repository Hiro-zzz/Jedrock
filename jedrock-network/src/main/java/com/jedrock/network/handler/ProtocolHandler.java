package com.jedrock.network.handler;

import com.jedrock.network.JedrockConnection;
import com.jedrock.utils.lazy.LazyPacket;

/**
 * Strategy for handling inbound traffic for one protocol family (JE or Bedrock) and its states.
 *
 * This is the key abstraction for scaling Jedrock to many versions.
 *
 * Responsibilities of a ProtocolHandler:
 *  - Parse (materialize) only what is needed from LazyPacket
 *  - Perform protocol state transitions
 *  - Send immediate responses (login sequence, keepalive, etc.)
 *  - Notify the ConnectionListener when a player session is ready
 *  - Keep per-connection protocol-specific transient state (if any)
 *
 * JedrockConnection stays protocol-agnostic and thin.
 */
public interface ProtocolHandler {

    /**
     * Handle a freshly received inbound packet (still lazy).
     *
     * The handler MUST call packet.release() (directly or via the connection) when done.
     */
    void handleInbound(LazyPacket packet, JedrockConnection connection);

    /**
     * Periodic per-tick work (keep-alives, timeouts, etc.).
     * Called from the game loop via the owning connection.
     */
    default void tick(long currentTick, JedrockConnection connection) {
        // no-op by default
    }

    /**
     * Called once after the JedrockConnection is fully constructed and registered.
     */
    default void onConnectionActive(JedrockConnection connection) {
        // no-op by default
    }
}
