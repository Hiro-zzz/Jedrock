package com.jedrock.network;

import com.jedrock.api.protocol.ProtocolVersion;

import java.net.SocketAddress;
import java.util.Collection;

/**
 * Abstraction over the network listener(s).
 * A single NetworkServer can bind multiple addresses / protocols.
 */
public interface NetworkServer {

    void bind(SocketAddress address, ProtocolVersion protocol) throws Exception;

    /**
     * Register the listener notified about connection lifecycle (login / disconnect).
     * Should be set before {@link #bind} so no early events are missed.
     */
    void setConnectionListener(ConnectionListener listener);

    void shutdown();

    /**
     * Active connections. This collection should be as cheap as possible.
     */
    Collection<Connection> getConnections();

    boolean isActive();

    /** Optional tick hook for keep-alives etc. */
    default void tick(long currentTick) {}
}
