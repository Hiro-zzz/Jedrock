package com.jedrock.network.pe.v014;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.chunk.ChunkView;
import com.jedrock.utils.JLogger;
import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetReliability;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetSessionListener;
import com.nukkitx.network.raknet.RakNetState;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.UUID;
import java.util.function.Consumer;

import static com.jedrock.network.pe.v014.Mcpe014Packets.*;

/**
 * One Bedrock/MCPE 0.14 (protocol 45) player session: the RakNet callbacks, the 0.14 game-layer state
 * machine that walks a client from Login to spawn, AND the {@link PlayerConnection} the core sees — so
 * a 0.14 player lands in the same {@code PlayerRegistry} as a Java or 1.1.5 player and shares the world,
 * chat and avatars.
 *
 * <p>This is the 0.14 counterpart of {@code PeSession} (1.1.5): same shape, different wire. 0.14 is the
 * pre-VarInt era — big-endian fields, a one-byte {@code 0x8e} wrapper on every packet, a {@code 0x92}
 * zlib batch for big packets (chunks), plaintext login and 128-tall full-column chunks. Formats are
 * ground-truthed from PocketMine-MP at protocol 45 (see {@code mcpe-protocol-45-reference}).
 */
public final class PeSession014 implements RakNetSessionListener, PlayerConnection {

    private static final JLogger LOGGER = JLogger.getLogger(PeSession014.class);

    private static final int MAX_VIEW_RADIUS = 4;

    private final RakNetServerSession session;
    private final ConnectionListener listener;
    private final World world;
    private final ServerProperties properties;

    private volatile boolean loggedIn = false;
    private volatile UUID uuid;
    private volatile String username;

    private ChunkView chunkView;
    private final ChunkView.Sink chunkSink = new ChunkView.Sink() {
        @Override public void load(int cx, int cz) { sendChunk(cx, cz); }
        @Override public void unload(int cx, int cz) { /* 0.14 client culls by distance */ }
    };

    public PeSession014(RakNetServerSession session, ConnectionListener listener, World world,
                        ServerProperties properties) {
        this.session = session;
        this.listener = listener;
        this.world = world;
        this.properties = properties;
    }

    // ===== PlayerConnection (api) =====

    @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.PE_0_14; }
    @Override public String getAddress() { return String.valueOf(session.getAddress()); }
    @Override public boolean isActive() { return !session.isClosed(); }
    @Override public void close(String reason) { session.disconnect(); }
    @Override public void sendPacket(Object packet) { /* core needs only identity + lifecycle */ }

    @Override
    public void sendMessage(String message) {
        sendWrapped(b -> Mcpe014Packets.text(b, message));
    }

    @Override public void addToTab(UUID uuid, String name) { /* avatar carries the name via AddPlayer */ }
    @Override public void removeFromTab(UUID uuid) { /* see hidePlayer */ }

    @Override
    public void showPlayer(UUID uuid, String name, long entityId,
                           double x, double y, double z, float yaw, float pitch) {
        // Player-list entry first (feeds the pause-menu list), then the avatar. AddPlayer takes feet y.
        // The list needs a valid skin (an empty one crashes the client), so hand it a synthetic texture.
        byte[] skin = Mcpe014Skin.synthetic(uuid);
        sendWrapped(b -> Mcpe014Packets.playerListAdd(b, uuid, entityId, name, Mcpe014Skin.SKIN_NAME, skin));
        sendWrapped(b -> Mcpe014Packets.addPlayer(b, uuid, name, entityId,
                (float) x, (float) y, (float) z, yaw, pitch));
    }

    @Override
    public void hidePlayer(UUID uuid, long entityId) {
        sendWrapped(b -> Mcpe014Packets.removeEntity(b, entityId));
        sendWrapped(b -> Mcpe014Packets.playerListRemove(b, uuid));
    }

    @Override
    public void spawnEntity(long entityId, UUID uuid, com.jedrock.api.entity.EntityType type,
                            double x, double y, double z, float yaw, float pitch) {
        // AddEntity takes feet y. The type maps to the shared MCPE id table.
        int typeId = com.jedrock.network.EntityTypeIds.bedrockId(type);
        sendWrapped(b -> Mcpe014Packets.addEntity(b, entityId, typeId,
                (float) x, (float) y, (float) z, yaw, pitch));
    }

    @Override
    public void moveEntity(long entityId, double x, double y, double z, float yaw, float pitch) {
        // MoveEntity takes eye y (same as moveAvatar).
        sendWrapped(b -> Mcpe014Packets.moveEntity(b, entityId,
                (float) x, (float) y + EYE_HEIGHT, (float) z, yaw, pitch));
    }

    @Override
    public void removeEntity(long entityId) {
        sendWrapped(b -> Mcpe014Packets.removeEntity(b, entityId));
    }

    @Override
    public void spawnTextLine(long entityId, UUID uuid, double x, double y, double z, String text) {
        sendWrapped(b -> Mcpe014Packets.addTextLine(b, entityId, (float) x, (float) y, (float) z, text));
    }

    @Override
    public void setEntityNameTag(long entityId, String nameTag) {
        sendWrapped(b -> Mcpe014Packets.setEntityNameTag(b, entityId, nameTag));
    }

    @Override
    public void setEntityFlags(long entityId, int flags) {
        sendWrapped(b -> Mcpe014Packets.setEntityFlags(b, entityId,
                (int) com.jedrock.network.EntityFlagIds.bedrockBits(flags)));
    }

    @Override
    public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {
        // MoveEntity takes eye y.
        sendWrapped(b -> Mcpe014Packets.moveEntity(b, entityId,
                (float) x, (float) y + EYE_HEIGHT, (float) z, yaw, pitch));
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        sendWrapped(b -> Mcpe014Packets.movePlayerSelf(b,
                (float) x, (float) y + EYE_HEIGHT, (float) z, yaw, pitch, MOVE_MODE_RESET));
    }

    @Override
    public void setGameMode(GameMode mode) {
        // 0.14 has no verified live game-mode packet, and this client crashes on a wrong id, so we don't
        // guess one on the wire — the mode is applied at StartGame on the next join (the server remembers
        // it, so a reconnect comes back in the chosen mode). Tell the player.
        sendMessage("§eGame mode set to " + mode.displayName() + " — reconnect to apply on 0.14.");
    }

    /** The mode this client joins in: its remembered choice this run, else the config default. */
    private GameMode joinGameMode() {
        return listener != null ? listener.gameModeFor(uuid) : properties.defaultGameMode();
    }

    @Override
    public void setHealth(int health) {
        // The 0.14 client only ever shows the health it's told — it reports no fall packet, so server-side
        // fall tracking (JedrockServer) is the sole damage source and this pushes the result to the HUD.
        // SetHealth (0xb0) is a raw big-endian int on protocol 45 (no zigzag varint — that's 1.1.5).
        sendWrapped(b -> Mcpe014Packets.setHealth(b, health));
    }

    @Override
    public void swingArm(long entityId) {
        sendWrapped(b -> Mcpe014Packets.animate(b, ANIMATE_SWING, entityId));
    }

    @Override
    public void playHurtAnimation(long entityId) {
        sendWrapped(b -> Mcpe014Packets.entityEvent(b, entityId, ENTITY_EVENT_HURT));
    }

    @Override
    public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {
        // Crouch needs the DATA_FLAGS byte; the 0.14 client draws sprint / item-use itself, but sending
        // them too is harmless and keeps a late joiner in sync with the full pose.
        sendWrapped(b -> Mcpe014Packets.setEntityDataFlags(b, entityId, sneaking, sprinting, usingItem));
    }

    @Override
    public void sendBlockChange(int x, int y, int z, int state) {
        sendWrapped(b -> Mcpe014Packets.updateBlock(b, x, y, z, (state >> 4) & 0xFF, state & 0x0F));
    }

    // ===== RakNet callbacks =====

    @Override
    public void onSessionChangeState(RakNetState state) {
        LOGGER.debug(() -> "[0.14] " + session.getAddress() + " -> " + state);
    }

    @Override
    public void onDisconnect(DisconnectReason reason) {
        LOGGER.info("[0.14] disconnect " + session.getAddress() + " (" + reason + ")");
        if (loggedIn && listener != null) {
            listener.onDisconnect(this);
        }
    }

    @Override public void onDirect(ByteBuf buf) { }

    @Override
    public void onEncapsulated(EncapsulatedPacket packet) {
        ByteBuf buf = packet.getBuffer();
        int n = buf.readableBytes();
        if (n < 2) return;
        byte[] data = new byte[n];
        buf.getBytes(buf.readerIndex(), data);

        int offset = (data[0] & 0xFF) == WRAPPER ? 1 : 0; // strip the 0x8e game wrapper
        int id = data[offset] & 0xFF;
        ByteBuf body = Unpooled.wrappedBuffer(data, offset + 1, n - offset - 1);
        try {
            handleGamePacket(id, body);
        } catch (RuntimeException e) {
            LOGGER.debug(() -> "[0.14] error handling 0x" + Integer.toHexString(id) + ": " + e);
        }
    }

    private void handleGamePacket(int id, ByteBuf in) {
        switch (id) {
            case ID_LOGIN -> {
                Mcpe014Login.Identity identity = Mcpe014Login.decode(in);
                this.uuid = identity.uuid();
                this.username = identity.name();
                LOGGER.info("[0.14] Login: " + username + " (" + uuid + ")");
                sendLoginSequence();
            }
            case ID_REQUEST_CHUNK_RADIUS -> {
                int requested = in.readableBytes() >= 4 ? in.readInt() : 8;
                int radius = Math.clamp(requested, 2, MAX_VIEW_RADIUS);
                streamWorldAndSpawn(radius);
                registerPlayer();
            }
            case ID_MOVE_PLAYER -> handleMove(in);
            case ID_TEXT -> handleText(in);
            case ID_REMOVE_BLOCK -> handleRemoveBlock(in);
            case ID_USE_ITEM -> handleUseItem(in);
            case ID_CONTAINER_SET_SLOT -> handleContainerSetSlot(in);
            case ID_CONTAINER_CLOSE -> handleContainerClose(in);
            case ID_PLAYER_ACTION -> handlePlayerAction(in);
            case ID_INTERACT -> handleInteract(in);
            case ID_ANIMATE -> handleAnimate(in);
            default -> { /* other gameplay packet — nothing to answer yet */ }
        }
    }

    private void registerPlayer() {
        if (loggedIn || uuid == null || listener == null) return;
        loggedIn = true;
        listener.onLogin(this, uuid, username);
    }

    // ===== Inbound handlers =====

    private void handleMove(ByteBuf in) {
        in.readLong();                          // entity id (self) — ignored
        float x = in.readFloat();
        float y = in.readFloat() - EYE_HEIGHT;  // eye -> feet
        float z = in.readFloat();
        in.readFloat();                         // yaw
        float bodyYaw = in.readFloat();
        float pitch = in.readFloat();
        if (loggedIn && listener != null) {
            listener.onMove(this, x, y, z, bodyYaw, pitch);
        }
        if (chunkView != null) {
            chunkView.recenter(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4, chunkSink);
        }
    }

    private void handleText(ByteBuf in) {
        int type = in.readUnsignedByte();
        if (type == TEXT_TYPE_CHAT) {
            Mcpe014Codec.readString(in);        // source name — we use the server-side name
        }
        String message = Mcpe014Codec.readString(in);
        if (loggedIn && listener != null && !message.isEmpty()) {
            listener.onChat(this, message);
        }
    }

    private void handleRemoveBlock(ByteBuf in) {
        in.readLong();                          // entity id
        int x = in.readInt();
        int z = in.readInt();
        int y = in.readUnsignedByte();
        if (loggedIn && listener != null) {
            listener.onBlockChange(this, x, y, z, Blocks.AIR);
        }
    }

    private void handleUseItem(ByteBuf in) {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        int face = in.readUnsignedByte();
        in.skipBytes(6 * 4);                    // fx,fy,fz, posX,posY,posZ
        int state = readSlotState(in);
        if (!loggedIn || listener == null) {
            return;
        }
        // Right-click a block: if it's a chest, open it (suppress the placement this packet would be).
        if (listener.onUseBlock(this, x, y, z)) {
            return;
        }
        if (face < 6 && state != Blocks.AIR && Blocks.isKnown(Blocks.idOf(state))) {
            listener.onBlockChange(this,
                    x + FACE_DX[face], y + FACE_DY[face], z + FACE_DZ[face], state);
        }
    }

    /** Read a 0.14 Slot far enough for its canonical state; id<=0 means air. */
    /** A decoded 0.14 item slot: canonical state (0 = air) and stack count. */
    private record Slot(int state, int count) {}

    private static Slot readSlot(ByteBuf in) {
        if (in.readableBytes() < 2) return new Slot(Blocks.AIR, 0);
        int id = in.readShort();                // signed short; <= 0 = air
        if (id <= 0) return new Slot(Blocks.AIR, 0);
        int count = in.readUnsignedByte();
        int damage = in.readableBytes() >= 2 ? in.readShort() : 0;
        // trailing LE nbtLen + nbt (the item is the last field of these packets) is left unread
        return new Slot(Blocks.state(id, damage & 0x0F), count);
    }

    private static int readSlotState(ByteBuf in) {
        return readSlot(in).state();
    }

    @Override
    public void openContainer(int windowId, String title, int slots, int x, int y, int z) {
        // ContainerOpen (0xb5), type 0 = CHEST/CONTAINER; 0.14 carries the slot count and int coords.
        sendWrapped(b -> Mcpe014Packets.containerOpen(b, windowId, 0, slots, x, y, z));
    }

    @Override
    public void setWindowItems(int windowId, int[] states, int[] counts) {
        // ContainerSetContent (0xb9) for the chest window (just its own slots; the player inventory is
        // window 0). 0.14 crashes on an id it can't render, so anything outside the safe set becomes air.
        sendWrapped(b -> {
            b.writeByte(ID_CONTAINER_SET_CONTENT);
            b.writeByte(windowId);
            b.writeShort(states.length);
            for (int i = 0; i < states.length; i++) {
                int state = Pe014Blocks.supports(Blocks.idOf(states[i])) ? states[i] : Blocks.AIR;
                Mcpe014Packets.writeSlot(b, state, counts[i]);
            }
            b.writeShort(0); // hotbar-link count
        });
    }

    /** The client moved an item in a container (inbound ContainerSetSlot 0xb7 — client-authoritative). */
    private void handleContainerSetSlot(ByteBuf in) {
        int windowId = in.readUnsignedByte();
        int slot = in.readShort();
        in.readShort();                         // hotbarSlot — unused
        Slot item = readSlot(in);
        if (loggedIn && listener != null && slot >= 0) {
            listener.onContainerSetSlot(this, windowId, slot, item.state(), item.count());
        }
    }

    /** The client closed a container (inbound ContainerClose 0xb6). Clear state and echo the close back. */
    private void handleContainerClose(ByteBuf in) {
        int windowId = in.readUnsignedByte();
        if (loggedIn && listener != null) {
            listener.onWindowClose(this);
        }
        sendWrapped(b -> {
            b.writeByte(ID_CONTAINER_CLOSE);
            b.writeByte(windowId);
        });
    }

    private void handlePlayerAction(ByteBuf in) {
        in.readLong();                          // entity id
        int action = in.readInt();
        if (!loggedIn || listener == null) return;
        switch (action) {
            case ACTION_START_SNEAK -> listener.onSneak(this, true);
            case ACTION_STOP_SNEAK -> listener.onSneak(this, false);
            case ACTION_START_SPRINT -> listener.onSprint(this, true);
            case ACTION_STOP_SPRINT -> listener.onSprint(this, false);
            default -> { /* jump / break animation etc. not relayed */ }
        }
    }

    private void handleAnimate(ByteBuf in) {
        int action = in.readUnsignedByte();
        if (action == ANIMATE_SWING && loggedIn && listener != null) {
            listener.onSwingArm(this);
        }
    }

    /**
     * InteractPacket (0.14): {@code byte action} then a big-endian {@code long} target eid (= the
     * avatar's server entity id). A left-click is a melee attack — hand the target to the core to
     * resolve the victim and apply damage.
     */
    private void handleInteract(ByteBuf in) {
        int action = in.readUnsignedByte();
        long target = in.readLong();            // big-endian eid (protocol 45 is fixed-width BE)
        if (action == INTERACT_LEFT_CLICK && loggedIn && listener != null) {
            listener.onAttack(this, target);
        }
    }

    // ===== Join sequence =====

    private void sendLoginSequence() {
        Location spawn = world.getSpawnLocation();
        int sx = spawn.getBlockX(), sy = spawn.getBlockY(), sz = spawn.getBlockZ();
        float eyeY = (float) spawn.y() + EYE_HEIGHT;

        sendWrapped(b -> Mcpe014Packets.playStatus(b, PLAY_STATUS_LOGIN_SUCCESS));
        sendWrapped(b -> Mcpe014Packets.startGame(b,
                -1, 0, 1, joinGameMode().getId(), 0L, sx, sy, sz,
                (float) spawn.x(), eyeY, (float) spawn.z()));
        sendWrapped(b -> Mcpe014Packets.setTime(b, 0, true));
        sendWrapped(b -> Mcpe014Packets.setSpawnPosition(b, sx, sy, sz));
        sendWrapped(b -> Mcpe014Packets.setHealth(b, 20));
        sendWrapped(b -> Mcpe014Packets.setDifficulty(b, 2));
        // Creative menu: the 0.14-safe palette, sent as a zlib batch (it's too large to send raw, and
        // an id 0.14 can't render would crash the menu — hence the hard-limited Pe014Blocks list).
        sendBatched(b -> Mcpe014Packets.containerSetContent(b, WINDOW_ID_CREATIVE, Pe014Blocks.creativePalette(), 1));
    }

    private void streamWorldAndSpawn(int radius) {
        sendWrapped(b -> Mcpe014Packets.chunkRadiusUpdate(b, radius));

        Location spawn = world.getSpawnLocation();
        this.chunkView = new ChunkView(radius);
        chunkView.recenter(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, chunkSink);

        // doFirstSpawn: SetTime + Respawn + PlayStatus(PLAYER_SPAWN). Respawn y is eye-level.
        float eyeY = (float) spawn.y() + EYE_HEIGHT;
        sendWrapped(b -> Mcpe014Packets.setTime(b, 0, true));
        sendWrapped(b -> Mcpe014Packets.respawn(b, (float) spawn.x(), eyeY, (float) spawn.z()));
        sendWrapped(b -> Mcpe014Packets.playStatus(b, PLAY_STATUS_PLAYER_SPAWN));
    }

    // ===== Send helpers =====

    /** One packet, 0x8e-wrapped, reliable-ordered. */
    private void sendWrapped(Consumer<ByteBuf> body) {
        if (session.isClosed()) return;
        ByteBuf out = Unpooled.buffer();
        out.writeByte(WRAPPER);
        body.accept(out);
        session.send(out, RakNetReliability.RELIABLE_ORDERED);
    }

    /** One packet, zlib-batched (0x92) and 0x8e-wrapped — for packets too large to send raw. */
    private void sendBatched(Consumer<ByteBuf> body) {
        if (session.isClosed()) return;
        ByteBuf pkt = Unpooled.buffer();
        body.accept(pkt);
        byte[] pktBytes = new byte[pkt.readableBytes()];
        pkt.readBytes(pktBytes);
        pkt.release();

        byte[] batch = Mcpe014Batch.of(pktBytes);
        ByteBuf out = Unpooled.buffer(1 + batch.length);
        out.writeByte(WRAPPER);
        out.writeBytes(batch);
        session.send(out, RakNetReliability.RELIABLE_ORDERED);
    }

    /** One chunk column, FullChunkData in a 0x92 zlib batch. */
    private void sendChunk(int cx, int cz) {
        sendBatched(b -> {
            byte[] blob = Mcpe014ChunkSerializer.serialize(world, cx, cz);
            Mcpe014Packets.fullChunkDataHeader(b, cx, cz, blob.length);
            b.writeBytes(blob);
        });
    }
}
