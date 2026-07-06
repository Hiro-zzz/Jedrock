package com.jedrock.network;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.World;

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

    /** The world clients read blocks from when serializing chunks. Set before {@link #bind}. */
    void setWorld(World world);

    /**
     * Server settings the network layer advertises and honours (server-list MOTD, player cap,
     * view distance). Set before {@link #bind}; defaults to {@link ServerProperties#defaults()}.
     */
    default void setProperties(ServerProperties properties) {}

    void shutdown();

    /**
     * Active connections. This collection should be as cheap as possible.
     */
    Collection<Connection> getConnections();

    boolean isActive();

    /** Optional tick hook for keep-alives etc. */
    default void tick(long currentTick) {}
}
