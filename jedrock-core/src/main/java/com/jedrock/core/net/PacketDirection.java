package com.jedrock.core.net;

/**
 * Which way a tapped packet is travelling: {@link #INBOUND} from the client to the server (before the core
 * handles it) or {@link #OUTBOUND} from the server to the client (before it hits the socket).
 */
public enum PacketDirection {
    INBOUND,
    OUTBOUND
}
