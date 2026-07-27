package com.jedrock.network;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.network.handler.je.JavaHandshakeHandler;
import com.jedrock.network.handler.je.JavaProtocol;
import com.jedrock.network.je.packet.ClientboundPacket;
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
 * Netty-backed Java Edition connection — version-neutral.
 *
 * <p>This class owns only what does not change between JE versions: the channel and outbound
 * framing, the movement-merge bookkeeping, chunk streaming plumbing, keep-alive timing and the
 * lifecycle. Everything whose bytes differ per version — the inbound state machine and every
 * clientbound encode the core drives through {@link PlayerConnection} — is delegated to the
 * {@link JavaProtocol} installed for the client that connected.
 *
 * <p>A connection starts on the {@link JavaHandshakeHandler} bootstrap, which reads the handshake,
 * learns the client's protocol number and installs the matching {@link JavaProtocol} (or refuses an
 * unsupported version). Framing is identical across JE versions (VarInt length + VarInt id +
 * payload), so the transport below never needs to know which version it is carrying.
 */
public class JedrockConnection implements Connection, PlayerConnection {

    private static final JLogger LOGGER = JLogger.getLogger(JedrockConnection.class);

    private final Channel channel;
    private final ConnectionListener listener;
    private final World world;
    private final ServerProperties properties;
    private final ConnectionProtocol connectionProtocol;

    /** Resolved from the client's handshake; provisional until then. */
    private volatile ProtocolVersion protocol;
    /** The version strategy handling this connection; swapped from the bootstrap at handshake. */
    private volatile JavaProtocol protocolHandler;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile boolean loggedIn = false;

    // Keep-alive tracking (driven by the protocol handler's tick()).
    private volatile long lastKeepAliveSent = 0;

    // Last client-reported position/look. JE splits movement into three packets
    // (position / look / both), so we merge them here before notifying the core.
    // Initialized to the world spawn the join sequence teleports the client to.
    private volatile double lastX, lastY, lastZ;
    private volatile float lastYaw = 0f, lastPitch = 0f;

    public JedrockConnection(Channel channel, ProtocolVersion protocol, ConnectionListener listener,
                             World world, ServerProperties properties) {
        this.channel = channel;
        this.protocol = protocol; // provisional; the handshake sets the real client version
        this.listener = listener;
        this.world = world;
        this.properties = properties;
        this.connectionProtocol = new ConnectionProtocol(protocol);
        this.chunkView = new ChunkView(properties.viewDistance());

        // Seed the movement-merge state with the world spawn we'll teleport the client to.
        Location spawn = world.getSpawnLocation();
        this.lastX = spawn.x();
        this.lastY = spawn.y();
        this.lastZ = spawn.z();

        // Every JE connection starts on the shared handshake/status bootstrap; it installs the
        // version-specific handler once the client announces its protocol.
        this.protocolHandler = new JavaHandshakeHandler();
        this.protocolHandler.onConnectionActive(this);
    }

    @Override
    public ProtocolVersion getProtocol() {
        return protocol;
    }

    /**
     * Swap in the version-specific protocol strategy once the handshake has identified the client,
     * and record the resolved version. Called by {@link JavaHandshakeHandler}.
     */
    public void installProtocol(JavaProtocol handler, ProtocolVersion version) {
        this.protocol = version;
        this.protocolHandler = handler;
    }

    // ===== PlayerConnection (api) — every wire op delegates to the installed version =====

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
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        protocolHandler.sendTitle(this, title, subtitle, fadeIn, stay, fadeOut);
    }

    @Override
    public void sendWeather(com.jedrock.api.world.Weather weather) {
        protocolHandler.sendWeather(this, weather);
    }

    @Override
    public void sendTabComplete(java.util.List<String> matches) {
        protocolHandler.sendTabComplete(this, matches);
    }

    @Override
    public void setSidebar(String title, String[] lines) {
        protocolHandler.setSidebar(this, title, lines);
    }

    @Override
    public void clearSidebar() {
        protocolHandler.clearSidebar(this);
    }

    @Override
    public void setBossBar(String title, float progress, int color) {
        protocolHandler.setBossBar(this, title, progress, color);
    }

    @Override
    public void clearBossBar() {
        protocolHandler.clearBossBar(this);
    }

    @Override
    public void showHeldItem(long entityId, int state) {
        protocolHandler.showHeldItem(this, entityId, state);
    }

    @Override
    public void showArmor(long entityId, int helmet, int chestplate, int leggings, int boots) {
        protocolHandler.showArmor(this, entityId, helmet, chestplate, leggings, boots);
    }

    @Override
    public void spawnItemEntity(long entityId, java.util.UUID uuid, double x, double y, double z, int state) {
        protocolHandler.spawnItemEntity(this, entityId, uuid, x, y, z, state);
    }

    @Override
    public void spawnFallingBlock(long entityId, java.util.UUID uuid, double x, double y, double z, int state) {
        protocolHandler.spawnFallingBlock(this, entityId, uuid, x, y, z, state);
    }

    @Override
    public void playSound(com.jedrock.api.world.Sound sound, double x, double y, double z, float volume, float pitch) {
        protocolHandler.playSound(this, sound, x, y, z, volume, pitch);
    }

    @Override
    public void spawnParticle(com.jedrock.api.world.Particle particle, double x, double y, double z, int count, double spread) {
        protocolHandler.spawnParticle(this, particle, x, y, z, count, spread);
    }

    @Override
    public void sendActionBar(String text) {
        protocolHandler.sendActionBar(this, text);
    }

    @Override
    public void clearTitle() {
        protocolHandler.clearTitle(this);
    }

    @Override
    public void sendMessage(String message) {
        protocolHandler.sendSystemMessage(this, message);
    }

    @Override
    public void addToTab(UUID uuid, String name) {
        protocolHandler.addToTab(this, uuid, name);
    }

    @Override
    public void removeFromTab(UUID uuid) {
        protocolHandler.removeFromTab(this, uuid);
    }

    @Override
    public void showPlayer(UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        protocolHandler.showPlayer(this, uuid, name, entityId, x, y, z, yaw, pitch);
    }

    @Override
    public void hidePlayer(UUID uuid, long entityId) {
        protocolHandler.hidePlayer(this, uuid, entityId);
    }

    @Override
    public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
        protocolHandler.moveAvatar(this, entityId, x, y, z, yaw, pitch);
    }

    @Override
    public void spawnEntity(long entityId, UUID uuid, com.jedrock.api.entity.EntityType type,
                            double x, double y, double z, float yaw, float pitch) {
        protocolHandler.spawnEntity(this, entityId, uuid, type, x, y, z, yaw, pitch);
    }

    @Override
    public void moveEntity(long entityId, double x, double y, double z, float yaw, float pitch) {
        protocolHandler.moveEntity(this, entityId, x, y, z, yaw, pitch);
    }

    @Override
    public void removeEntity(long entityId) {
        protocolHandler.removeEntity(this, entityId);
    }

    @Override
    public void setEntityNameTag(long entityId, String nameTag) {
        protocolHandler.setEntityNameTag(this, entityId, nameTag);
    }

    @Override
    public void setEntityFlags(long entityId, int flags) {
        protocolHandler.setEntityFlags(this, entityId, flags);
    }

    @Override
    public void spawnTextLine(long entityId, UUID uuid, double x, double y, double z, String text) {
        protocolHandler.spawnTextLine(this, entityId, uuid, x, y, z, text);
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        protocolHandler.teleportSelf(this, x, y, z, yaw, pitch);
    }

    @Override
    public void setGameMode(com.jedrock.api.player.GameMode mode) {
        protocolHandler.setGameMode(this, mode);
    }

    @Override
    public void setInventory(int[] states, int[] counts, com.jedrock.api.item.ItemDisplay[] display) {
        protocolHandler.setInventory(this, states, counts, display);
    }

    @Override
    public void setInventory(int[] states, int[] counts) {
        protocolHandler.setInventory(this, states, counts);
    }

    @Override
    public void setHealth(int health) {
        protocolHandler.setHealth(this, health);
    }

    @Override
    public void setInventorySlot(int slot, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        protocolHandler.setInventorySlot(this, slot, state, count, display);
    }

    @Override
    public void setInventorySlot(int slot, int state, int count) {
        protocolHandler.setInventorySlot(this, slot, state, count);
    }

    @Override
    public void swingArm(long entityId) {
        protocolHandler.swingArm(this, entityId);
    }

    @Override
    public void playHurtAnimation(long entityId) {
        protocolHandler.playHurtAnimation(this, entityId);
    }

    @Override
    public void setCursorItem(int state, int count) {
        protocolHandler.setCursorItem(this, state, count);
    }

    @Override
    public void openContainer(int windowId, String title, int slots, int x, int y, int z) {
        protocolHandler.openContainer(this, windowId, title, slots, x, y, z);
    }

    @Override
    public void setWindowItems(int windowId, int[] states, int[] counts, com.jedrock.api.item.ItemDisplay[] display) {
        protocolHandler.setWindowItems(this, windowId, states, counts, display);
    }

    @Override
    public void setWindowItems(int windowId, int[] states, int[] counts) {
        protocolHandler.setWindowItems(this, windowId, states, counts);
    }

    @Override
    public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        protocolHandler.setPose(this, entityId, sneaking, sprinting, usingItem);
    }

    @Override
    public void sendBlockChange(int x, int y, int z, int state) {
        protocolHandler.sendBlockChange(this, x, y, z, state);
    }

    @Override
    public void close(String reason) {
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
        // The length encoder in the pipeline will prepend the VarInt length.
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
     * Send a typed clientbound packet. Writes [VarInt id][payload]; the pipeline prepends the length.
     * The framing is identical across JE versions, so protocol handlers of any version build their
     * own {@link ClientboundPacket} instances and hand them here.
     */
    public void send(ClientboundPacket packet) {
        if (!isOpen()) return;

        ByteBuf buf = channel.alloc().buffer();
        try {
            ByteBufUtils.writeVarInt(buf, packet.getPacketId());
            int idLen = buf.writerIndex();          // where the payload begins, past the VarInt id
            packet.write(buf);
            // Offer the outbound packet to any tap; a cancel drops it before it reaches the pipeline.
            if (listener != null && listener.hasPacketTaps()) {
                byte[] payload = new byte[buf.writerIndex() - idLen];
                buf.getBytes(idLen, payload);
                if (listener.onOutboundPacket(this, packet.getPacketId(), payload)) {
                    buf.release();
                    return;
                }
            }
            // Every clientbound packet passes here (chunks, avatar moves, block changes) — gate the
            // lambda rather than allocate one per send just to throw it away with debug off.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Outgoing packet 0x" + Integer.toHexString(packet.getPacketId()));
            }
            send(buf); // ownership transferred to the pipeline
        } catch (Exception e) {
            buf.release();
            LOGGER.error("Failed to send packet 0x" + Integer.toHexString(packet.getPacketId()), e);
        }
    }

    /** The shared world clients serialize chunks from; used by protocol handlers during the join. */
    public World getWorld() {
        return world;
    }

    /** View distance (in chunks) streamed around the player — from server config. */
    private final ChunkView chunkView;
    private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
        @Override public void load(int cx, int cz) { protocolHandler.streamChunk(JedrockConnection.this, cx, cz); }
        @Override public void unload(int cx, int cz) { protocolHandler.unloadChunk(JedrockConnection.this, cx, cz); }
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
        // Offer the raw packet to any tap first; a cancel drops it before the core ever sees it.
        if (listener != null && listener.hasPacketTaps()) {
            ByteBuf payload = packet.getPayload();
            byte[] bytes;
            if (payload != null) {
                bytes = new byte[payload.readableBytes()];
                payload.getBytes(payload.readerIndex(), bytes); // absolute read — doesn't consume
            } else {
                bytes = EMPTY_PAYLOAD;
            }
            if (listener.onInboundPacket(this, packet.getPacketId(), bytes)) {
                packet.release();
                return;
            }
        }
        protocolHandler.handleInbound(packet, this);
    }

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    @Override
    public void sendRawPacket(int packetId, byte[] payload) {
        if (!isOpen()) return;
        byte[] body = payload != null ? payload : EMPTY_PAYLOAD;
        // Injected packets are tapped like any other send; the registry's re-entrancy guard stops a tap
        // that injects from recursing into itself.
        if (listener != null && listener.hasPacketTaps()
                && listener.onOutboundPacket(this, packetId, body)) {
            return;
        }
        ByteBuf buf = channel.alloc().buffer();
        ByteBufUtils.writeVarInt(buf, packetId); // JE framing: VarInt id + payload
        buf.writeBytes(body);
        send(buf); // low-level: does not re-tap (send(ClientboundPacket) is the tapped path)
    }

    /**
     * Protocol handlers call this to mark that the player has fully logged in.
     * Used so that notifyDisconnected only fires real player quits.
     */
    public void setLoggedIn(boolean value) {
        this.loggedIn = value;
    }

    /** Accessor for protocol handlers (and core via listener). */
    public ConnectionListener getListener() {
        return listener;
    }

    /** Server settings (player cap, view distance, etc.) for the protocol handler. */
    public ServerProperties getServerProperties() {
        return properties;
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

    /** Measured keep-alive round trip, ms; -1 until the first response lands. */
    private volatile int pingMs = -1;

    /**
     * The client answered a keep-alive: the gap since the last one we sent is the round trip. JE
     * clients answer promptly, so the keep-alive cadence (~15 s) doubles as a ping probe.
     */
    public void onKeepAliveResponse() {
        long sent = lastKeepAliveSent;
        if (sent > 0) {
            pingMs = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - sent);
        }
    }

    @Override
    public int getPing() {
        return pingMs;
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
