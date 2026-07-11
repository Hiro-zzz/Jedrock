package com.jedrock.network.handler.je;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.network.JedrockConnection;
import com.jedrock.network.je.packet.*;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import com.jedrock.utils.text.JsonText;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Java Edition 1.12.2 (protocol 340) — login, play and the clientbound wire encoding.
 *
 * <p>The handshake and server-list ping are handled by the shared {@link JavaHandshakeHandler},
 * which installs this handler once it sees a 1.12.2 login handshake. From there this class owns the
 * full LOGIN → PLAY flow and every {@link JavaProtocol} clientbound op in the 1.12.2 packet format.
 */
public final class JavaEditionProtocolHandler implements JavaProtocol {

    private static final JLogger LOGGER = JLogger.getLogger(JavaEditionProtocolHandler.class);

    // Per-connection creative inventory state (one handler instance per connection, single-threaded
    // inbound), used to know which block a placement should place.
    private int heldSlot = 0;
    private final int[] hotbarState = new int[9]; // canonical states (id<<4|meta); 0 = empty

    // ===== Inbound state machine (LOGIN + PLAY) =====

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
                case LOGIN -> handleLogin(id, packet, connection);
                case PLAY -> handlePlay(id, packet, connection);
                default -> { /* handshake/status are owned by JavaHandshakeHandler */ }
            }
        } finally {
            packet.release();
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
            connection.send(new ClientboundLoginSuccess(uuid, name));
            connection.setState(ProtocolState.PLAY);

            // 2-5. Send the exact sequence a vanilla 1.12.2 client expects to finish "Downloading terrain"
            sendInitialJoinSequence(connection, uuid);

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
            var listener = connection.getListener();
            if (listener != null) {
                boolean creative = listener.gameModeOf(connection) == GameMode.CREATIVE;
                // Creative mines instantly (STARTED); survival removes the block only when digging
                // FINISHED — otherwise a single click would break it on the first hit.
                if ((dig.status == ServerboundPlayerDigging.STATUS_STARTED && creative)
                        || dig.status == ServerboundPlayerDigging.STATUS_FINISHED) {
                    listener.onBlockChange(connection, dig.x, dig.y, dig.z, 0); // 0 = air
                } else if (dig.status == ServerboundPlayerDigging.STATUS_RELEASE_USE) {
                    listener.onUseItem(connection, false); // finished eating / released
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
        } else if (id == ServerboundUseEntity.PACKET_ID) {
            ServerboundUseEntity use = lazy.materialize(ServerboundUseEntity::fromBuffer);
            if (use.type == ServerboundUseEntity.TYPE_ATTACK && connection.getListener() != null) {
                connection.getListener().onAttack(connection, use.target);
            }
        } else if (id == 0x00) {
            // Teleport Confirm (sent after PlayerPositionAndLook)
            LOGGER.debug("Received Teleport Confirm (ignored for now)");
        }
        // TODO: Client Settings (0x04), Player flying flag (0x0C), etc.
    }

    /** Sends the mandatory packets right after Login Success so the client actually spawns. */
    private void sendInitialJoinSequence(JedrockConnection connection, UUID uuid) {
        Location spawn = connection.getWorld().getSpawnLocation();
        // The mode this client joins in: its remembered choice this run, else the config default.
        GameMode mode = connection.getListener() != null
                ? connection.getListener().gameModeFor(uuid)
                : connection.getServerProperties().defaultGameMode();

        connection.send(new ClientboundJoinGame(connection.getServerProperties().maxPlayers(), mode.getId()));
        connection.send(new ClientboundServerDifficulty());
        connection.send(new ClientboundSpawnPosition(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
        connection.send(new ClientboundPlayerAbilities(mode));
        connection.send(new ClientboundHeldItemChange());

        // Terrain around spawn — without chunks the client hangs on "Downloading terrain"
        connection.sendSpawnChunks();

        // Initial position + look on top of the generated ground. Sent after chunks.
        connection.send(new ClientboundPlayerPositionAndLook(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));

        // Keep alive so the client knows the connection is alive
        long now = System.currentTimeMillis();
        connection.send(new ClientboundKeepAlive(now));
        connection.setLastKeepAliveSent(now);
    }

    @Override
    public void tick(long currentTick, JedrockConnection connection) {
        if (!connection.isOpen() || connection.getState() != ProtocolState.PLAY) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - connection.getLastKeepAliveSent() > 15000) {
            connection.send(new ClientboundKeepAlive(now));
            connection.setLastKeepAliveSent(now);
        }
    }

    // ===== Clientbound wire encoding (JavaProtocol), 1.12.2 packet format =====

    @Override
    public void sendSystemMessage(JedrockConnection c, String message) {
        c.send(new ClientboundChatMessage("{\"text\":\"" + JsonText.escape(message) + "\"}"));
    }

    @Override
    public void addToTab(JedrockConnection c, UUID uuid, String name) {
        c.send(ClientboundPlayerListItem.add(uuid, name, 1)); // creative gamemode in the tab
    }

    @Override
    public void removeFromTab(JedrockConnection c, UUID uuid) {
        c.send(ClientboundPlayerListItem.remove(uuid));
    }

    @Override
    public void showPlayer(JedrockConnection c, UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        // The uuid is already in the tab (core adds it first), which 1.12.2 requires to render.
        c.send(new ClientboundSpawnPlayer((int) entityId, uuid, x, y, z, yaw, pitch));
    }

    @Override
    public void hidePlayer(JedrockConnection c, UUID uuid, long entityId) {
        c.send(new ClientboundDestroyEntities((int) entityId));
    }

    @Override
    public void moveAvatar(JedrockConnection c, long entityId, double x, double y, double z, float yaw, float pitch) {
        c.send(new ClientboundEntityTeleport((int) entityId, x, y, z, yaw, pitch));
        c.send(new ClientboundEntityHeadLook((int) entityId, yaw));
    }

    @Override
    public void teleportSelf(JedrockConnection c, double x, double y, double z, float yaw, float pitch) {
        c.send(new ClientboundPlayerPositionAndLook(x, y, z, yaw, pitch));
    }

    @Override
    public void setGameMode(JedrockConnection c, GameMode mode) {
        // Change Game State (reason 3) flips the HUD; Player Abilities re-grants/revokes flight.
        c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_CHANGE_GAMEMODE, mode.getId()));
        c.send(new ClientboundPlayerAbilities(mode));
    }

    @Override
    public void setInventory(JedrockConnection c, int[] states, int[] counts) {
        c.send(new ClientboundWindowItems(JeInventoryCodec.encode(states, counts, JeInventoryCodec.WINDOW_SLOTS_1_12)));
        // Mirror the hotbar (slots 0-8) into our held-item view: 1.12.2's Block Placement carries NO item,
        // so a survival placement's block comes from hotbarState[heldSlot]. Without this it stayed on the
        // creative-menu value (or air), so survival players placed the wrong block / nothing.
        for (int i = 0; i < 9; i++) {
            hotbarState[i] = counts[i] > 0 ? states[i] : 0;
        }
    }

    @Override
    public void setHealth(JedrockConnection c, int health) {
        c.send(new ClientboundUpdateHealth(health));
    }

    @Override
    public void setInventorySlot(JedrockConnection c, int slot, int state, int count) {
        // Set Slot (0x16): byte windowId=0, short slot, Slot data.
        c.send(new ClientboundSetSlot(JeInventoryCodec.windowSlot(slot), state, count));
        // Keep the held-item mirror in step with a live survival pickup / consume (see setInventory).
        if (slot >= 0 && slot < 9) {
            hotbarState[slot] = count > 0 ? state : 0;
        }
    }

    @Override
    public void swingArm(JedrockConnection c, long entityId) {
        c.send(new ClientboundAnimation((int) entityId, ClientboundAnimation.SWING_MAIN_ARM));
    }

    @Override
    public void playHurtAnimation(JedrockConnection c, long entityId) {
        c.send(new ClientboundEntityStatus((int) entityId, ClientboundEntityStatus.STATUS_HURT));
    }

    @Override
    public void setPose(JedrockConnection c, long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        c.send(ClientboundEntityMetadata.pose((int) entityId, sneaking, sprinting, usingItem));
    }

    @Override
    public void sendBlockChange(JedrockConnection c, int x, int y, int z, int state) {
        c.send(new ClientboundBlockChange(x, y, z, state));
    }

    @Override
    public void streamChunk(JedrockConnection c, int chunkX, int chunkZ) {
        c.send(new ClientboundChunkData(c.getWorld(), chunkX, chunkZ));
    }

    @Override
    public void unloadChunk(JedrockConnection c, int chunkX, int chunkZ) {
        c.send(new ClientboundUnloadChunk(chunkX, chunkZ));
    }
}
