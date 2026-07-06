package com.jedrock.network.handler.je;

import com.jedrock.api.world.Blocks;
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

    // Per-connection creative inventory state (one handler instance per connection, single-threaded
    // inbound), used to know which block a placement should place.
    private int heldSlot = 0;
    private final int[] hotbarState = new int[9]; // canonical states (id<<4|meta); 0 = empty

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
        } else if (id == ServerboundChatMessage.PACKET_ID) {
            ServerboundChatMessage chat = lazy.materialize(ServerboundChatMessage::fromBuffer);
            if (connection.getListener() != null) {
                connection.getListener().onChat(connection, chat.message);
            }
        } else if (id == ServerboundPlayerPosition.PACKET_ID) {
            ServerboundPlayerPosition p = lazy.materialize(ServerboundPlayerPosition::fromBuffer);
            connection.clientMoved(p.x, p.y, p.z, null, null);
        } else if (id == ServerboundPlayerPositionAndLook.PACKET_ID) {
            ServerboundPlayerPositionAndLook p = lazy.materialize(ServerboundPlayerPositionAndLook::fromBuffer);
            connection.clientMoved(p.x, p.y, p.z, p.yaw, p.pitch);
        } else if (id == ServerboundPlayerLook.PACKET_ID) {
            ServerboundPlayerLook p = lazy.materialize(ServerboundPlayerLook::fromBuffer);
            connection.clientMoved(null, null, null, p.yaw, p.pitch);
        } else if (id == ServerboundPlayerDigging.PACKET_ID) {
            ServerboundPlayerDigging dig = lazy.materialize(ServerboundPlayerDigging::fromBuffer);
            if (connection.getListener() != null) {
                if (dig.isBreak()) {
                    connection.getListener().onBlockChange(connection, dig.x, dig.y, dig.z, 0); // 0 = air
                } else if (dig.status == ServerboundPlayerDigging.STATUS_RELEASE_USE) {
                    connection.getListener().onUseItem(connection, false); // finished eating / released
                }
            }
        } else if (id == ServerboundUseItem.PACKET_ID) {
            // Started using the held item (eat / drink / block / draw bow).
            if (connection.getListener() != null) {
                connection.getListener().onUseItem(connection, true);
            }
        } else if (id == ServerboundHeldItemChange.PACKET_ID) {
            ServerboundHeldItemChange h = lazy.materialize(ServerboundHeldItemChange::fromBuffer);
            if (h.slot >= 0 && h.slot < 9) heldSlot = h.slot;
        } else if (id == ServerboundCreativeInventoryAction.PACKET_ID) {
            ServerboundCreativeInventoryAction c = lazy.materialize(ServerboundCreativeInventoryAction::fromBuffer);
            if (c.slot >= 36 && c.slot <= 44) {
                hotbarState[c.slot - 36] = c.itemId <= 0 ? 0 : Blocks.state(c.itemId, c.damage);
            }
        } else if (id == ServerboundPlayerBlockPlacement.PACKET_ID) {
            ServerboundPlayerBlockPlacement place = lazy.materialize(ServerboundPlayerBlockPlacement::fromBuffer);
            int state = hotbarState[heldSlot];
            if (Blocks.isKnown(Blocks.idOf(state)) && state != Blocks.AIR && connection.getListener() != null) {
                connection.getListener().onBlockChange(connection,
                        place.placeX(), place.placeY(), place.placeZ(), state);
            }
        } else if (id == ServerboundEntityAction.PACKET_ID) {
            ServerboundEntityAction a = lazy.materialize(ServerboundEntityAction::fromBuffer);
            if (connection.getListener() != null) {
                switch (a.actionId) {
                    case ServerboundEntityAction.START_SNEAKING -> connection.getListener().onSneak(connection, true);
                    case ServerboundEntityAction.STOP_SNEAKING -> connection.getListener().onSneak(connection, false);
                    case ServerboundEntityAction.START_SPRINTING -> connection.getListener().onSprint(connection, true);
                    case ServerboundEntityAction.STOP_SPRINTING -> connection.getListener().onSprint(connection, false);
                    default -> { /* leave bed, horse jump, elytra — not relayed */ }
                }
            }
        } else if (id == ServerboundAnimation.PACKET_ID) {
            // Arm swing (the hand field is irrelevant to the relayed main-arm swing).
            if (connection.getListener() != null) {
                connection.getListener().onSwingArm(connection);
            }
        } else if (id == 0x00) {
            // Teleport Confirm (sent after PlayerPositionAndLook)
            LOGGER.debug("Received Teleport Confirm (ignored for now)");
        }
        // TODO: Client Settings (0x04), Player flying flag (0x0C), etc.
    }

    private void handleStatus(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id == 0x00) {
            // Request → Response: version, player counts and MOTD as JSON. The client renders this
            // in the multiplayer list, then usually follows with a Ping to measure latency.
            int online = connection.getListener() != null ? connection.getListener().getOnlinePlayerCount() : 0;
            connection.send(ClientboundStatusResponse.of(
                    connection.getProtocolVersion().getVersionName(),
                    connection.getProtocolVersion().getProtocolNumber(),
                    connection.getServerProperties().maxPlayers(),
                    online,
                    connection.getServerProperties().motd()));
        } else if (id == ServerboundStatusPing.PACKET_ID) {
            // Ping → Pong: echo the client's opaque long; the client then closes the connection.
            ServerboundStatusPing ping = lazy.materialize(ServerboundStatusPing::fromBuffer);
            connection.send(new ClientboundStatusPong(ping.payload));
        }
    }

    /**
     * Sends the mandatory packets right after Login Success so the client actually spawns.
     */
    private void sendInitialJoinSequence(JedrockConnection connection) {
        connection.send(new ClientboundJoinGame(connection.getServerProperties().maxPlayers()));
        connection.send(new ClientboundServerDifficulty());
        connection.sendSpawnPosition();
        connection.send(new ClientboundPlayerAbilities());
        connection.send(new ClientboundHeldItemChange());

        // Terrain around spawn — without chunks the client hangs on "Downloading terrain"
        connection.sendSpawnChunks();

        // Initial position + look on top of the generated ground. Sent after chunks.
        connection.sendSpawnPositionAndLook();

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
