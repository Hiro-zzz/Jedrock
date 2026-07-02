package com.jedrock.network;

import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.SocketAddress;

/**
 * Represents an active network connection (player or query etc).
 * Designed around lazy packet handling.
 */
public interface Connection {

    ProtocolVersion getProtocol();

    SocketAddress getRemoteAddress();

    /**
     * Send raw or encoded data. Prefer higher level senders.
     */
    void send(ByteBuf data);

    /**
     * Enqueue an outbound packet (implementation decides encoding).
     */
    void sendPacket(Object packet);

    /**
     * Close this connection.
     */
    void close();

    /**
     * The underlying Netty channel if available (for advanced use).
     * Most code should not touch this.
     */
    Channel getChannel();

    /**
     * Called by the pipeline when a new inbound packet arrives.
     *
     * The packet is a LazyPacket: only ID + raw payload bytes.
     * You must call packet.release() after processing (usually done inside the implementation).
     *
     * Rule: Do not materialize the packet unless you actually need its fields.
     */
    void handleInboundPacket(LazyPacket packet);

    boolean isOpen();
}
