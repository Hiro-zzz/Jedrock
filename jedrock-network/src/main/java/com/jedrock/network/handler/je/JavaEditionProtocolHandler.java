package com.jedrock.network.handler.je;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.network.EntityFlagIds;
import com.jedrock.network.EntityTypeIds;
import com.jedrock.network.JedrockConnection;
import com.jedrock.network.je.packet.*;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import com.jedrock.utils.text.JsonText;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

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
    private int openWindowId = 0;                  // a container (chest) window this client has open; 0 = none

    // ===== Inbound state machine (LOGIN + PLAY) =====

    @Override
    public void handleInbound(LazyPacket packet, JedrockConnection connection) {
        if (packet == null) return;

        int id = packet.getPacketId();
        ProtocolState state = connection.getState();
        ByteBuf payload = packet.getPayload();

        try {
            // Runs for every inbound packet — movement alone is ~20/s per player — so the lambda is
            // gated rather than handed to the supplier form, which would allocate it even with debug off.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Inbound 0x" + Integer.toHexString(id) +
                        " (state=" + state + ", bytes=" + (payload != null ? payload.readableBytes() : 0) + ")");
            }

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
            connection.onKeepAliveResponse(); // the round trip is the player's ping
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
            if (h.slot >= 0 && h.slot < 9) {
                heldSlot = h.slot;
                if (connection.getListener() != null) {
                    connection.getListener().onHeldSlotChange(connection, h.slot);
                }
            }
        } else if (id == ServerboundCreativeInventoryAction.PACKET_ID) {
            ServerboundCreativeInventoryAction c = lazy.materialize(ServerboundCreativeInventoryAction::fromBuffer);
            int state = c.itemId <= 0 ? 0 : Blocks.state(c.itemId, c.damage);
            if (c.slot >= 36 && c.slot <= 44) {
                hotbarState[c.slot - 36] = state;         // held-item view, for placement
            }
            // Mirror the creative slot into the core inventory too, so an open chest's player half is right.
            int coreSlot = JeInventoryCodec.modelIndex(c.slot);
            if (coreSlot >= 0 && connection.getListener() != null) {
                connection.getListener().onCreativeSetSlot(connection, coreSlot, state, c.count);
            }
        } else if (id == ServerboundPlayerBlockPlacement.PACKET_ID) {
            ServerboundPlayerBlockPlacement place = lazy.materialize(ServerboundPlayerBlockPlacement::fromBuffer);
            var listener = connection.getListener();
            if (listener != null) {
                // Right-click a block: if it's interactable (a chest), the core uses it (opens the window)
                // and we suppress the placement the same packet would otherwise be.
                if (!listener.onUseBlock(connection, place.x, place.y, place.z)) {
                    int state = hotbarState[heldSlot];
                    if (Blocks.isKnown(Blocks.idOf(state)) && state != Blocks.AIR) {
                        listener.onBlockChange(connection, place.placeX(), place.placeY(), place.placeZ(), state);
                    }
                }
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
        } else if (id == ServerboundClickWindow.PACKET_ID) {
            ServerboundClickWindow click = lazy.materialize(ServerboundClickWindow::fromBuffer);
            var listener = connection.getListener();
            if (listener != null) {
                // Confirm first (accepted) — the resync is the real correction, so we skip the client's
                // reject/apologise round-trip. We handle normal (0) and shift (1) clicks; other modes pass
                // as a plain resync (slot -1).
                connection.send(new ClientboundConfirmTransaction(click.windowId, click.actionNumber, true));
                boolean modeOk = click.mode == 0 || click.mode == 1;
                if (click.windowId == 0) {
                    // The player's own inventory: translate the window slot to a core slot.
                    int coreSlot = modeOk ? JeInventoryCodec.modelIndex(click.slot) : -1;
                    listener.onWindowClick(connection, coreSlot, click.button, modeOk && click.mode == 1);
                } else if (click.windowId == openWindowId && openWindowId != 0) {
                    // The open chest window: the core translates the raw window slot (0-26 chest, 27-62 self).
                    listener.onChestClick(connection, modeOk ? click.slot : -1, click.button,
                            modeOk && click.mode == 1);
                }
            }
        } else if (id == 0x08) { // Close Window — return any carried item to the inventory
            openWindowId = 0;
            if (connection.getListener() != null) {
                connection.getListener().onWindowClose(connection);
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

    /** 1.12.2 Title packet (id 0x48 — NOT 0x4B, which is another packet): actions 0 title, 1 subtitle,
     *  2 action-bar, 3 times, 4 hide, 5 reset. Verified against minecraft-data for protocol 340. */
    private static final int CB_TITLE = 0x48;
    private static final int CB_NAMED_SOUND = 0x19;      // named sound effect (name + category + pos*8 + volume + pitch)
    private static final int CB_WORLD_PARTICLES = 0x22;  // particle burst (same body as 1.8's 0x2a)
    private static final int CB_ENTITY_EQUIPMENT = 0x3f; // eid (varint) + slot (varint, 0 = main hand) + item
    private static final int CB_SPAWN_OBJECT = 0x00;     // non-mob entities: item stacks, falling blocks…

    @Override
    public void sendActionBar(JedrockConnection c, String text) {
        // Position 2 on the chat packet = the action bar (works the same on 1.8, kept parallel).
        ClientboundChatMessage m = new ClientboundChatMessage(json(text));
        m.position = 2;
        c.send(m);
    }

    @Override
    public void sendTitle(JedrockConnection c, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        send(c, CB_TITLE, b -> { ByteBufUtils.writeVarInt(b, 3); b.writeInt(fadeIn); b.writeInt(stay); b.writeInt(fadeOut); });
        send(c, CB_TITLE, b -> { ByteBufUtils.writeVarInt(b, 1); ByteBufUtils.writeString(b, json(subtitle)); });
        send(c, CB_TITLE, b -> { ByteBufUtils.writeVarInt(b, 0); ByteBufUtils.writeString(b, json(title)); });
    }

    @Override
    public void clearTitle(JedrockConnection c) {
        send(c, CB_TITLE, b -> ByteBufUtils.writeVarInt(b, 5)); // 5 = reset
    }

    @Override
    public void showHeldItem(JedrockConnection c, long entityId, int state) {
        // Entity Equipment (0x3f at 340): entity id (varint), equipment slot (varint, 0 = main hand),
        // then the standard Slot — ground truth minecraft-data packet_entity_equipment.
        sendEquipment(c, entityId, 0, state);
    }

    @Override
    public void spawnItemEntity(JedrockConnection c, long entityId, java.util.UUID uuid,
                                double x, double y, double z, int state) {
        // Spawn Object (0x00 at 340): id, UUID, object type (2 = item stack), doubles, pitch/yaw angles,
        // object data and a velocity vector (always present at 340) — minecraft-data packet_spawn_entity.
        send(c, CB_SPAWN_OBJECT, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeLong(uuid.getMostSignificantBits());
            b.writeLong(uuid.getLeastSignificantBits());
            b.writeByte(EntityTypeIds.JAVA_OBJECT_ITEM_STACK);
            b.writeDouble(x); b.writeDouble(y); b.writeDouble(z);
            b.writeByte(0); b.writeByte(0);                    // pitch, yaw
            b.writeInt(1);                                     // object data (non-zero: has velocity)
            b.writeShort(0); b.writeShort(0); b.writeShort(0); // velocity: none, it's a prop
        });
        // The spawn packet says "an item stack is here" but not which — that rides in the metadata.
        // Item is index 6 at 1.12.2 (5 in 1.9, shifted by the no-gravity field 1.10 added at 5).
        c.send(ClientboundEntityMetadata.item((int) entityId, state));
    }

    @Override
    public void spawnFallingBlock(JedrockConnection c, long entityId, java.util.UUID uuid,
                                  double x, double y, double z, int state) {
        // Spawn Object type 70; the block rides in the object-data int as id | (meta << 12) — the
        // pre-flattening packing ViaVersion's 1.13 converter reads back as ((d & 4095) << 4) | (d >> 12).
        int objectData = Blocks.idOf(state) | (Blocks.metaOf(state) << 12);
        send(c, CB_SPAWN_OBJECT, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeLong(uuid.getMostSignificantBits());
            b.writeLong(uuid.getLeastSignificantBits());
            b.writeByte(EntityTypeIds.JAVA_OBJECT_FALLING_BLOCK);
            b.writeDouble(x); b.writeDouble(y); b.writeDouble(z);
            b.writeByte(0); b.writeByte(0);                    // pitch, yaw
            b.writeInt(objectData);
            b.writeShort(0); b.writeShort(0); b.writeShort(0); // velocity: none, it's a prop
        });
        // A falling block is one of the few entities the client animates on its own, so pin it: the
        // no-gravity field (index 5, added in 1.10) is what keeps this prop from dropping.
        c.send(ClientboundEntityMetadata.noGravity((int) entityId));
    }

    @Override
    public void showArmor(JedrockConnection c, long entityId,
                          int helmet, int chestplate, int leggings, int boots) {
        // Since 1.9 the slots are 0 = main hand, 1 = off hand, then 2-5 feet-to-head — the 1.8 numbering
        // shifted by one when the off-hand landed (ground truth ViaVersion's 1.9→1.8 slot transform).
        sendEquipment(c, entityId, 2, boots);
        sendEquipment(c, entityId, 3, leggings);
        sendEquipment(c, entityId, 4, chestplate);
        sendEquipment(c, entityId, 5, helmet);
    }

    /** One Entity Equipment packet: entity id (varint), equipment slot (varint at 340), then the Slot. */
    private static void sendEquipment(JedrockConnection c, long entityId, int equipmentSlot, int state) {
        send(c, CB_ENTITY_EQUIPMENT, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            ByteBufUtils.writeVarInt(b, equipmentSlot);
            JeInventoryCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        });
    }

    @Override
    public void sendWeather(JedrockConnection c, com.jedrock.api.world.Weather weather) {
        // Same reason semantics as 1.8: 1 = end rain, 2 = begin rain, then the strengths — reason 7
        // is setRainStrength and 8 is setThunderStrength on the client, so full-strength values make
        // the change visible instantly instead of ramping in (or, with 0s, not at all).
        switch (weather) {
            case CLEAR -> {
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_END_RAIN, 0f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_RAIN_STRENGTH, 0f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_THUNDER_STRENGTH, 0f));
            }
            case RAIN -> {
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_BEGIN_RAIN, 0f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_RAIN_STRENGTH, 1f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_THUNDER_STRENGTH, 0f));
            }
            case THUNDER -> {
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_BEGIN_RAIN, 0f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_RAIN_STRENGTH, 1f));
                c.send(new ClientboundChangeGameState(ClientboundChangeGameState.REASON_THUNDER_STRENGTH, 1f));
            }
        }
    }

    @Override
    public void playSound(JedrockConnection c, com.jedrock.api.world.Sound sound,
                          double x, double y, double z, float volume, float pitch) {
        // 1.12.2 Named Sound Effect (0x19): name, sound category (varint, 0 = master), effect position
        // as fixed-point ints (×8), volume and pitch floats (ground truth minecraft-data pc/1.12.2).
        String name = JeEffects.soundName1_12(sound);
        send(c, CB_NAMED_SOUND, b -> {
            ByteBufUtils.writeString(b, name);
            ByteBufUtils.writeVarInt(b, 0);
            b.writeInt((int) (x * 8.0));
            b.writeInt((int) (y * 8.0));
            b.writeInt((int) (z * 8.0));
            b.writeFloat(volume);
            b.writeFloat(pitch);
        });
    }

    @Override
    public void spawnParticle(JedrockConnection c, com.jedrock.api.world.Particle particle,
                              double x, double y, double z, int count, double spread) {
        int id = JeEffects.particleId(particle);
        send(c, CB_WORLD_PARTICLES, b -> JeEffects.writeParticleBody(b, id, x, y, z, count, spread));
    }

    /** Wrap an already-rendered legacy (§) string as a JSON chat component. */
    private static String json(String legacy) {
        return "{\"text\":\"" + JsonText.escape(legacy == null ? "" : legacy) + "\"}";
    }

    /** Send a raw versioned packet — a numeric id + a body writer (mirrors the 1.8 handler's helper). */
    private static void send(JedrockConnection c, int id, Consumer<ByteBuf> body) {
        c.send(new ClientboundPacket() {
            @Override public int getPacketId() { return id; }
            @Override public void write(ByteBuf buf) { body.accept(buf); }
        });
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
    public void spawnEntity(JedrockConnection c, long entityId, UUID uuid, com.jedrock.api.entity.EntityType type,
                            double x, double y, double z, float yaw, float pitch) {
        c.send(new ClientboundSpawnMob((int) entityId, uuid, EntityTypeIds.javaId(type), x, y, z, yaw, pitch));
    }

    @Override
    public void removeEntity(JedrockConnection c, long entityId) {
        c.send(new ClientboundDestroyEntities((int) entityId));
    }

    @Override
    public void setEntityNameTag(JedrockConnection c, long entityId, String nameTag) {
        c.send(ClientboundEntityMetadata.nameTag((int) entityId, nameTag));
    }

    @Override
    public void setEntityFlags(JedrockConnection c, long entityId, int flags) {
        c.send(ClientboundEntityMetadata.flags((int) entityId, EntityFlagIds.javaBits(flags)));
    }

    @Override
    public void spawnTextLine(JedrockConnection c, long entityId, UUID uuid,
                              double x, double y, double z, String text) {
        c.send(ClientboundSpawnMob.textLine((int) entityId, uuid, x, y, z, text));
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
    public void setCursorItem(JedrockConnection c, int state, int count) {
        c.send(new ClientboundSetSlot(-1, -1, state, count)); // window -1 / slot -1 = the cursor
    }

    @Override
    public void openContainer(JedrockConnection c, int windowId, String title, int slots, int x, int y, int z) {
        openWindowId = windowId; // Java's Open Window needs no block position (x/y/z ignored)
        c.send(new ClientboundOpenWindow(windowId, "minecraft:chest", title, slots));
    }

    @Override
    public void setWindowItems(JedrockConnection c, int windowId, int[] states, int[] counts) {
        c.send(new ClientboundWindowItems(JeInventoryCodec.encodeRaw(windowId, states, counts)));
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
