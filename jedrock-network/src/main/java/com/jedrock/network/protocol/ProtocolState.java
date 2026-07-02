package com.jedrock.network.protocol;

/**
 * Connection protocol states.
 * Used to decide which packets are legal at any moment and for lazy dispatching.
 */
public enum ProtocolState {
    HANDSHAKE,
    STATUS,
    LOGIN,
    PLAY;

    // In future we can map states to different PacketRegistries per version
}
