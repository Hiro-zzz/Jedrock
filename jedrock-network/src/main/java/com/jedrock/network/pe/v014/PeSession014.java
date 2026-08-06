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

import java.util.Arrays;
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

    private static int maxViewRadius() {
        return com.jedrock.network.Pipeline.get().bedrock().v0_14().maxViewRadius();
    }

    private final RakNetServerSession session;
    private final ConnectionListener listener;
    /** The world this client is currently in — not final: a player can travel to another one. */
    private volatile World world;
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

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // MCPE 0.14 (protocol 45) predates the SetTitle packet — fall back to chat lines.
        if (title != null && !title.isEmpty()) {
            sendMessage(title);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            sendMessage(subtitle);
        }
    }

    @Override
    public void sendActionBar(String text) {
        if (text != null && !text.isEmpty()) {
            sendMessage(text);
        }
    }

    /**
     * The sidebar on 0.14: this era has no scoreboard either (it predates even 1.1.5's boss bar), so the
     * panel borrows the <b>popup</b> — the HUD field that shows a held item's name, displaced up the
     * screen — exactly as the 1.1.5 session does, over 0.14's own big-endian TextPacket. Title first, rows
     * newline-separated beneath it.
     *
     * <p>The popup fades by itself, so the core repaints it — see {@link #sidebarRepaintTicks()}.
     */
    @Override
    public void setSidebar(String title, String[] lines) {
        int raise = properties.peSidebarRaise();
        int shift = properties.peSidebarShift();
        String head = pad(title == null ? "" : title, shift);
        String body = joinSidebarLines(lines, raise, shift);
        sendWrapped(b -> Mcpe014Packets.popup(b, head, body));
    }

    @Override
    public void clearSidebar() {
        sendWrapped(b -> Mcpe014Packets.popup(b, "", ""));
    }

    @Override
    public int sidebarRepaintTicks() {
        return com.jedrock.network.Pipeline.get().bedrock().v0_14().sidebarRepaintTicks();
    }


    /**
     * Join the sidebar rows into the popup's second string, capped at the api's line limit, padded into
     * place by the {@code pe.sidebar.raise} / {@code pe.sidebar.shift} knobs — see the 1.1.5 session's
     * copy for what the padding does and why the pad rows are a space rather than empty. (The two
     * Bedrock eras deliberately share no wire code.)
     */
    static String joinSidebarLines(String[] lines, int raise, int shift) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        int count = Math.min(lines.length, com.jedrock.api.player.Player.SIDEBAR_MAX_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < -raise; i++) {
            sb.append(" \n");
        }
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(pad(lines[i] == null ? "" : lines[i], shift));
        }
        for (int i = 0; i < raise; i++) {
            sb.append("\n ");
        }
        return sb.toString();
    }

    /** Pad one row sideways: {@code shift} spaces on the left, or {@code -shift} on the right. */
    static String pad(String line, int shift) {
        if (shift == 0 || line.isEmpty()) {
            return line;
        }
        String spaces = " ".repeat(Math.abs(shift));
        return shift > 0 ? spaces + line : line + spaces;
    }

    @Override
    public void clearTitle() {
        // nothing to clear in the chat fallback
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
        // 0.14 predates some of the canonical mobs; showing it an id it doesn't know risks the same
        // crash its block palette does, so an unsupported mob is simply not rendered for this client.
        if (!Pe014Entities.supports(type)) {
            return;
        }
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
    public void moveEntity(long entityId, double x, double y, double z,
                           float bodyYaw, float pitch, float headYaw) {
        sendWrapped(b -> Mcpe014Packets.moveEntity(b, entityId,
                (float) x, (float) y + EYE_HEIGHT, (float) z, bodyYaw, pitch, headYaw));
    }

    @Override
    public void setEntityHeadYaw(long entityId, double x, double y, double z,
                                 float bodyYaw, float pitch, float headYaw) {
        // As on 1.1.5: this era has no head-only packet either, so a glance restates the whole pose.
        moveEntity(entityId, x, y, z, bodyYaw, pitch, headYaw);
    }

    @Override
    public void removeEntity(long entityId) {
        sendWrapped(b -> Mcpe014Packets.removeEntity(b, entityId));
    }

    /** A 0.14 day is 19200 ticks, not 24000 — the canonical time is rescaled on the way out. */
    private static final int DAY_TICKS_014 = 19200;

    /**
     * The entity id this client knows itself by: {@code 0}, which is what StartGame hands it (unlike
     * 1.1.5, where the player is entity 1). Anything addressed at the player themselves uses it.
     */
    private static final long SELF_ENTITY_ID = 0L;

    @Override
    public void sendTime(long timeOfDay, boolean cycling) {
        // SetTime(0x94): int32 time, byte started. Ground truth PMMP at CURRENT_PROTOCOL 45, whose value
        // is time/TIME_FULL*19200 — this era's day is shorter than the 24000 every other target uses, so
        // the same o'clock is a different number here. The `started` byte is this wire's freeze switch,
        // which is more than 1.1.5 offers and exactly what Java's negative time means.
        int scaled = (int) (Math.floorMod(timeOfDay, 24000L) * DAY_TICKS_014 / 24000L);
        sendWrapped(b -> Mcpe014Packets.setTime(b, scaled, cycling));
    }

    @Override
    public void sendEffect(com.jedrock.api.entity.Effect effect, int amplifier,
                           int durationTicks, boolean particles) {
        // An effect this era never had is simply not sent: there is no placeholder on this client, and an
        // unknown id crashes it the way an unknown block does. The core still applies whatever it owns
        // (instant health and damage are a number, not a picture), so nothing is silently lost here that
        // this client could have shown.
        if (!Pe014Effects.supports(effect)) {
            return;
        }
        sendWrapped(b -> Mcpe014Packets.mobEffect(b, SELF_ENTITY_ID, Mcpe014Packets.EFFECT_EVENT_ADD,
                effect.getId(), amplifier, particles, durationTicks));
    }

    @Override
    public void removeEffect(com.jedrock.api.entity.Effect effect) {
        if (!Pe014Effects.supports(effect)) {
            return;
        }
        sendWrapped(b -> Mcpe014Packets.mobEffect(b, SELF_ENTITY_ID, Mcpe014Packets.EFFECT_EVENT_REMOVE,
                effect.getId(), 0, false, 0));
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

    /**
     * Move this client into another world — with no dimension packet, because 0.14 has none this project
     * has ground-truthed, and this is the client that crashes on a guessed id. So the world changes the
     * only way it safely can: re-send every chunk in view from the new world, then put the player down.
     * The blocks, the biome tint and the spawn are all the destination's; the sky is not. A nether looks
     * like an overworld with netherrack in it, which is the honest half of the feature this era can have.
     */
    @Override
    public void switchWorld(World target, double x, double y, double z, float yaw, float pitch,
                            GameMode mode) {
        this.world = target;
        if (chunkView != null) {
            chunkView.forgetAll();
            chunkView.recenter(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4, chunkSink);
        }
        teleport(x, y, z, yaw, pitch);
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
    public void showHeldItem(long entityId, int state) {
        sendWrapped(b -> Mcpe014Packets.mobEquipment(b, entityId, safeState(state)));
    }

    @Override
    public void spawnItemEntity(long entityId, java.util.UUID uuid, double x, double y, double z, int state) {
        // The 0.14 AddItemEntity carries no metadata, so immobility follows in its own SetEntityData —
        // without it the client drifts the prop around instead of leaving it where it was placed.
        sendWrapped(b -> Mcpe014Packets.addItemEntity(b, entityId, x, y, z, safeState(state)));
        sendWrapped(b -> Mcpe014Packets.setEntityNoAi(b, entityId));
    }

    @Override
    public void spawnFallingBlock(long entityId, java.util.UUID uuid, double x, double y, double z, int state) {
        int id = Blocks.idOf(state);
        if (!Pe014Blocks.supports(id)) {
            return; // the same crash gate the chunks use: never send 0.14 a block it can't render
        }
        sendWrapped(b -> Mcpe014Packets.addFallingBlock(b, entityId, x, y, z,
                id | (Blocks.metaOf(state) << 8)));
    }

    @Override
    public void showArmor(long entityId, int helmet, int chestplate, int leggings, int boots) {
        sendWrapped(b -> Mcpe014Packets.mobArmorEquipment(b, entityId,
                safeState(helmet), safeState(chestplate), safeState(leggings), safeState(boots)));
    }

    @Override
    public void sendOwnArmor(int helmet, int chestplate, int leggings, int boots) {
        sendWrapped(b -> Mcpe014Packets.ownArmor(b,
                safeState(helmet), safeState(chestplate), safeState(leggings), safeState(boots)));
    }

    @Override
    public int getPing() {
        // RakNet keeps its own connected-ping estimate per session — no game-layer probe needed.
        return (int) Math.min(Integer.MAX_VALUE, session.getPing());
    }

    /** LevelEvent rain/thunder intensity — the mid value the era's servers used; stops send 0. */
    private static final int WEATHER_INTENSITY = 10000;

    @Override
    public void sendWeather(com.jedrock.api.world.Weather weather) {
        // Same LevelEvent 3001-series as 1.1.5 (weather landed in PE 0.12), big-endian at protocol 45.
        switch (weather) {
            case CLEAR -> {
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3003, 0, 0, 0, 0));
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3004, 0, 0, 0, 0));
            }
            case RAIN -> {
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3001, 0, 0, 0, WEATHER_INTENSITY));
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3004, 0, 0, 0, 0));
            }
            case THUNDER -> {
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3001, 0, 0, 0, WEATHER_INTENSITY));
                sendWrapped(b -> Mcpe014Packets.levelEvent(b, 3002, 0, 0, 0, WEATHER_INTENSITY));
            }
        }
    }

    @Override
    public void playSound(com.jedrock.api.world.Sound sound, double x, double y, double z, float volume, float pitch) {
        // 0.14 has LevelEvent only; sounds it predates map to the closest available id (see PeEffects).
        // The data field carries pitch×1000 (PMMP GenericSound, identical in the 0.14 tree); no volume slot.
        int evid = com.jedrock.network.pe.PeEffects.levelEventSound014(sound);
        sendWrapped(b -> Mcpe014Packets.levelEvent(b, evid, x, y, z, Math.round(pitch * 1000f)));
    }


    @Override
    public void spawnParticle(com.jedrock.api.world.Particle particle, double x, double y, double z,
                              int count, double spread) {
        int evid = com.jedrock.network.pe.PeEffects.ADD_PARTICLE_MASK
                | com.jedrock.network.pe.PeEffects.particle014(particle);
        int n = Math.min(Math.max(1, count),
                com.jedrock.network.Pipeline.get().bedrock().v0_14().maxParticleBurst());
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            final double px = x + offset(rnd, spread), py = y + offset(rnd, spread), pz = z + offset(rnd, spread);
            sendWrapped(b -> Mcpe014Packets.levelEvent(b, evid, px, py, pz, 0));
        }
    }

    /** A uniform scatter in ±spread (0 spread → exactly at the point). */
    private static double offset(java.util.concurrent.ThreadLocalRandom rnd, double spread) {
        return spread <= 0 ? 0 : (rnd.nextDouble() * 2.0 - 1.0) * spread;
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
        int bodyLen = n - offset - 1;
        // Offer the raw packet to any tap; a cancel drops it before the session handles it.
        if (listener != null && listener.hasPacketTaps()) {
            byte[] payload = Arrays.copyOfRange(data, offset + 1, n);
            if (listener.onInboundPacket(this, id, payload)) {
                return; // cancelled
            }
        }
        ByteBuf body = Unpooled.wrappedBuffer(data, offset + 1, bodyLen);
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
                int radius = Math.clamp(requested, 2, maxViewRadius());
                streamWorldAndSpawn(radius);
                registerPlayer();
            }
            case ID_MOVE_PLAYER -> handleMove(in);
            case ID_TEXT -> handleText(in);
            case ID_REMOVE_BLOCK -> handleRemoveBlock(in);
            case ID_USE_ITEM -> handleUseItem(in);
            case ID_CONTAINER_SET_SLOT -> handleContainerSetSlot(in);
            case ID_MOB_EQUIPMENT -> handleMobEquipment(in);
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
        if (face < 6 && state != Blocks.AIR) {
            int tx = x + FACE_DX[face], ty = y + FACE_DY[face], tz = z + FACE_DZ[face];
            if (Blocks.isKnown(Blocks.idOf(state))) {
                listener.onBlockChange(this, tx, ty, tz, state);
            } else {
                // An item "placement" (a door, a bed…): nothing places server-side — items are inert —
                // but the client may have drawn something optimistically, so correct it with the truth.
                sendBlockChange(tx, ty, tz, world.getBlockId(tx, ty, tz));
            }
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

    /**
     * The crash gate for anything item-shaped sent to the 0.14 client (inventory, chests): a block id
     * outside the renderable set — or an item id outside the classic 0.14 set — becomes air. A richer
     * edition's exotics (an ender pearl from the 1.1.5 menu, an elytra from a JE client) thus show as
     * an empty slot here instead of crashing this client.
     */
    private static int safeState(int state) {
        int id = Blocks.idOf(state);
        boolean renderable = id <= Blocks.MAX_LEGACY_ID ? Pe014Blocks.supports(id) : Pe014Items.supports(id);
        return renderable ? state : Blocks.AIR;
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
                Mcpe014Packets.writeSlot(b, safeState(states[i]), counts[i]);
            }
            b.writeShort(0); // hotbar-link count
        });
    }

    @Override
    public void setInventory(int[] states, int[] counts) {
        // The player's own inventory (window 0) with the 9-entry hotbar-link table — the core's 36
        // storage slots (0-8 hotbar / 9-35 main) map 1:1 onto the 0.14 window.
        int[] safe = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            safe[i] = safeState(states[i]);
        }
        sendWrapped(b -> Mcpe014Packets.playerInventory(b, safe, counts));
    }

    @Override
    public void setInventory(int[] states, int[] counts, com.jedrock.api.item.ItemDisplay[] display) {
        int[] safe = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            safe[i] = safeState(states[i]);
        }
        sendWrapped(b -> Mcpe014Packets.playerInventory(b, safe, counts, display));
    }

    @Override
    public void setInventorySlot(int slot, int state, int count) {
        setInventorySlot(slot, state, count, null);
    }

    @Override
    public void setInventorySlot(int slot, int state, int count, com.jedrock.api.item.ItemDisplay display) {
        sendWrapped(b -> Mcpe014Packets.containerSetSlot(
                b, Mcpe014Packets.WINDOW_ID_PLAYER, slot, safeState(state), count, display));
    }

    /**
     * The client switched (or re-announced) its held item — MobEquipment inbound. Layout mirrors
     * {@link Mcpe014Packets#mobEquipment}; only the selected hotbar slot matters (the item claim is
     * ignored — the server-side inventory is the truth the relay reads from).
     */
    private void handleMobEquipment(ByteBuf in) {
        in.readLong();                              // eid (the sender's own)
        readSlot(in);                               // item claim — skipped
        in.readByte();                              // slot
        int selectedSlot = in.readUnsignedByte();
        if (loggedIn && listener != null && selectedSlot < 9) {
            listener.onHeldSlotChange(this, selectedSlot);
        }
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

    /** The world this client serializes chunks from — the join's, then whatever it travelled to. */
    @Override
    public World getWorld() {
        return world;
    }

    private void sendLoginSequence() {
        // The world this client joins into: the one it logged out in, if the server remembers. Settled
        // before StartGame, so the blocks, the spawn and the biome tint are the right world's from the
        // first packet. The dimension byte below stays 0: this era has no dimension change this project
        // has ground-truthed, and a nether joined under an overworld sky is the same honest half of the
        // feature 0.14 gets when travelling (see switchWorld).
        World remembered = listener != null ? listener.worldFor(uuid) : null;
        if (remembered != null) {
            this.world = remembered;
        }
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
        // out = [WRAPPER][id][fields]; offer (id, fields) to any outbound tap.
        if (listener != null && listener.hasPacketTaps() && out.readableBytes() >= 2) {
            int id = out.getByte(1) & 0xFF;
            byte[] payload = new byte[out.readableBytes() - 2];
            out.getBytes(2, payload);
            if (listener.onOutboundPacket(this, id, payload)) {
                out.release();
                return; // cancelled
            }
        }
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

        // pktBytes = [id][fields]; offer to any outbound tap before compressing/framing.
        if (listener != null && listener.hasPacketTaps() && pktBytes.length >= 1) {
            int id = pktBytes[0] & 0xFF;
            byte[] payload = Arrays.copyOfRange(pktBytes, 1, pktBytes.length);
            if (listener.onOutboundPacket(this, id, payload)) {
                return; // cancelled
            }
        }

        byte[] batch = Mcpe014Batch.of(pktBytes);
        ByteBuf out = Unpooled.buffer(1 + batch.length);
        out.writeByte(WRAPPER);
        out.writeBytes(batch);
        session.send(out, RakNetReliability.RELIABLE_ORDERED);
    }

    @Override
    public void sendRawPacket(int packetId, byte[] payload) {
        // Frame as a normal 0x8e-wrapped packet ([id byte][payload]); it's tapped like any other send.
        sendWrapped(b -> {
            b.writeByte(packetId);
            if (payload != null) {
                b.writeBytes(payload);
            }
        });
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
