package com.jedrock.network;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.network.je.packet.*;
import com.jedrock.network.protocol.ConnectionProtocol;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
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
 */
public class JedrockConnection implements Connection, PlayerConnection {

    private static final JLogger LOGGER = JLogger.getLogger(JedrockConnection.class);

    private final Channel channel;
    private final ProtocolVersion protocol;
    private final ConnectionProtocol connectionProtocol;
    private final ConnectionListener listener;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile boolean loggedIn = false;

    // Very basic keep-alive tracking (better to move to core later)
    private long lastKeepAliveSent = 0;

    public JedrockConnection(Channel channel, ProtocolVersion protocol, ConnectionListener listener) {
        this.channel = channel;
        this.protocol = protocol;
        this.listener = listener;
        this.connectionProtocol = new ConnectionProtocol(protocol);
        // New connections always begin in HANDSHAKE for standard flow
        this.connectionProtocol.setState(ProtocolState.HANDSHAKE);
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

    /** Send Login Success (switches the client to PLAY). */
    public void sendLoginSuccess(UUID uuid, String username) {
        send(new ClientboundLoginSuccess(uuid, username));
    }

    public void sendKeepAlive(long id) {
        send(new ClientboundKeepAlive(id));
    }

    /** Send a simple system chat message. */
    public void sendChat(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        send(new ClientboundChatMessage("{\"text\":\"" + escaped + "\"}"));
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

        int id = packet.getPacketId();
        ProtocolState state = getState();
        ByteBuf payload = packet.getPayload(); // read-only view, no ownership transfer

        try {
            LOGGER.debug(() -> "Inbound 0x" + Integer.toHexString(id) +
                    " (state=" + state + ", bytes=" + (payload != null ? payload.readableBytes() : 0) + ")");

            if (protocol.isJava()) {
                handleJavaPacket(id, state, packet, payload);
            } else {
                // PE / other — not implemented yet
                LOGGER.warn("Received packet for unsupported protocol: " + protocol);
            }
        } finally {
            packet.release();
        }
    }

    private void handleJavaPacket(int id, ProtocolState state, LazyPacket lazy, ByteBuf payload) {
        switch (state) {
            case HANDSHAKE -> {
                if (id == 0x00) {
                    ServerboundHandshake hs = lazy.materialize(ServerboundHandshake::fromBuffer);
                    LOGGER.info("Handshake from " + getRemoteAddress() +
                            " | protocol=" + hs.protocolVersion + " nextState=" + hs.nextState);

                    if (hs.nextState == 2) { // Login
                        setState(ProtocolState.LOGIN);
                    } else if (hs.nextState == 1) {
                        setState(ProtocolState.STATUS);
                        // TODO: proper status ping later
                    }
                }
            }

            case LOGIN -> {
                if (id == 0x00) {
                    ServerboundLoginStart loginStart = lazy.materialize(ServerboundLoginStart::fromBuffer);
                    String name = loginStart.username;

                    LOGGER.info("Player " + name + " is logging in from " + getRemoteAddress());

                    // Offline mode login success (no encryption)
                    UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));

                    // 1. Login Success → client switches internally to PLAY
                    sendLoginSuccess(uuid, name);
                    setState(ProtocolState.PLAY);

                    // 2. Join Game + the packets a vanilla client expects, in order
                    send(new ClientboundJoinGame());
                    send(new ClientboundServerDifficulty());
                    send(new ClientboundSpawnPosition());
                    send(new ClientboundPlayerAbilities());
                    send(new ClientboundHeldItemChange());

                    // 3. Initial position + look (MUST be sent to stop "loading terrain")
                    send(new ClientboundPlayerPositionAndLook());

                    // 4. Keep alive so the client knows the connection is alive
                    sendKeepAlive(System.currentTimeMillis());

                    LOGGER.info("Player " + name + " has joined the game (PLAY state).");

                    // 5. Hand the fully-joined player up to the core state layer.
                    //    Any welcome message / game logic is the core's responsibility.
                    loggedIn = true;
                    if (listener != null) {
                        listener.onLogin(this, uuid, name);
                    }
                }
            }

            case PLAY -> {
                if (id == 0x0B) { // Keep Alive (client response)
                    ServerboundKeepAlive ka = lazy.materialize(ServerboundKeepAlive::fromBuffer);
                    LOGGER.debug(() -> "KeepAlive response: " + ka.keepAliveId);
                } else if (id == 0x00) {
                    // Teleport Confirm (sent after we sent PlayerPositionAndLook)
                    LOGGER.debug("Received Teleport Confirm (ignored for now)");
                }
                // TODO: Client Settings (0x04), Position (0x0C), etc.
            }

            case STATUS -> {
                // TODO: implement server list ping if desired
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get() && channel.isOpen();
    }

    /**
     * Called periodically (e.g. from game loop) to send keep-alives.
     * Very lightweight implementation.
     */
    public void tick(long currentTick) {
        if (!isOpen() || getState() != ProtocolState.PLAY) return;

        long now = System.currentTimeMillis();
        if (now - lastKeepAliveSent > 15000) { // every 15s
            sendKeepAlive(now);
            lastKeepAliveSent = now;
        }
    }
}
