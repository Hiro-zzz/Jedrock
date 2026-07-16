package com.jedrock.network.handler.je;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.network.EntityFlagIds;
import com.jedrock.network.EntityTypeIds;
import com.jedrock.network.JedrockConnection;
import com.jedrock.network.handler.je.v1_8.Java1_8ChunkData;
import com.jedrock.network.je.packet.ClientboundLoginSuccess;
import com.jedrock.network.je.packet.ClientboundPacket;
import com.jedrock.network.protocol.ProtocolState;
import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.lazy.LazyPacket;
import com.jedrock.utils.text.JsonText;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static com.jedrock.network.handler.je.v1_8.Java1_8Protocol.*;

/**
 * Java Edition 1.8 (protocol 47): login, play and the clientbound wire encoding. Installed by
 * {@link JavaHandshakeHandler} for a 1.8 client.
 *
 * <p>1.8 shares the world's {@code (id<<4)|meta} block model and the VarInt framing with 1.12.2, so
 * this is the 1.12.2 flow with the 1.8 packet ids and a few format deltas: a byte dimension in Join
 * Game, VarInt keep-alive ids, fixed-point entity coordinates, the old (header-tagged) entity
 * metadata format, no teleport-confirm handshake, and the 1.8 grouped chunk layout
 * ({@link Java1_8ChunkData}). Offline, no encryption.
 */
public final class Java1_8ProtocolHandler implements JavaProtocol {

    private static final JLogger LOGGER = JLogger.getLogger(Java1_8ProtocolHandler.class);

    private int keepAliveId = 0;
    private int openWindowId = 0; // a container (chest) window this client has open; 0 = none

    // ===== Inbound =====

    @Override
    public void handleInbound(LazyPacket packet, JedrockConnection connection) {
        if (packet == null) return;
        int id = packet.getPacketId();
        ProtocolState state = connection.getState();
        try {
            switch (state) {
                case LOGIN -> handleLogin(id, packet, connection);
                case PLAY -> handlePlay(id, packet, connection);
                default -> { /* handshake/status owned by JavaHandshakeHandler */ }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[1.8] error handling 0x" + Integer.toHexString(id) + ": " + e);
        } finally {
            packet.release();
        }
    }

    private void handleLogin(int id, LazyPacket lazy, JedrockConnection connection) {
        if (id != SB_LOGIN_START) return;
        String name = ByteBufUtils.readString(lazy.getPayload());
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        LOGGER.info("[1.8] login: " + name + " (" + uuid + ")");

        connection.send(new ClientboundLoginSuccess(uuid, name)); // 0x02, uuid+name strings (same as 1.12.2)
        connection.setState(ProtocolState.PLAY);
        sendJoinSequence(connection, uuid);

        connection.setLoggedIn(true);
        if (connection.getListener() != null) {
            connection.getListener().onLogin(connection, uuid, name);
        }
        LOGGER.info("[1.8] " + name + " joined (PLAY)");
    }

    private void handlePlay(int id, LazyPacket lazy, JedrockConnection connection) {
        ByteBuf in = lazy.getPayload();
        var listener = connection.getListener();
        if (id == SB_POSITION) {
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            connection.clientMoved(x, y, z, null, null);
        } else if (id == SB_POSITION_LOOK) {
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat();
            connection.clientMoved(x, y, z, yaw, pitch);
        } else if (id == SB_LOOK) {
            float yaw = in.readFloat(), pitch = in.readFloat();
            connection.clientMoved(null, null, null, yaw, pitch);
        } else if (id == SB_CHAT) {
            String message = ByteBufUtils.readString(in);
            if (listener != null && !message.isEmpty()) listener.onChat(connection, message);
        } else if (id == SB_ARM_ANIMATION) {
            if (listener != null) listener.onSwingArm(connection);
        } else if (id == SB_USE_ENTITY) {
            int target = ByteBufUtils.readVarInt(in);
            int type = ByteBufUtils.readVarInt(in);          // 0 interact, 1 attack, 2 interact-at
            if (type == 1 && listener != null) listener.onAttack(connection, target);
        } else if (id == SB_BLOCK_DIG) {
            int status = in.readByte();
            long pos = in.readLong();
            if (listener != null) {
                boolean creative = listener.gameModeOf(connection) == GameMode.CREATIVE;
                // Creative breaks on start (0, instant); survival only when digging finishes (2).
                if ((status == 0 && creative) || status == 2) {
                    listener.onBlockChange(connection, posX(pos), posY(pos), posZ(pos), Blocks.AIR);
                } else if (status == 5) {                    // release use item
                    listener.onUseItem(connection, false);
                }
            }
        } else if (id == SB_BLOCK_PLACE) {
            handleBlockPlace(in, connection);
        } else if (id == SB_ENTITY_ACTION) {
            ByteBufUtils.readVarInt(in);                     // entity id (self)
            int action = ByteBufUtils.readVarInt(in);
            if (listener != null) {
                switch (action) {
                    case 0 -> listener.onSneak(connection, true);
                    case 1 -> listener.onSneak(connection, false);
                    case 3 -> listener.onSprint(connection, true);
                    case 4 -> listener.onSprint(connection, false);
                    default -> { /* leave bed etc. */ }
                }
            }
        } else if (id == SB_CLICK_WINDOW) {
            int windowId = in.readUnsignedByte();
            int slot = in.readShort();
            int button = in.readByte();
            int actionNumber = in.readShort();
            int mode = in.readByte();                        // 1.8: a byte (VarInt only from 1.9)
            // Confirm (accept) first — the resync is the real correction.
            send(connection, CB_CONFIRM_TRANSACTION, b -> {
                b.writeByte(windowId);
                b.writeShort(actionNumber);
                b.writeBoolean(true);
            });
            if (listener != null) {
                boolean modeOk = mode == 0 || mode == 1;
                if (windowId == 0) {
                    int coreSlot = modeOk ? JeInventoryCodec.modelIndex(slot) : -1;
                    listener.onWindowClick(connection, coreSlot, button, modeOk && mode == 1);
                } else if (windowId == openWindowId && openWindowId != 0) {
                    listener.onChestClick(connection, modeOk ? slot : -1, button, modeOk && mode == 1);
                }
            }
        } else if (id == SB_CLOSE_WINDOW) {
            openWindowId = 0;
            if (listener != null) listener.onWindowClose(connection);
        } else if (id == SB_CREATIVE_ACTION) {
            int slot = in.readShort();
            int itemId = in.readShort();
            int count = 1, damage = 0;
            if (itemId != -1 && in.readableBytes() >= 3) {
                count = in.readUnsignedByte();
                damage = in.readShort() & 0xFFFF;
            }
            int state = itemId <= 0 ? 0 : Blocks.state(itemId, damage);
            int coreSlot = JeInventoryCodec.modelIndex(slot);
            if (coreSlot >= 0 && listener != null) {
                listener.onCreativeSetSlot(connection, coreSlot, state, count);
            }
        }
        // SB_KEEP_ALIVE / SB_HELD_ITEM / SB_CONFIRM_TRANSACTION — accepted, nothing to do.
    }

    /** 1.8 block placement: face 255 is a use-item; otherwise place the held block at face offset. */
    private void handleBlockPlace(ByteBuf in, JedrockConnection connection) {
        var listener = connection.getListener();
        long pos = in.readLong();
        int face = in.readByte() & 0xFF;
        if (face == 255) {                                   // right-click with item (eat / draw bow)
            if (listener != null) listener.onUseItem(connection, true);
            return;
        }
        // Right-click a block: if it's interactable (a chest) the core uses it (opens the window) and we
        // suppress the placement the same packet would otherwise be.
        if (listener != null && listener.onUseBlock(connection, posX(pos), posY(pos), posZ(pos))) {
            return;
        }
        int state = readHeldSlotState(in);                   // the 1.8 slot carries a legacy id + damage
        if (state != Blocks.AIR && Blocks.isKnown(Blocks.idOf(state)) && listener != null && face < 6) {
            listener.onBlockChange(connection,
                    posX(pos) + FACE_DX[face], posY(pos) + FACE_DY[face], posZ(pos) + FACE_DZ[face], state);
        }
    }

    /** Read a 1.8 held-item Slot far enough to get its canonical state; -1 id means empty. */
    private static int readHeldSlotState(ByteBuf in) {
        int itemId = in.readShort();
        if (itemId < 0) return Blocks.AIR;
        in.readByte();                                       // count
        int damage = in.readShort() & 0xFFFF;                // = block metadata
        return Blocks.state(itemId, damage);
    }

    // ===== Join sequence =====

    private void sendJoinSequence(JedrockConnection c, UUID uuid) {
        Location spawn = c.getWorld().getSpawnLocation();
        int max = Math.min(255, c.getServerProperties().maxPlayers());
        // The mode this client joins in: its remembered choice this run, else the config default.
        GameMode mode = c.getListener() != null
                ? c.getListener().gameModeFor(uuid)
                : c.getServerProperties().defaultGameMode();

        send(c, CB_JOIN_GAME, b -> {
            b.writeInt(1);                                   // entity id
            b.writeByte(mode.getId());                       // gamemode (from config)
            b.writeByte(0);                                  // dimension: overworld (signed byte in 1.8)
            b.writeByte(2);                                  // difficulty: normal
            b.writeByte(max);                                // max players (unsigned byte)
            ByteBufUtils.writeString(b, "default");          // level type
            b.writeBoolean(false);                           // reduced debug info
        });
        send(c, CB_SERVER_DIFFICULTY, b -> b.writeByte(2));
        send(c, CB_SPAWN_POSITION, b -> b.writeLong(packPos(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ())));
        send(c, CB_PLAYER_ABILITIES, b -> { b.writeByte(abilityFlags(mode)); b.writeFloat(0.05f); b.writeFloat(0.1f); });
        send(c, CB_HELD_ITEM, b -> b.writeByte(0));

        c.sendSpawnChunks();

        send(c, CB_POSITION, b -> {
            b.writeDouble(spawn.x()); b.writeDouble(spawn.y()); b.writeDouble(spawn.z());
            b.writeFloat(spawn.yaw()); b.writeFloat(spawn.pitch());
            b.writeByte(0);                                  // flags: all absolute (no teleport id in 1.8)
        });
        sendKeepAlive(c);
    }

    private void sendKeepAlive(JedrockConnection c) {
        int kid = ++keepAliveId;
        send(c, CB_KEEP_ALIVE, b -> ByteBufUtils.writeVarInt(b, kid)); // VarInt id in 1.8
        c.setLastKeepAliveSent(System.currentTimeMillis());
    }

    @Override
    public void tick(long currentTick, JedrockConnection connection) {
        if (!connection.isOpen() || connection.getState() != ProtocolState.PLAY) return;
        if (System.currentTimeMillis() - connection.getLastKeepAliveSent() > 15000) {
            sendKeepAlive(connection);
        }
    }

    // ===== Clientbound (1.8 format) =====

    @Override
    public void sendSystemMessage(JedrockConnection c, String message) {
        send(c, CB_CHAT, b -> { ByteBufUtils.writeString(b, "{\"text\":\"" + JsonText.escape(message) + "\"}"); b.writeByte(0); });
    }

    @Override
    public void addToTab(JedrockConnection c, UUID uuid, String name) {
        send(c, CB_PLAYER_LIST_ITEM, b -> {
            ByteBufUtils.writeVarInt(b, 0);                  // action: add player
            ByteBufUtils.writeVarInt(b, 1);                  // count
            writeUuid(b, uuid);
            ByteBufUtils.writeString(b, name);
            ByteBufUtils.writeVarInt(b, 0);                  // properties
            ByteBufUtils.writeVarInt(b, 1);                  // gamemode: creative
            ByteBufUtils.writeVarInt(b, 0);                  // ping
            b.writeBoolean(false);                           // has display name
        });
    }

    @Override
    public void removeFromTab(JedrockConnection c, UUID uuid) {
        send(c, CB_PLAYER_LIST_ITEM, b -> {
            ByteBufUtils.writeVarInt(b, 4);                  // action: remove player
            ByteBufUtils.writeVarInt(b, 1);
            writeUuid(b, uuid);
        });
    }

    @Override
    public void showPlayer(JedrockConnection c, UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        send(c, CB_SPAWN_PLAYER, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            writeUuid(b, uuid);
            b.writeInt(fixed(x)); b.writeInt(fixed(y)); b.writeInt(fixed(z));
            b.writeByte(angle(yaw)); b.writeByte(angle(pitch));
            b.writeShort(0);                                 // current item: none
            b.writeByte(META_END);                           // empty metadata
        });
    }

    @Override
    public void hidePlayer(JedrockConnection c, UUID uuid, long entityId) {
        send(c, CB_ENTITY_DESTROY, b -> { ByteBufUtils.writeVarInt(b, 1); ByteBufUtils.writeVarInt(b, (int) entityId); });
    }

    @Override
    public void moveAvatar(JedrockConnection c, long entityId, double x, double y, double z, float yaw, float pitch) {
        send(c, CB_ENTITY_TELEPORT, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeInt(fixed(x)); b.writeInt(fixed(y)); b.writeInt(fixed(z));
            b.writeByte(angle(yaw)); b.writeByte(angle(pitch));
            b.writeBoolean(true);                            // on ground
        });
        send(c, CB_ENTITY_HEAD_ROTATION, b -> { ByteBufUtils.writeVarInt(b, (int) entityId); b.writeByte(angle(yaw)); });
    }

    @Override
    public void spawnEntity(JedrockConnection c, long entityId, UUID uuid, com.jedrock.api.entity.EntityType type,
                            double x, double y, double z, float yaw, float pitch) {
        // Spawn Mob (0x0F): entityId, byte type (classic id), fixed-point pos, angle yaw/pitch/headYaw,
        // 3× velocity short, then an empty (0x7f-terminated) metadata block. No uuid in 1.8's Spawn Mob.
        send(c, CB_SPAWN_MOB, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeByte(EntityTypeIds.javaId(type));
            b.writeInt(fixed(x)); b.writeInt(fixed(y)); b.writeInt(fixed(z));
            b.writeByte(angle(yaw)); b.writeByte(angle(pitch)); b.writeByte(angle(yaw)); // yaw, pitch, head yaw
            b.writeShort(0); b.writeShort(0); b.writeShort(0);                            // velocity x, y, z
            b.writeByte(META_END);
        });
    }

    @Override
    public void removeEntity(JedrockConnection c, long entityId) {
        send(c, CB_ENTITY_DESTROY, b -> { ByteBufUtils.writeVarInt(b, 1); ByteBufUtils.writeVarInt(b, (int) entityId); });
    }

    @Override
    public void setEntityNameTag(JedrockConnection c, long entityId, String nameTag) {
        send(c, CB_ENTITY_METADATA, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            writeNameTag(b, nameTag);
            b.writeByte(META_END);
        });
    }

    @Override
    public void setEntityFlags(JedrockConnection c, long entityId, int flags) {
        send(c, CB_ENTITY_METADATA, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            writeMetaByte(b, META_INDEX_FLAGS, EntityFlagIds.javaBits(flags));
            b.writeByte(META_END);
        });
    }

    @Override
    public void spawnTextLine(JedrockConnection c, long entityId, UUID uuid,
                              double x, double y, double z, String text) {
        // Same Spawn Mob as a puppet, but an invisible marker armor stand carrying the text as its name.
        // 1.8 has no NoGravity metadata (that arrived in 1.10) and needs none: the client never simulates
        // a server-driven entity's physics, it only interpolates towards what we send.
        send(c, CB_SPAWN_MOB, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeByte(ARMOR_STAND_TYPE_ID);
            b.writeInt(fixed(x)); b.writeInt(fixed(y + ARMOR_STAND_NAME_OFFSET)); b.writeInt(fixed(z));
            b.writeByte(0); b.writeByte(0); b.writeByte(0);      // yaw, pitch, head yaw
            b.writeShort(0); b.writeShort(0); b.writeShort(0);   // velocity x, y, z
            writeMetaByte(b, META_INDEX_FLAGS, FLAG_INVISIBLE);
            writeNameTag(b, text);
            writeMetaByte(b, META_INDEX_ARMOR_STAND_FLAGS,
                    ARMOR_STAND_MARKER | ARMOR_STAND_SMALL | ARMOR_STAND_NO_BASE_PLATE);
            b.writeByte(META_END);
        });
    }

    /** Custom name (string) + its visibility (a byte — 1.8 has no boolean metadata type). */
    private static void writeNameTag(ByteBuf b, String nameTag) {
        boolean shown = nameTag != null && !nameTag.isEmpty();
        b.writeByte((META_TYPE_STRING << 5) | META_INDEX_CUSTOM_NAME);
        ByteBufUtils.writeString(b, shown ? nameTag : "");
        writeMetaByte(b, META_INDEX_CUSTOM_NAME_VISIBLE, shown ? 1 : 0);
    }

    /** One byte-typed metadata entry: the 1.8 header packs type and index into a single byte. */
    private static void writeMetaByte(ByteBuf b, int index, int value) {
        b.writeByte((META_TYPE_BYTE << 5) | index);
        b.writeByte(value);
    }

    @Override
    public void teleportSelf(JedrockConnection c, double x, double y, double z, float yaw, float pitch) {
        send(c, CB_POSITION, b -> {
            b.writeDouble(x); b.writeDouble(y); b.writeDouble(z);
            b.writeFloat(yaw); b.writeFloat(pitch);
            b.writeByte(0);
        });
    }

    @Override
    public void setGameMode(JedrockConnection c, GameMode mode) {
        // Change Game State (0x2B): byte reason 3 = change game mode, float value = the mode id.
        send(c, CB_CHANGE_GAME_STATE, b -> { b.writeByte(3); b.writeFloat(mode.getId()); });
        send(c, CB_PLAYER_ABILITIES, b -> { b.writeByte(abilityFlags(mode)); b.writeFloat(0.05f); b.writeFloat(0.1f); });
    }

    @Override
    public void setInventory(JedrockConnection c, int[] states, int[] counts) {
        send(c, CB_WINDOW_ITEMS, b -> JeInventoryCodec.writeWindowItems(b, states, counts, JeInventoryCodec.WINDOW_SLOTS_1_8));
    }

    @Override
    public void setHealth(JedrockConnection c, int health) {
        // 1.8 Update Health (0x06): float health, VarInt food, float saturation.
        send(c, CB_UPDATE_HEALTH, b -> { b.writeFloat(health); ByteBufUtils.writeVarInt(b, 20); b.writeFloat(5.0f); });
    }

    @Override
    public void setInventorySlot(JedrockConnection c, int slot, int state, int count) {
        // Set Slot (0x2F): byte windowId=0, short slot, Slot data (same Slot format as 1.12.2).
        send(c, CB_SET_SLOT, b -> {
            b.writeByte(0);
            b.writeShort(JeInventoryCodec.windowSlot(slot));
            JeInventoryCodec.writeSlot(b, state, count);
        });
    }

    @Override
    public void setCursorItem(JedrockConnection c, int state, int count) {
        // Set Slot to window -1 / slot -1 = the cursor (carried item).
        send(c, CB_SET_SLOT, b -> {
            b.writeByte(-1);
            b.writeShort(-1);
            JeInventoryCodec.writeSlot(b, state, count);
        });
    }

    @Override
    public void openContainer(JedrockConnection c, int windowId, String title, int slots, int x, int y, int z) {
        openWindowId = windowId; // Java's Open Window needs no block position (x/y/z ignored)
        // Open Window (0x2D): windowId, window type string, chat title, slot count.
        send(c, CB_OPEN_WINDOW, b -> {
            b.writeByte(windowId);
            ByteBufUtils.writeString(b, "minecraft:chest");
            ByteBufUtils.writeString(b, "{\"text\":\""
                    + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            b.writeByte(slots);
        });
    }

    @Override
    public void setWindowItems(JedrockConnection c, int windowId, int[] states, int[] counts) {
        // Window Items (0x30) — the core assembled the slots in wire order, so write the raw body.
        send(c, CB_WINDOW_ITEMS, b -> b.writeBytes(JeInventoryCodec.encodeRaw(windowId, states, counts)));
    }

    /** 1.8 Player Abilities flags for a mode: creative → invuln|allowFly|creative; else no flight. */
    private static int abilityFlags(GameMode mode) {
        if (mode == GameMode.CREATIVE) {
            return 0x0D; // invulnerable | allow-fly | creative
        }
        return mode.allowsFlight() ? 0x04 : 0x00; // spectator flies; survival/adventure don't
    }

    @Override
    public void swingArm(JedrockConnection c, long entityId) {
        send(c, CB_ANIMATION, b -> { ByteBufUtils.writeVarInt(b, (int) entityId); b.writeByte(ANIMATION_SWING); });
    }

    @Override
    public void playHurtAnimation(JedrockConnection c, long entityId) {
        // Entity Status (0x1A): plain int32 entity id + byte status (2 = living entity hurt).
        send(c, CB_ENTITY_STATUS, b -> { b.writeInt((int) entityId); b.writeByte(2); });
    }

    @Override
    public void setPose(JedrockConnection c, long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        // 1.8 metadata (old format): header = (type<<5)|index, then value, list ends with 0x7F.
        // Crouch, sprint and item-use all live in the shared entity-flags byte (index 0), so the full
        // pose travels together — matching the cross-edition setPose contract.
        int flags = (sneaking ? FLAG_CROUCHED : 0) | (sprinting ? FLAG_SPRINTING : 0)
                | (usingItem ? FLAG_USING_ITEM : 0);
        send(c, CB_ENTITY_METADATA, b -> {
            ByteBufUtils.writeVarInt(b, (int) entityId);
            b.writeByte((META_TYPE_BYTE << 5) | META_INDEX_FLAGS);
            b.writeByte(flags);
            b.writeByte(META_END);
        });
    }

    @Override
    public void sendBlockChange(JedrockConnection c, int x, int y, int z, int state) {
        send(c, CB_BLOCK_CHANGE, b -> { b.writeLong(packPos(x, y, z)); ByteBufUtils.writeVarInt(b, state); });
    }

    @Override
    public void streamChunk(JedrockConnection c, int chunkX, int chunkZ) {
        c.send(new Java1_8ChunkData(c.getWorld(), chunkX, chunkZ));
    }

    @Override
    public void unloadChunk(JedrockConnection c, int chunkX, int chunkZ) {
        c.send(Java1_8ChunkData.unload(chunkX, chunkZ));
    }

    // ===== helpers =====

    private static void send(JedrockConnection c, int id, Consumer<ByteBuf> body) {
        c.send(new ClientboundPacket() {
            @Override public int getPacketId() { return id; }
            @Override public void write(ByteBuf buf) { body.accept(buf); }
        });
    }

    private static void writeUuid(ByteBuf b, UUID uuid) {
        b.writeLong(uuid.getMostSignificantBits());
        b.writeLong(uuid.getLeastSignificantBits());
    }

    /** JE 1.8 block-position packing: x(26) | y(12) | z(26), y in the middle. */
    private static long packPos(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((y & 0xFFFL) << 26) | (z & 0x3FFFFFFL);
    }
    private static int posX(long v) { return (int) (v >> 38); }
    private static int posY(long v) { return (int) ((v >> 26) & 0xFFF); }
    private static int posZ(long v) { return (int) (v << 38 >> 38); }

    private static int fixed(double coord) { return (int) Math.floor(coord * FIXED_POINT); }
    private static int angle(float degrees) { return (byte) (int) (degrees * 256.0f / 360.0f); }
}
