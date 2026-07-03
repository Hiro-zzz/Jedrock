package com.jedrock.network;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.World;
import com.jedrock.network.handler.ProtocolHandler;
import com.jedrock.network.handler.je.JavaEditionProtocolHandler;
import com.jedrock.network.je.packet.ClientboundChatMessage;
import com.jedrock.network.je.packet.ClientboundChunkData;
import com.jedrock.network.je.packet.ClientboundKeepAlive;
import com.jedrock.network.je.packet.ClientboundLoginSuccess;
import com.jedrock.network.je.packet.ClientboundPacket;
import com.jedrock.network.je.packet.ClientboundPlayerListItem;
import com.jedrock.network.protocol.ConnectionProtocol;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full Netty-backed implementation of Connection.
 *
 * Core guarantees:
 * - Inbound packets arrive as LazyPacket (raw ID + raw payload bytes).
 * - Nothing is parsed unless someone explicitly calls materialize(...).
 * - All heavy work (login, chunk, entity metadata...) stays lazy until needed.
 * - Outbound goes through the pipeline (length framing).
 *
 * Protocol-specific logic lives in a {@link ProtocolHandler}.
 * This class is intentionally kept thin and protocol-agnostic.
 */
public class JedrockConnection implements Connection, PlayerConnection {

    private static final JLogger LOGGER = JLogger.getLogger(JedrockConnection.class);

    private final Channel channel;
    private final ProtocolVersion protocol;
    private final ConnectionProtocol connectionProtocol;
    private final ConnectionListener listener;
    private final World world;
    private final ProtocolHandler protocolHandler;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile boolean loggedIn = false;

    // Keep-alive tracking (delegated to handler)
    private volatile long lastKeepAliveSent = 0;

    public JedrockConnection(Channel channel, ProtocolVersion protocol, ConnectionListener listener, World world) {
        this.channel = channel;
        this.protocol = protocol;
        this.listener = listener;
        this.world = world;
        this.connectionProtocol = new ConnectionProtocol(protocol);

        // JedrockConnection is the Java Edition (TCP) connection; Bedrock uses PeRakNetServer.
        this.protocolHandler = new JavaEditionProtocolHandler();
        this.protocolHandler.onConnectionActive(this);
    }

    @Override
    public ProtocolVersion getProtocol() {
        return protocol;
    }

    // ===== PlayerConnection (api) =====

    @Override
    public ProtocolVersion getProtocolVersion() {
        return protocol;
    }

    @Override
    public String getAddress() {
        SocketAddress remote = channel.remoteAddress();
        return remote != null ? remote.toString() : "unknown";
    }

    @Override
    public void sendMessage(String message) {
        sendChat(message);
    }

    @Override
    public void addToTab(UUID uuid, String name) {
        send(ClientboundPlayerListItem.add(uuid, name, 1)); // creative gamemode in the tab
    }

    @Override
    public void removeFromTab(UUID uuid) {
        send(ClientboundPlayerListItem.remove(uuid));
    }

    @Override
    public void close(String reason) {
        // Disconnect packet with a reason is a future improvement; for now just drop the channel.
        LOGGER.debug(() -> "Closing connection " + getAddress() + (reason != null ? " (" + reason + ")" : ""));
        close();
    }

    @Override
    public boolean isActive() {
        return isOpen();
    }

    /**
     * Called by the pipeline when the underlying channel goes inactive.
     * Notifies the listener only if this connection had actually logged in.
     */
    void notifyDisconnected() {
        if (loggedIn && listener != null) {
            listener.onDisconnect(this);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public void send(ByteBuf data) {
        if (!isOpen() || data == null) {
            if (data != null) data.release();
            return;
        }
        // The length encoder in the pipeline will prepend the VarInt length
        channel.writeAndFlush(data);
    }

    @Override
    public void sendPacket(Object packet) {
        // We accept two outbound patterns:
        //   1. A typed ClientboundPacket (recommended)
        //   2. A pre-encoded ByteBuf ([packetId][payload]) for low-level/lazy paths
        if (packet instanceof ClientboundPacket cb) {
            send(cb);
        } else if (packet instanceof ByteBuf buf) {
            send(buf);
        } else {
            LOGGER.debug(() -> "sendPacket called with unknown type: " + (packet == null ? "null" : packet.getClass().getSimpleName()));
        }
    }

    /**
     * Send a typed clientbound packet. Writes [VarInt id][payload]; the pipeline
     * prepends the length. This is the primary way to send packets.
     */
    public void send(ClientboundPacket packet) {
        if (!isOpen()) return;

        ByteBuf buf = channel.alloc().buffer();
        try {
            ByteBufUtils.writeVarInt(buf, packet.getPacketId());
            packet.write(buf);
            LOGGER.debug(() -> "Outgoing packet 0x" + Integer.toHexString(packet.getPacketId()));
            send(buf); // ownership transferred to the pipeline
        } catch (Exception e) {
            buf.release();
            LOGGER.error("Failed to send packet 0x" + Integer.toHexString(packet.getPacketId()), e);
        }
    }

    /** Send Login Success (switches the client to PLAY). Primarily called by protocol handlers. */
    public void sendLoginSuccess(UUID uuid, String username) {
        send(new ClientboundLoginSuccess(uuid, username));
    }

    /** Send keep-alive packet. Primarily called by protocol handlers. */
    public void sendKeepAlive(long id) {
        send(new ClientboundKeepAlive(id));
    }

    /** Send a simple system chat message. */
    public void sendChat(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        send(new ClientboundChatMessage("{\"text\":\"" + escaped + "\"}"));
    }

    /** Radius (in chunks) of the flat terrain sent around spawn so the client can render/spawn. */
    private static final int SPAWN_CHUNK_RADIUS = 5;

    /**
     * Stream a square of flat chunks around spawn.
     * Called by protocol handlers during initial join sequence.
     */
    public void sendSpawnChunks() {
        for (int cx = -SPAWN_CHUNK_RADIUS; cx <= SPAWN_CHUNK_RADIUS; cx++) {
            for (int cz = -SPAWN_CHUNK_RADIUS; cz <= SPAWN_CHUNK_RADIUS; cz++) {
                send(new ClientboundChunkData(world, cx, cz));
            }
        }
    }

    public ProtocolState getState() {
        return connectionProtocol.getState();
    }

    public void setState(ProtocolState newState) {
        connectionProtocol.setState(newState);
    }

    public ConnectionProtocol getConnectionProtocol() {
        return connectionProtocol;
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            channel.close();
        }
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @Override
    public void handleInboundPacket(LazyPacket packet) {
        if (packet == null) return;
        protocolHandler.handleInbound(packet, this);
    }

    /**
     * Protocol handlers call this to mark that the player has fully logged in.
     * Used so that notifyDisconnected only fires real player quits.
     */
    public void setLoggedIn(boolean value) {
        this.loggedIn = value;
    }

    /**
     * Accessor for protocol handlers (and core via listener).
     */
    public ConnectionListener getListener() {
        return listener;
    }

    public long getLastKeepAliveSent() {
        return lastKeepAliveSent;
    }

    public void setLastKeepAliveSent(long time) {
        this.lastKeepAliveSent = time;
    }

    @Override
    public boolean isOpen() {
        return open.get() && channel.isOpen();
    }

    /**
     * Called periodically (e.g. from game loop).
     * Delegates timing-sensitive work to the current ProtocolHandler.
     */
    public void tick(long currentTick) {
        protocolHandler.tick(currentTick, this);
    }
}
