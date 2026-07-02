package com.jedrock.network.handler;

import com.jedrock.network.JedrockConnection;
import com.jedrock.utils.lazy.LazyPacket;

/**
 * Handler that receives LazyPackets.
 * 
 * This is the recommended extension point.
 * Implementations must respect the lazy rule:
 *   - Only materialize what you need.
 *   - Release is handled by the connection unless you retain.
 */
public interface LazyPacketHandler {

    void handle(JedrockConnection connection, LazyPacket packet);
}
