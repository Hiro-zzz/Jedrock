package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Clientbound Spawn Mob (0x03) for JE 1.12.2 — spawns a non-player entity (a puppet). Unlike a player
 * avatar this needs no tab-list entry; the client renders it straight from the numeric {@code type} id
 * (the classic pre-1.13 mob id). Position is doubles, rotation is angle-bytes, and the trailing metadata
 * block is empty by default (all defaults) — a static visual with no simulated state.
 *
 * <p>The client never simulates a spawned entity's physics (it only interpolates towards the positions the
 * server sends), so a puppet stays exactly where it is put without the server ticking anything.
 */
public final class ClientboundSpawnMob implements ClientboundPacket {

    /** The classic mob id of an armor stand — the body a hologram line's text hangs on. */
    private static final int ARMOR_STAND_TYPE_ID = 30;

    /**
     * Where to put a hologram line's armor stand so its name lands on the requested y. A marker stand has
     * no bounding box, and the client draws the name a little above the entity. Cosmetic and approximate —
     * worth a nudge against a live client.
     */
    private static final double ARMOR_STAND_NAME_OFFSET = -0.5;

    private final int entityId;
    private final UUID uuid;
    private final int type;
    private final double x, y, z; // y = feet
    private final float yaw, pitch;
    private final Consumer<ByteBuf> metadata;

    public ClientboundSpawnMob(int entityId, UUID uuid, int type,
                               double x, double y, double z, float yaw, float pitch) {
        this(entityId, uuid, type, x, y, z, yaw, pitch, buf -> {});
    }

    private ClientboundSpawnMob(int entityId, UUID uuid, int type,
                                double x, double y, double z, float yaw, float pitch,
                                Consumer<ByteBuf> metadata) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.metadata = metadata;
    }

    /** One line of a hologram: an invisible marker armor stand whose custom name is the floating text. */
    public static ClientboundSpawnMob textLine(int entityId, UUID uuid,
                                               double x, double y, double z, String text) {
        return new ClientboundSpawnMob(entityId, uuid, ARMOR_STAND_TYPE_ID,
                x, y + ARMOR_STAND_NAME_OFFSET, z, 0f, 0f,
                buf -> ClientboundEntityMetadata.writeTextLineEntries(buf, text));
    }

    @Override
    public void write(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, entityId);
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        ByteBufUtils.writeVarInt(buf, type);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        ByteBufUtils.writeAngle(buf, yaw);   // yaw
        ByteBufUtils.writeAngle(buf, pitch); // pitch
        ByteBufUtils.writeAngle(buf, yaw);   // head pitch
        buf.writeShort(0);                   // velocity x
        buf.writeShort(0);                   // velocity y
        buf.writeShort(0);                   // velocity z
        metadata.accept(buf);
        buf.writeByte(0xFF);                 // entity metadata: terminator
    }

    @Override
    public int getPacketId() {
        return 0x03;
    }
}
