package com.jedrock.network.handler.je;

import com.jedrock.network.JedrockConnection;
import com.jedrock.network.handler.ProtocolHandler;
import com.jedrock.network.je.packet.*;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Handles the full JE 1.12.2 protocol state machine using lazy packets.
 *
 * This class encapsulates all version-specific logic for Java Edition.
 * New versions should get their own *ProtocolHandler (or share via versioned registries).
 */
public final class JavaEditionProtocolHandler implements ProtocolHandler {

    private static final JLogger LOGGER = JLogger.getLogger(JavaEditionProtocolHandler.class);

    @Override
    public void handleInbound(LazyPacket packet, JedrockConnection connection) {
        if (packet == null) return;

        int id = packet.getPacketId();
        ProtocolState state = connection.getState();
        ByteBuf payload = packet.getPayload();

        try {
            LOGGER.debug(() -> "Inbound 0x" + Integer.toHexString(id) +
                    " (state=" + state + ", bytes=" + (payload != null ? payload.readableBytes() : 0) + ")");

            switch (state) {
                case HANDSHAKE -> handleHandshake(id, packet, connection);
                case LOGIN -> handleLogin(id, packet, connection);
                case PLAY -> handlePlay(id, packet, connection);
                case STATUS -> handleStatus(id, packet, connection);
            }
        } finally {
            packet.release();
        }
    }

    private void handleHandshake(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id == ServerboundHandshake.PACKET_ID) {
            ServerboundHandshake hs = lazy.materialize(ServerboundHandshake::fromBuffer);
            LOGGER.info("Handshake from " + connection.getRemoteAddress() +
                    " | protocol=" + hs.protocolVersion + " nextState=" + hs.nextState);

            if (hs.nextState == 2) { // Login
                connection.setState(ProtocolState.LOGIN);
            } else if (hs.nextState == 1) {
                connection.setState(ProtocolState.STATUS);
                // TODO: proper status ping (server list) later
            }
        }
    }

    private void handleLogin(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id == ServerboundLoginStart.PACKET_ID) {
            ServerboundLoginStart loginStart = lazy.materialize(ServerboundLoginStart::fromBuffer);
            String name = loginStart.username;

            LOGGER.info("Player " + name + " is logging in from " + connection.getRemoteAddress());

            // Offline mode login success (no encryption for skeleton)
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));

            // 1. Login Success → client switches internally to PLAY
            connection.sendLoginSuccess(uuid, name);
            connection.setState(ProtocolState.PLAY);

            // 2-5. Send the exact sequence a vanilla 1.12.2 client expects to finish "Downloading terrain"
            sendInitialJoinSequence(connection);

            LOGGER.info("Player " + name + " has joined the game (PLAY state).");

            // 6. Hand over to core (player registry, events, etc.)
            connection.setLoggedIn(true);
            if (connection.getListener() != null) {
                connection.getListener().onLogin(connection, uuid, name);
            }
        }
    }

    private void handlePlay(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id == ServerboundKeepAlive.PACKET_ID) {
            ServerboundKeepAlive ka = lazy.materialize(ServerboundKeepAlive::fromBuffer);
            LOGGER.debug(() -> "KeepAlive response: " + ka.keepAliveId);
        } else if (id == 0x00) {
            // Teleport Confirm (sent after PlayerPositionAndLook)
            LOGGER.debug("Received Teleport Confirm (ignored for now)");
        }
        // TODO: Client Settings (0x04), Position (0x0C), Chat, etc.
    }

    private void handleStatus(int id, LazyPacket lazy, JedrockConnection connection) {
        // TODO: implement server list ping (status + ping) if desired
    }

    /**
     * Sends the mandatory packets right after Login Success so the client actually spawns.
     */
    private void sendInitialJoinSequence(JedrockConnection connection) {
        connection.send(new ClientboundJoinGame());
        connection.send(new ClientboundServerDifficulty());
        connection.send(new ClientboundSpawnPosition());
        connection.send(new ClientboundPlayerAbilities());
        connection.send(new ClientboundHeldItemChange());

        // Terrain around spawn — without chunks the client hangs on "Downloading terrain"
        connection.sendSpawnChunks();

        // Initial position + look. Sent after chunks.
        connection.send(new ClientboundPlayerPositionAndLook());

        // Keep alive so the client knows the connection is alive
        connection.sendKeepAlive(System.currentTimeMillis());
    }

    @Override
    public void tick(long currentTick, JedrockConnection connection) {
        if (!connection.isOpen() || connection.getState() != ProtocolState.PLAY) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - connection.getLastKeepAliveSent() > 15000) {
            connection.sendKeepAlive(now);
            connection.setLastKeepAliveSent(now);
        }
    }

    @Override
    public void onConnectionActive(JedrockConnection connection) {
        // JE connections start in HANDSHAKE
        connection.setState(ProtocolState.HANDSHAKE);
    }
}
