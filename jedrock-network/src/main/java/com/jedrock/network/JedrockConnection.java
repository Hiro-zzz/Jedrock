package com.jedrock.network;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.network.handler.ProtocolHandler;
import com.jedrock.network.handler.je.JavaEditionProtocolHandler;
import com.jedrock.network.je.packet.ClientboundBlockChange;
import com.jedrock.network.je.packet.ClientboundChatMessage;
import com.jedrock.network.je.packet.ClientboundChunkData;
import com.jedrock.network.je.packet.ClientboundDestroyEntities;
import com.jedrock.network.je.packet.ClientboundEntityHeadLook;
import com.jedrock.network.je.packet.ClientboundEntityTeleport;
import com.jedrock.network.je.packet.ClientboundKeepAlive;
import com.jedrock.network.je.packet.ClientboundLoginSuccess;
import com.jedrock.network.je.packet.ClientboundPacket;
import com.jedrock.network.je.packet.ClientboundPlayerListItem;
import com.jedrock.network.je.packet.ClientboundPlayerPositionAndLook;
import com.jedrock.network.je.packet.ClientboundSpawnPlayer;
import com.jedrock.network.je.packet.ClientboundSpawnPosition;
import com.jedrock.network.je.packet.ClientboundUnloadChunk;
import com.jedrock.network.chunk.ChunkView;
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

    // Last client-reported position/look. JE splits movement into three packets
    // (position / look / both), so we merge them here before notifying the core.
    // Initialized to the world spawn the join sequence teleports the client to.
    private volatile double lastX, lastY, lastZ;
    private volatile float lastYaw = 0f, lastPitch = 0f;

    public JedrockConnection(Channel channel, ProtocolVersion protocol, ConnectionListener listener, World world) {
        this.channel = channel;
        this.protocol = protocol;
        this.listener = listener;
        this.world = world;
        this.connectionProtocol = new ConnectionProtocol(protocol);

        // Seed the movement-merge state with the world spawn we'll teleport the client to.
        Location spawn = world.getSpawnLocation();
        this.lastX = spawn.x();
        this.lastY = spawn.y();
        this.lastZ = spawn.z();

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
    public void showPlayer(UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        // The uuid is already in the tab (core adds it first), which 1.12.2 requires to render.
        send(new ClientboundSpawnPlayer((int) entityId, uuid, x, y, z, yaw, pitch));
    }

    @Override
    public void hidePlayer(UUID uuid, long entityId) {
        send(new ClientboundDestroyEntities((int) entityId));
    }

    @Override
    public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
        send(new ClientboundEntityTeleport((int) entityId, x, y, z, yaw, pitch));
        send(new ClientboundEntityHeadLook((int) entityId, yaw));
    }

    @Override
    public void sendBlockChange(int x, int y, int z, int blockId) {
        send(new ClientboundBlockChange(x, y, z, blockId));
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

    /** Send the world's spawn as the client's compass/respawn point. */
    public void sendSpawnPosition() {
        Location s = world.getSpawnLocation();
        send(new ClientboundSpawnPosition(s.getBlockX(), s.getBlockY(), s.getBlockZ()));
    }

    /** Teleport the client to the world spawn (feet on the generated ground). */
    public void sendSpawnPositionAndLook() {
        Location s = world.getSpawnLocation();
        send(new ClientboundPlayerPositionAndLook(s.x(), s.y(), s.z(), s.yaw(), s.pitch()));
    }

    /** View distance (in chunks) streamed around the player. */
    private static final int VIEW_RADIUS = 6;

    private final ChunkView chunkView = new ChunkView(VIEW_RADIUS);
    private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
        @Override public void load(int cx, int cz) { send(new ClientboundChunkData(world, cx, cz)); }
        @Override public void unload(int cx, int cz) { send(new ClientboundUnloadChunk(cx, cz)); }
    };

    /**
     * Send the initial window of chunks around spawn. Called by the protocol handler during the
     * join sequence; subsequent movement streams new chunks via {@link #clientMoved}.
     */
    public void sendSpawnChunks() {
        Location s = world.getSpawnLocation();
        chunkView.recenter(s.getBlockX() >> 4, s.getBlockZ() >> 4, chunkSink);
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

    /**
     * Merge a client movement update (either half may be absent) into the tracked state
     * and relay the full position to the core. Called by the protocol handler.
     */
    public void clientMoved(Double x, Double y, Double z, Float yaw, Float pitch) {
        if (x != null) {
            lastX = x;
            lastY = y;
            lastZ = z;
        }
        if (yaw != null) {
            lastYaw = yaw;
            lastPitch = pitch;
        }
        if (x != null) {
            // Stream chunks around the new position (no-op unless the player crossed a boundary).
            chunkView.recenter(((int) Math.floor(lastX)) >> 4, ((int) Math.floor(lastZ)) >> 4, chunkSink);
        }
        if (loggedIn && listener != null) {
            listener.onMove(this, lastX, lastY, lastZ, lastYaw, lastPitch);
        }
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
