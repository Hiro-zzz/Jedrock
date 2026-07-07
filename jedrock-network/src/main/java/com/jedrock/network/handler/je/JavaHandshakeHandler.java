package com.jedrock.network.handler.je;

import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.network.JedrockConnection;
import com.jedrock.network.je.packet.ClientboundStatusPong;
import com.jedrock.network.je.packet.ClientboundStatusResponse;
import com.jedrock.network.je.packet.ServerboundHandshake;
import com.jedrock.network.je.packet.ServerboundStatusPing;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;

import java.util.UUID;

/**
 * Version-neutral bootstrap for every JE connection: it owns the two phases whose wire format is
 * stable across all Java Edition versions — the handshake and the server-list ping (STATUS).
 *
 * <p>The handshake carries the client's protocol number. On a login-bound handshake this resolves
 * the matching {@link JavaProtocol} and installs it on the connection (refusing an unsupported
 * version); from the next packet on, the version-specific handler drives login and play. On a
 * status-bound handshake the bootstrap stays installed and answers the ping itself, echoing the
 * client's own protocol number so a multiversion server always shows as compatible.
 *
 * <p>The clientbound {@link JavaProtocol} operations are unreachable here — no player exists during
 * handshake/status — so they fail fast if ever called before a real version is installed.
 */
public final class JavaHandshakeHandler implements JavaProtocol {

    private static final JLogger LOGGER = JLogger.getLogger(JavaHandshakeHandler.class);

    /** Captured from the handshake so the status response can echo the client's own version. */
    private int clientProtocol;
    private String clientVersionName = "Jedrock";

    @Override
    public void onConnectionActive(JedrockConnection connection) {
        connection.setState(ProtocolState.HANDSHAKE);
    }

    @Override
    public void handleInbound(LazyPacket packet, JedrockConnection connection) {
        if (packet == null) return;
        int id = packet.getPacketId();
        ProtocolState state = connection.getState();
        try {
            switch (state) {
                case HANDSHAKE -> handleHandshake(id, packet, connection);
                case STATUS -> handleStatus(id, packet, connection);
                default -> { /* once LOGIN is entered, the installed version handler takes over */ }
            }
        } finally {
            packet.release();
        }
    }

    private void handleHandshake(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id != ServerboundHandshake.PACKET_ID) {
            return;
        }
        ServerboundHandshake hs = lazy.materialize(ServerboundHandshake::fromBuffer);
        this.clientProtocol = hs.protocolVersion;
        ProtocolVersion resolved = ProtocolVersion.fromProtocolNumber(hs.protocolVersion);
        if (resolved != null && resolved.isJava()) {
            this.clientVersionName = resolved.getVersionName();
        }
        LOGGER.info("Handshake from " + connection.getRemoteAddress()
                + " | protocol=" + hs.protocolVersion + " nextState=" + hs.nextState);

        if (hs.nextState == 1) {
            connection.setState(ProtocolState.STATUS);
        } else if (hs.nextState == 2) {
            JavaProtocol handler = JavaProtocols.create(hs.protocolVersion);
            if (handler == null) {
                LOGGER.warn("Refusing unsupported JE protocol " + hs.protocolVersion
                        + " from " + connection.getRemoteAddress());
                connection.close("Unsupported Minecraft version (protocol " + hs.protocolVersion + ")");
                return;
            }
            // Install the version strategy; the next packet (LoginStart) dispatches straight to it.
            connection.installProtocol(handler, resolved);
            connection.setState(ProtocolState.LOGIN);
        }
    }

    private void handleStatus(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id == 0x00) {
            // Request → Response. Echo the client's own protocol number so it renders as compatible.
            int online = connection.getListener() != null ? connection.getListener().getOnlinePlayerCount() : 0;
            connection.send(ClientboundStatusResponse.of(
                    clientVersionName,
                    clientProtocol,
                    connection.getServerProperties().maxPlayers(),
                    online,
                    connection.getServerProperties().motd()));
        } else if (id == ServerboundStatusPing.PACKET_ID) {
            // Ping → Pong: echo the client's opaque long; the client then closes the connection.
            ServerboundStatusPing ping = lazy.materialize(ServerboundStatusPing::fromBuffer);
            connection.send(new ClientboundStatusPong(ping.payload));
        }
    }

    // ===== No player exists during handshake/status — these must never be called here. =====

    private static IllegalStateException noPlayer() {
        return new IllegalStateException("clientbound op called before a version handler was installed");
    }

    @Override public void sendSystemMessage(JedrockConnection c, String message) { throw noPlayer(); }
    @Override public void addToTab(JedrockConnection c, UUID uuid, String name) { throw noPlayer(); }
    @Override public void removeFromTab(JedrockConnection c, UUID uuid) { throw noPlayer(); }
    @Override public void showPlayer(JedrockConnection c, UUID uuid, String name, long entityId,
                                     double x, double y, double z, float yaw, float pitch) { throw noPlayer(); }
    @Override public void hidePlayer(JedrockConnection c, UUID uuid, long entityId) { throw noPlayer(); }
    @Override public void moveAvatar(JedrockConnection c, long entityId,
                                     double x, double y, double z, float yaw, float pitch) { throw noPlayer(); }
    @Override public void teleportSelf(JedrockConnection c, double x, double y, double z, float yaw, float pitch) { throw noPlayer(); }
    @Override public void swingArm(JedrockConnection c, long entityId) { throw noPlayer(); }
    @Override public void setPose(JedrockConnection c, long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { throw noPlayer(); }
    @Override public void sendBlockChange(JedrockConnection c, int x, int y, int z, int state) { throw noPlayer(); }
    @Override public void streamChunk(JedrockConnection c, int chunkX, int chunkZ) { throw noPlayer(); }
    @Override public void unloadChunk(JedrockConnection c, int chunkX, int chunkZ) { throw noPlayer(); }
}
