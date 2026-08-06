package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;
import com.jedrock.network.EntityTypeIds;
import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import static com.jedrock.network.pe.McpeProtocol.*;

/**
 * Clientbound MCPE 1.1.5 (protocol 113) body encoders — every packet {@link PeSession} sends, written as
 * a pure function of its arguments. Each one writes {@code [packetId][body]} into the buffer; the caller
 * wraps it in the {@code 0xFE} zlib batch.
 *
 * <p>This is the 1.1.5 counterpart of {@code Mcpe014Packets}, and it exists for the same reason: an
 * encoder that touches no session state can be read against PocketMine field by field and pinned by a
 * unit test that needs no client, no socket and no login — which is how every layout here was verified
 * (PMMP at tag {@code 1.7dev-27}, the tree whose {@code CURRENT_PROTOCOL} is 113). What is left in the
 * session is the part that genuinely has state: the RakNet callbacks, the join flow, and the decisions.
 *
 * <p>Ids, metadata indices and flag bits live in {@link McpeProtocol}; item slots and UUIDs are framed by
 * {@link McpeCodec}. Nothing here reads the world, the config or the player — an encoder that needs the
 * player's own entity id takes it as an argument.
 */
final class McpePackets {

    private McpePackets() {}

    // ===== Chat and player-facing text =====

    /** A raw (system) chat line. */
    static void text(ByteBuf b, String message) {
        ByteBufUtils.writeVarInt(b, ID_TEXT);
        b.writeByte(TEXT_TYPE_RAW);
        ByteBufUtils.writeString(b, message);
    }

    /**
     * A TextPacket popup. Layout, verbatim from PMMP {@code TextPacket} at protocol 113: {@code type}
     * (byte) then, for {@code TYPE_POPUP}, two strings — {@code source} (the popup line) and
     * {@code message} (the subtitle under it). Both are the era's unsigned-varint-length strings.
     */
    static void popup(ByteBuf b, String source, String message) {
        ByteBufUtils.writeVarInt(b, ID_TEXT);
        b.writeByte(TEXT_TYPE_POPUP);
        ByteBufUtils.writeString(b, source == null ? "" : source);
        ByteBufUtils.writeString(b, message == null ? "" : message);
    }

    /**
     * One SetTitle (0x59) packet. Layout, verbatim from PMMP {@code SetTitlePacket} at protocol 113:
     * {@code type} (signed varint), {@code text} (string), then {@code fadeIn} / {@code stay} /
     * {@code fadeOut} (signed varints, in ticks). {@code type} is one of the {@code TITLE_TYPE_*} values.
     */
    static void setTitle(ByteBuf b, int type, String text, int fadeIn, int stay, int fadeOut) {
        ByteBufUtils.writeVarInt(b, ID_SET_TITLE);
        ByteBufUtils.writeSignedVarInt(b, type);
        ByteBufUtils.writeString(b, text == null ? "" : text);
        ByteBufUtils.writeSignedVarInt(b, fadeIn);
        ByteBufUtils.writeSignedVarInt(b, stay);
        ByteBufUtils.writeSignedVarInt(b, fadeOut);
    }

    // ===== Boss bar (PMMP BossEventPacket @ 113) =====

    static final int BOSS_SHOW = 0;
    static final int BOSS_HIDE = 2;
    static final int BOSS_HEALTH = 4;
    static final int BOSS_TITLE = 5;

    /**
     * Show the bar. TYPE_SHOW falls through to the texture fields in PMMP, so the packet carries the
     * title, the fill, an unknown short, then colour and overlay — all of them, in that order. The bar
     * binds to an entity id; the player's own works, so no extra entity has to be spawned.
     */
    static void bossEventShow(ByteBuf b, long bossEntityId, String title, float progress, int color) {
        ByteBufUtils.writeVarInt(b, ID_BOSS_EVENT);
        ByteBufUtils.writeSignedVarLong(b, bossEntityId);  // putEntityUniqueId
        ByteBufUtils.writeVarInt(b, BOSS_SHOW);
        ByteBufUtils.writeString(b, title);
        b.writeFloatLE(progress);
        b.writeShortLE(0);                                 // unknownShort
        ByteBufUtils.writeVarInt(b, color);
        ByteBufUtils.writeVarInt(b, 0);                    // overlay
    }

    /** Update the fill of a bar that is already up. */
    static void bossEventHealth(ByteBuf b, long bossEntityId, float progress) {
        ByteBufUtils.writeVarInt(b, ID_BOSS_EVENT);
        ByteBufUtils.writeSignedVarLong(b, bossEntityId);
        ByteBufUtils.writeVarInt(b, BOSS_HEALTH);
        b.writeFloatLE(progress);
    }

    /** Retitle a bar that is already up (a colour change after the first show is ignored by the client). */
    static void bossEventTitle(ByteBuf b, long bossEntityId, String title) {
        ByteBufUtils.writeVarInt(b, ID_BOSS_EVENT);
        ByteBufUtils.writeSignedVarLong(b, bossEntityId);
        ByteBufUtils.writeVarInt(b, BOSS_TITLE);
        ByteBufUtils.writeString(b, title);
    }

    /** Take the bar away. */
    static void bossEventHide(ByteBuf b, long bossEntityId) {
        ByteBufUtils.writeVarInt(b, ID_BOSS_EVENT);
        ByteBufUtils.writeSignedVarLong(b, bossEntityId);
        ByteBufUtils.writeVarInt(b, BOSS_HIDE);
    }

    // ===== Sounds, particles and weather =====

    /**
     * One LevelEvent (0x1a) packet. Layout, verbatim from PMMP {@code LevelEventPacket} at protocol 113:
     * event id (signed varint — a 1000-series sound or {@code 0x4000 | particle type}), position
     * (Vector3f = 3 LE floats), data (signed varint — pitch×1000 for sounds, 0 for particles).
     */
    static void levelEvent(ByteBuf b, int evid, double x, double y, double z, int data) {
        ByteBufUtils.writeVarInt(b, ID_LEVEL_EVENT);
        ByteBufUtils.writeSignedVarInt(b, evid);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);
        b.writeFloatLE((float) z);
        ByteBufUtils.writeSignedVarInt(b, data);
    }

    /**
     * One LevelSoundEvent (0x19) packet. Layout, verbatim from PMMP {@code LevelSoundEventPacket} at
     * protocol 113: sound (byte), position (Vector3f), extraData (signed varint, -1 = none), pitch
     * (signed varint, 1 = normal), isBabyMob (bool), disableRelativeVolume (bool).
     */
    static void levelSoundEvent(ByteBuf b, int soundId, double x, double y, double z) {
        ByteBufUtils.writeVarInt(b, ID_LEVEL_SOUND_EVENT);
        b.writeByte(soundId);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);
        b.writeFloatLE((float) z);
        ByteBufUtils.writeSignedVarInt(b, -1);
        ByteBufUtils.writeSignedVarInt(b, 1);
        b.writeBoolean(false);
        b.writeBoolean(false);
    }

    // ===== Avatars (the PlayerList entry carries the skin the avatar renders with) =====

    /** A PlayerList ADD entry — five fields at 113: uuid, unique id, name, skin id, skin data. */
    static void playerListAdd(ByteBuf b, UUID uuid, long entityId, String name,
                              String skinId, byte[] skinData) {
        ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
        b.writeByte(PLAYER_LIST_ADD);
        ByteBufUtils.writeVarInt(b, 1);                    // entry count
        McpeCodec.writeUuid(b, uuid);
        ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
        ByteBufUtils.writeString(b, name);
        ByteBufUtils.writeString(b, skinId);               // skin id / geometry
        ByteBufUtils.writeByteArray(b, skinData);          // skin RGBA texture
    }

    /** A PlayerList REMOVE entry. */
    static void playerListRemove(ByteBuf b, UUID uuid) {
        ByteBufUtils.writeVarInt(b, ID_PLAYER_LIST);
        b.writeByte(PLAYER_LIST_REMOVE);
        ByteBufUtils.writeVarInt(b, 1);
        McpeCodec.writeUuid(b, uuid);
    }

    /**
     * Spawn another player's avatar. {@code y} is feet. The metadata carries the flags long (nametag
     * always visible) plus the nametag string, so the name floats above the avatar as it does on Java.
     */
    static void addPlayer(ByteBuf b, UUID uuid, String name, long entityId,
                          double x, double y, double z, float yaw, float pitch) {
        ByteBufUtils.writeVarInt(b, ID_ADD_PLAYER);
        McpeCodec.writeUuid(b, uuid);
        ByteBufUtils.writeString(b, name);
        ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
        ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);                         // AddPlayer takes feet y
        b.writeFloatLE((float) z);
        b.writeFloatLE(0f);                                // motion x
        b.writeFloatLE(0f);                                // motion y
        b.writeFloatLE(0f);                                // motion z
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);                               // head yaw
        b.writeFloatLE(yaw);
        ByteBufUtils.writeSignedVarInt(b, 0);              // held item: air
        ByteBufUtils.writeVarInt(b, 2);                    // metadata entry count
        flagsEntry(b, BASE_ENTITY_FLAGS);
        nameTagEntry(b, name);
    }

    // ===== Entities: puppets, holograms and props =====

    /**
     * An AddEntity body (0x0D): entity ids, the MCPE entity type, feet position, motion, rotation (pitch
     * before yaw), then attributes, the metadata dictionary and entity links. Verbatim from PocketMine-MP
     * at protocol 113 ({@code AddEntityPacket::encodePayload}).
     */
    static void addEntity(ByteBuf b, long entityId, int typeId,
                          double x, double y, double z, float yaw, float pitch,
                          Consumer<ByteBuf> metadata) {
        ByteBufUtils.writeVarInt(b, ID_ADD_ENTITY);
        ByteBufUtils.writeSignedVarLong(b, entityId);      // entity unique id
        ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
        ByteBufUtils.writeVarInt(b, typeId);               // entity type (uvarint, MCPE id)
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);                         // feet
        b.writeFloatLE((float) z);
        b.writeFloatLE(0f);                                // motion x
        b.writeFloatLE(0f);                                // motion y
        b.writeFloatLE(0f);                                // motion z
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);
        ByteBufUtils.writeVarInt(b, 0);                    // attributes: none
        metadata.accept(b);                                // metadata dictionary (count + entries)
        ByteBufUtils.writeVarInt(b, 0);                    // entity links: none
    }

    /**
     * A puppet: an ordinary mob carrying the nametag-visibility flags from birth, so a later
     * {@link #setEntityNameTag} shows up without re-sending the flags (they share one DATA_FLAGS long).
     */
    static void addPuppet(ByteBuf b, long entityId, int typeId,
                          double x, double y, double z, float yaw, float pitch) {
        addEntity(b, entityId, typeId, x, y, z, yaw, pitch, meta -> {
            ByteBufUtils.writeVarInt(meta, 1);             // metadata entry count
            flagsEntry(meta, BASE_ENTITY_FLAGS);
        });
    }

    /**
     * One hologram line: an item entity with no item — nothing renders but the name floating where the
     * body would be, immobile so it can't be nudged. PocketMine's own floating-text hack, which
     * deliberately does <em>not</em> set the invisible flag at protocol 113: an item entity that was never
     * given an item draws nothing anyway.
     */
    static void addTextLine(ByteBuf b, long entityId, double x, double y, double z, String text) {
        long flags = BASE_ENTITY_FLAGS | (1L << DATA_FLAG_IMMOBILE_BIT);
        addEntity(b, entityId, ITEM_ENTITY_TYPE_ID, x, y + TEXT_LINE_Y_OFFSET, z, 0f, 0f,
                meta -> {
                    ByteBufUtils.writeVarInt(meta, 2);     // metadata entry count
                    flagsEntry(meta, flags);
                    nameTagEntry(meta, text);
                });
    }

    /**
     * An AddItemEntity (0x0f) body, verbatim from PMMP at protocol 113: unique id (signed varlong) and
     * runtime id (unsigned varlong) — both the entity id here — the item Slot, the position and a zero
     * speed Vector3f, then metadata. The metadata carries the immobile flag, the same lever holograms use
     * so a prop can never drift or be nudged.
     */
    static void addItemEntity(ByteBuf b, long entityId, double x, double y, double z, int state) {
        ByteBufUtils.writeVarInt(b, ID_ADD_ITEM_ENTITY);
        ByteBufUtils.writeSignedVarLong(b, entityId);
        ByteBufUtils.writeVarLong(b, entityId);
        McpeCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        b.writeFloatLE((float) x); b.writeFloatLE((float) y); b.writeFloatLE((float) z);
        b.writeFloatLE(0f); b.writeFloatLE(0f); b.writeFloatLE(0f); // speed: none, it's a prop
        ByteBufUtils.writeVarInt(b, 1);                             // one metadata entry
        flagsEntry(b, BASE_ENTITY_FLAGS | (1L << DATA_FLAG_IMMOBILE_BIT));
    }

    /**
     * A full-size block prop: an ordinary AddEntity of PMMP's FallingSand type, with the block it renders
     * riding in DATA_VARIANT (index 2 at protocol 113) as {@code id | (meta << 8)}. Immobile, so it hangs.
     */
    static void addFallingBlock(ByteBuf b, long entityId, double x, double y, double z, int state) {
        int blockInfo = Blocks.idOf(state) | (Blocks.metaOf(state) << 8);
        addEntity(b, entityId, EntityTypeIds.BEDROCK_FALLING_BLOCK, x, y, z, 0f, 0f,
                meta -> {
                    ByteBufUtils.writeVarInt(meta, 2);              // two metadata entries
                    flagsEntry(meta, BASE_ENTITY_FLAGS | (1L << DATA_FLAG_IMMOBILE_BIT));
                    ByteBufUtils.writeVarInt(meta, DATA_VARIANT_INDEX);
                    ByteBufUtils.writeVarInt(meta, DATA_TYPE_INT);
                    ByteBufUtils.writeSignedVarInt(meta, blockInfo);
                });
    }

    /** Despawn any entity (an avatar, a puppet or a prop) by runtime id. */
    /**
     * SetTime (0x0a): one signed VarInt, the time of day. Ground truth PocketMine-MP at 1.7dev-27
     * (CURRENT_PROTOCOL 113), whose {@code encodePayload} is a single {@code putVarInt($this->time)} —
     * and PMMP's {@code putVarInt} is the zigzag form, which is this codebase's writeSignedVarInt.
     *
     * <p>There is no "and keep counting" flag on this wire; the client advances the sky on its own and
     * is corrected the next time it is told. Freezing therefore has to be re-sent, unlike on Java where
     * a negative time says it once.
     */
    static void setTime(ByteBuf b, long timeOfDay) {
        ByteBufUtils.writeVarInt(b, ID_SET_TIME);
        ByteBufUtils.writeSignedVarInt(b, (int) timeOfDay);
    }

    /** MobEffect events, verbatim from PMMP's {@code MobEffectPacket} at protocol 113. */
    static final int EFFECT_EVENT_ADD = 1;
    static final int EFFECT_EVENT_MODIFY = 2;
    static final int EFFECT_EVENT_REMOVE = 3;

    /**
     * MobEffect (0x1D): {@code entityRuntimeId (uvarlong)}, {@code eventId (byte)}, then
     * {@code effectId}, {@code amplifier}, a {@code particles} bool and {@code duration} — the three
     * numbers all <b>signed</b> (zigzag) varints, as {@code putVarInt} writes them.
     *
     * <p>Removing one is the same packet with {@link #EFFECT_EVENT_REMOVE}, where only the effect id is
     * read — the rest is written anyway, since the decoder reads the whole body regardless.
     */
    static void mobEffect(ByteBuf b, long runtimeId, int event, int effectId, int amplifier,
                          boolean particles, int durationTicks) {
        ByteBufUtils.writeVarInt(b, ID_MOB_EFFECT);
        ByteBufUtils.writeVarLong(b, runtimeId);
        b.writeByte(event);
        ByteBufUtils.writeSignedVarInt(b, effectId);
        ByteBufUtils.writeSignedVarInt(b, amplifier);
        b.writeByte(particles ? 1 : 0);
        ByteBufUtils.writeSignedVarInt(b, durationTicks);
    }

    static void removeEntity(ByteBuf b, long entityId) {
        ByteBufUtils.writeVarInt(b, ID_REMOVE_ENTITY);
        ByteBufUtils.writeSignedVarLong(b, entityId);
    }

    /**
     * MoveEntity (0x12): runtime id, feet position, then byte-angle pitch / yaw / headYaw and a flags
     * byte. Unlike {@link #movePlayer} (player-only) this addresses any entity runtime id.
     */
    static void moveEntity(ByteBuf b, long entityId, double x, double y, double z, float yaw, float pitch) {
        moveEntity(b, entityId, x, y, z, yaw, pitch, yaw);
    }

    /**
     * As above, aiming the head separately from the body. The field has always been on this wire; what
     * used to go in it was the body yaw a second time, so nothing here could look anywhere but forwards.
     */
    static void moveEntity(ByteBuf b, long entityId, double x, double y, double z,
                           float bodyYaw, float pitch, float headYaw) {
        ByteBufUtils.writeVarInt(b, ID_MOVE_ENTITY);
        ByteBufUtils.writeVarLong(b, entityId);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) y);                         // feet
        b.writeFloatLE((float) z);
        ByteBufUtils.writeAngle(b, pitch);
        ByteBufUtils.writeAngle(b, bodyYaw);
        ByteBufUtils.writeAngle(b, headYaw);               // head yaw
        b.writeByte(0);                                    // flags (on-ground / teleport)
    }

    /**
     * MovePlayer (0x13): a player avatar's position, in <b>eye</b> y (the caller adds
     * {@link McpeProtocol#EYE_HEIGHT}). {@code mode} 0 interpolates the move; {@code MOVE_MODE_TELEPORT}
     * is a server-forced reposition, which is what a snap-back uses.
     */
    static void movePlayer(ByteBuf b, long entityId, double x, double eyeY, double z,
                           float yaw, float pitch, int mode) {
        ByteBufUtils.writeVarInt(b, ID_MOVE_PLAYER);
        ByteBufUtils.writeVarLong(b, entityId);
        b.writeFloatLE((float) x);
        b.writeFloatLE((float) eyeY);
        b.writeFloatLE((float) z);
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);                               // head yaw
        b.writeFloatLE(yaw);
        b.writeByte(mode);
        b.writeBoolean(true);                              // on ground
        ByteBufUtils.writeVarLong(b, 0);                   // riding runtime id: none
    }

    // ===== Entity metadata =====

    /** Only the nametag string: metadata is a merge, and the visibility bits rode in on spawn. */
    static void setEntityNameTag(ByteBuf b, long entityId, String nameTag) {
        ByteBufUtils.writeVarInt(b, ID_SET_ENTITY_DATA);
        ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
        ByteBufUtils.writeVarInt(b, 1);                    // metadata entry count
        nameTagEntry(b, nameTag);
    }

    /**
     * The whole DATA_FLAGS long. It holds both the canonical flags and the nametag-visibility bits, so it
     * is always written whole — dropping the base bits would silently hide a puppet's name.
     */
    static void setEntityFlags(ByteBuf b, long entityId, long flags) {
        ByteBufUtils.writeVarInt(b, ID_SET_ENTITY_DATA);
        ByteBufUtils.writeVarLong(b, entityId);            // entity runtime id
        ByteBufUtils.writeVarInt(b, 1);                    // metadata entry count
        flagsEntry(b, flags);
    }

    /**
     * A SetEntityData carrying the pose: sneaking / sprinting / item-use bits, sent together because they
     * live in the same long — and with the nametag-visibility flags kept, or the name would vanish.
     */
    static void setEntityPose(ByteBuf b, long entityRuntimeId,
                              boolean sneaking, boolean sprinting, boolean usingItem) {
        long flags = BASE_ENTITY_FLAGS
                | (sneaking ? (1L << DATA_FLAG_SNEAKING_BIT) : 0L)
                | (sprinting ? (1L << DATA_FLAG_SPRINTING_BIT) : 0L)
                | (usingItem ? (1L << DATA_FLAG_ACTION_BIT) : 0L);
        setEntityFlags(b, entityRuntimeId, flags);
    }

    /** One DATA_FLAGS metadata entry (a zigzag long). */
    private static void flagsEntry(ByteBuf b, long flags) {
        ByteBufUtils.writeVarInt(b, DATA_FLAGS_INDEX);     // key = DATA_FLAGS (0)
        ByteBufUtils.writeVarInt(b, DATA_TYPE_LONG);       // type = LONG (7)
        ByteBufUtils.writeSignedVarLong(b, flags);         // putVarLong (zigzag)
    }

    /** One DATA_NAMETAG metadata entry — index 4 at protocol 113 (0.14 puts it at 2). */
    private static void nameTagEntry(ByteBuf b, String nameTag) {
        ByteBufUtils.writeVarInt(b, DATA_NAMETAG_INDEX);   // key = DATA_NAMETAG (4)
        ByteBufUtils.writeVarInt(b, DATA_TYPE_STRING);     // type = STRING (4)
        ByteBufUtils.writeString(b, nameTag == null ? "" : nameTag);
    }

    // ===== Animations =====

    /** An Animate body (protocol 113): action (putVarInt), then entity runtime id. */
    static void animate(ByteBuf b, int action, long entityRuntimeId) {
        ByteBufUtils.writeVarInt(b, ID_ANIMATE);
        ByteBufUtils.writeSignedVarInt(b, action);         // action (putVarInt, signed)
        ByteBufUtils.writeVarLong(b, entityRuntimeId);     // entity runtime id
        // no trailing float — only actions with bit 0x80 carry one
    }

    /**
     * An EntityEvent body (protocol 113): entity runtime id, byte event, then a {@code putVarInt} data
     * field (signed). {@code event} 2 = hurt (the damage flash + sound); {@code data} is unused for it.
     */
    static void entityEvent(ByteBuf b, long entityRuntimeId, int event, int data) {
        ByteBufUtils.writeVarInt(b, ID_ENTITY_EVENT);
        ByteBufUtils.writeVarLong(b, entityRuntimeId);     // entity runtime id
        b.writeByte(event);
        ByteBufUtils.writeSignedVarInt(b, data);           // data (putVarInt, signed)
    }

    // ===== Blocks =====

    /**
     * One typed UpdateBlock (0x16): a single block instead of re-sending the whole chunk. Position is x/z
     * zigzag-varint and y unsigned-varint (the layout the inbound edit decoder reads); the canonical state
     * splits into a legacy id and 4-bit meta.
     */
    static void updateBlock(ByteBuf b, int x, int y, int z, int state) {
        ByteBufUtils.writeVarInt(b, ID_UPDATE_BLOCK);
        ByteBufUtils.writeSignedVarInt(b, x);
        ByteBufUtils.writeVarInt(b, y);
        ByteBufUtils.writeSignedVarInt(b, z);
        ByteBufUtils.writeVarInt(b, (state >> 4) & 0xFF);                          // legacy block id
        ByteBufUtils.writeVarInt(b, (UPDATE_BLOCK_FLAG_ALL << 4) | (state & 0xF)); // (flags << 4) | meta
    }

    // ===== Equipment and inventory =====

    /**
     * A MobEquipment (0x1f) body, verbatim from PMMP at protocol 113: entity runtime id (unsigned
     * varlong), the item Slot, inventorySlot (byte), hotbarSlot (byte), windowId (byte 0). The slot bytes
     * only matter for the holder's own inventory; a viewer just renders the item.
     */
    static void mobEquipment(ByteBuf b, long entityRuntimeId, int state) {
        ByteBufUtils.writeVarInt(b, ID_MOB_EQUIPMENT);
        ByteBufUtils.writeVarLong(b, entityRuntimeId);
        McpeCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        b.writeByte(0);
        b.writeByte(0);
        b.writeByte(0);
    }

    /**
     * A MobArmorEquipment (0x20) body, verbatim from PMMP at protocol 113: entity runtime id (unsigned
     * varlong) then exactly four Slots, head-to-feet. One packet dresses the whole avatar.
     */
    static void mobArmorEquipment(ByteBuf b, long entityRuntimeId,
                                  int helmet, int chestplate, int leggings, int boots) {
        ByteBufUtils.writeVarInt(b, ID_MOB_ARMOR_EQUIPMENT);
        ByteBufUtils.writeVarLong(b, entityRuntimeId);
        for (int state : new int[]{helmet, chestplate, leggings, boots}) {
            McpeCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        }
    }

    /**
     * The wearer's own armor. PMMP's {@code sendArmorContents} sends the WEARER a ContainerSetContent for
     * the armor window rather than the MobArmorEquipment other players get — without it a Bedrock player
     * sees everyone's armor but their own. Body per protocol 113: windowId, targetEid, slot count, slots,
     * then a hotbar-link count of 0 (links are only written for the inventory window).
     */
    static void ownArmor(ByteBuf b, long selfEntityId,
                         int helmet, int chestplate, int leggings, int boots) {
        ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_CONTENT);
        ByteBufUtils.writeVarInt(b, WINDOW_ID_ARMOR);
        ByteBufUtils.writeSignedVarLong(b, selfEntityId);
        ByteBufUtils.writeVarInt(b, 4);
        for (int state : new int[]{helmet, chestplate, leggings, boots}) {
            McpeCodec.writeSlot(b, state, state == 0 ? 0 : 1);
        }
        ByteBufUtils.writeVarInt(b, 0);
    }

    /** The core inventory's storage slots (0-8 hotbar / 9-35 main) that map 1:1 onto PE window 0. */
    static final int PE_PLAYER_SLOTS = 36;

    /**
     * The slot count PE 1.1.5 expects in the player window: {@code getSize() + getHotbarSize()} = 36 + 9 =
     * 45. PMMP appends 9 trailing air slots after the 36 storage slots, and the client won't wire up its
     * hotbar HUD unless the window has exactly this shape.
     */
    static final int PE_PLAYER_WINDOW_SLOTS = 45;

    /**
     * The ContainerSetContent body for the player window (0), PMMP-exact so the client wires up its
     * hotbar HUD. Two details a plain ContainerSetContent misses — and the reason mined items showed only
     * with the inventory GUI open:
     * <ul>
     *   <li><b>45 slots, not 36.</b> PMMP sends {@code getSize() + getHotbarSize()} — the 36 storage
     *       slots (core 0-8 hotbar / 9-35 main, 1:1) followed by 9 trailing air slots.</li>
     *   <li><b>A 9-entry hotbar-link array.</b> Each on-screen hotbar position {@code i} maps to inventory
     *       slot {@code i}; PMMP writes the link as {@code index + getHotbarSize()} (= {@code i + 9}).
     *       Without these links the client fills storage but leaves the hotbar empty.</li>
     * </ul>
     * The core's armor / off-hand slots (36-40) live in separate PE windows (not modelled here yet).
     */
    static void playerInventory(ByteBuf b, long selfEntityId,
                                IntUnaryOperator state, IntUnaryOperator count) {
        playerInventory(b, selfEntityId, state, count, slot -> null);
    }

    /** As above, with a per-slot custom-item display ({@code null} from the lookup = an ordinary item). */
    static void playerInventory(ByteBuf b, long selfEntityId,
                                IntUnaryOperator state, IntUnaryOperator count,
                                java.util.function.IntFunction<com.jedrock.api.item.ItemDisplay> display) {
        ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_CONTENT);
        ByteBufUtils.writeVarInt(b, WINDOW_ID_PLAYER);
        ByteBufUtils.writeSignedVarLong(b, selfEntityId);
        ByteBufUtils.writeVarInt(b, PE_PLAYER_WINDOW_SLOTS);
        for (int slot = 0; slot < PE_PLAYER_WINDOW_SLOTS; slot++) {
            if (slot < PE_PLAYER_SLOTS) {
                McpeCodec.writeSlot(b, state.applyAsInt(slot), count.applyAsInt(slot),
                        display.apply(slot));
            } else {
                McpeCodec.writeSlot(b, Blocks.AIR, 0); // 9 trailing hotbar-area slots are air
            }
        }
        ByteBufUtils.writeVarInt(b, 9);                    // hotbar-link count
        for (int i = 0; i < 9; i++) {
            ByteBufUtils.writeSignedVarInt(b, i + 9);      // hotbar pos i -> slot i (index + hotbarSize)
        }
    }

    /** A single-slot update. PMMP's {@code sendSlot} leaves hotbarSlot and selectSlot at 0 — match it. */
    static void containerSetSlot(ByteBuf b, int windowId, int slot, int state, int count) {
        containerSetSlot(b, windowId, slot, state, count, null);
    }

    /** As above, carrying a custom item's name and lore. */
    static void containerSetSlot(ByteBuf b, int windowId, int slot, int state, int count,
                                 com.jedrock.api.item.ItemDisplay display) {
        ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_SLOT);
        b.writeByte(windowId);                             // window id (a byte here, not a varint)
        ByteBufUtils.writeSignedVarInt(b, slot);           // inventory slot
        ByteBufUtils.writeSignedVarInt(b, 0);              // hotbarSlot (PMMP default)
        McpeCodec.writeSlot(b, state, count, display);
        b.writeByte(0);                                    // selectSlot (PMMP default)
    }

    /**
     * A ContainerSetContent (0x34): windowId, targetEid, slot count, the slots themselves, then a
     * hotbar-link count of 0 (only the player window remaps the hotbar — see {@link #playerInventory}).
     */
    static void containerSetContent(ByteBuf b, int windowId, long targetEntityId,
                                    int slotCount, IntUnaryOperator slotState, int count) {
        ByteBufUtils.writeVarInt(b, ID_CONTAINER_SET_CONTENT);
        ByteBufUtils.writeVarInt(b, windowId);             // window id (unsigned varint)
        ByteBufUtils.writeSignedVarLong(b, targetEntityId);// targetEid (zigzag varlong)
        ByteBufUtils.writeVarInt(b, slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            McpeCodec.writeSlot(b, slotState.applyAsInt(slot), count);
        }
        ByteBufUtils.writeVarInt(b, 0);                    // hotbar-link count (none)
    }

    // ===== Lifecycle: login, spawn and the world =====

    /** PlayStatus — note the status is a big-endian int32, not a varint. */
    static void playStatus(ByteBuf b, int status) {
        ByteBufUtils.writeVarInt(b, ID_PLAY_STATUS);
        ByteBufUtils.writeIntBE(b, status);
    }

    /** An empty ResourcePacksInfo: nothing to download, nothing to accept. */
    static void resourcePacksInfo(ByteBuf b) {
        ByteBufUtils.writeVarInt(b, ID_RESOURCE_PACKS_INFO);
        b.writeBoolean(false);                             // must accept
        b.writeShortLE(0);                                 // behaviour pack count
        b.writeShortLE(0);                                 // resource pack count
    }

    /**
     * StartGame: the packet that hands the client its identity, its game mode and the world it is about
     * to see. The long tail of generation / feature flags is written exactly as PMMP orders it at
     * protocol 113 — the client reads the whole body positionally, so a missing field shifts every one
     * after it and the join dies with no error.
     *
     * <p>{@code bedrockDimension} is in Bedrock's own numbering (0/1/2, see
     * {@code PeSession.bedrockDimension}) and is how a player who logged out in the nether joins under a
     * nether sky rather than being switched into one after arriving. It rides the same
     * {@code -Djedrock.pe.changeDimension=false} escape hatch as {@link #changeDimension}: with that flag
     * off the join is announced as the overworld, which is the wrong sky and a join that cannot hang.
     */
    static void startGame(ByteBuf b, long selfEntityId, int gameModeId, int bedrockDimension,
                          double spawnX, double spawnY, double spawnZ,
                          int spawnBlockX, int spawnBlockY, int spawnBlockZ) {
        ByteBufUtils.writeVarInt(b, ID_START_GAME);

        // Player entity ids + game mode
        ByteBufUtils.writeSignedVarLong(b, selfEntityId);
        ByteBufUtils.writeVarLong(b, selfEntityId);
        ByteBufUtils.writeSignedVarInt(b, gameModeId);

        // Position + rotation
        b.writeFloatLE((float) spawnX);
        b.writeFloatLE((float) spawnY);
        b.writeFloatLE((float) spawnZ);
        b.writeFloatLE(0.0f);
        b.writeFloatLE(0.0f);

        // World generation basics: seed, dimension, generator, …
        ByteBufUtils.writeSignedVarInt(b, 12345);
        ByteBufUtils.writeSignedVarInt(b, bedrockDimension);
        ByteBufUtils.writeSignedVarInt(b, 1);
        ByteBufUtils.writeSignedVarInt(b, 1);
        ByteBufUtils.writeSignedVarInt(b, 1);

        // World spawn block coords
        ByteBufUtils.writeSignedVarInt(b, spawnBlockX);
        ByteBufUtils.writeSignedVarInt(b, spawnBlockY);
        ByteBufUtils.writeSignedVarInt(b, spawnBlockZ);

        b.writeBoolean(true);
        ByteBufUtils.writeSignedVarInt(b, 0);
        b.writeBoolean(false);
        b.writeFloatLE(0.0f);
        b.writeFloatLE(0.0f);

        b.writeBoolean(true);
        b.writeBoolean(true);
        b.writeBoolean(false);

        b.writeBoolean(true);
        b.writeBoolean(false);

        ByteBufUtils.writeVarInt(b, 0);

        ByteBufUtils.writeString(b, "jedrock_level");
        ByteBufUtils.writeString(b, "Jedrock PE World");
        ByteBufUtils.writeString(b, "");

        b.writeBoolean(false);
        b.writeLongLE(0L);
    }

    /** The chunk radius the server grants (the client asked for one; this is the answer). */
    static void chunkRadiusUpdated(ByteBuf b, int radius) {
        ByteBufUtils.writeVarInt(b, ID_CHUNK_RADIUS_UPDATED);
        ByteBufUtils.writeSignedVarInt(b, radius);
    }

    /** One serialized chunk column. */
    static void fullChunkData(ByteBuf b, int chunkX, int chunkZ, byte[] chunkData) {
        ByteBufUtils.writeVarInt(b, ID_FULL_CHUNK_DATA);
        ByteBufUtils.writeSignedVarInt(b, chunkX);
        ByteBufUtils.writeSignedVarInt(b, chunkZ);
        ByteBufUtils.writeVarInt(b, chunkData.length);
        b.writeBytes(chunkData);
    }

    /**
     * AdventureSettings — mainly the flight permission. {@code allowFlight} rides the flags field; the
     * player entity id is an <b>LE long, not a varint</b> (a short write truncates the packet, and the
     * flight bit is lost with it). The OP command/permission level is kept so the creative menu and
     * block edits work.
     */
    static void adventureSettings(ByteBuf b, long selfEntityId, boolean allowFlight) {
        ByteBufUtils.writeVarInt(b, ID_ADVENTURE_SETTINGS);
        ByteBufUtils.writeVarInt(b, allowFlight ? ADVENTURE_ALLOW_FLIGHT : 0);
        ByteBufUtils.writeVarInt(b, 2);                    // command permission (OP)
        ByteBufUtils.writeVarInt(b, 0);                    // action permissions
        ByteBufUtils.writeVarInt(b, 2);                    // permission level (OP)
        ByteBufUtils.writeVarInt(b, 0);                    // custom extension flags
        b.writeLongLE(selfEntityId);                       // player entity unique id (LE long)
    }

    /** SetPlayerGameType: flips the client's HUD between survival and creative live. */
    static void setPlayerGameType(ByteBuf b, int gameModeId) {
        ByteBufUtils.writeVarInt(b, ID_SET_PLAYER_GAME_TYPE);
        ByteBufUtils.writeSignedVarInt(b, gameModeId);
    }

    /** SetHealth — PMMP writes the value as a signed (zigzag) varint. */
    static void setHealth(ByteBuf b, int health) {
        ByteBufUtils.writeVarInt(b, ID_SET_HEALTH);
        ByteBufUtils.writeSignedVarInt(b, health);
    }

    /**
     * The movement-speed attribute (0.1, vanilla walking), which is what stops the 1.1.5 client's
     * runaway acceleration. Body order per PMMP's {@code putAttributeList}: count, then min / max /
     * current / default as LE floats and the name — no per-attribute modifier count.
     */
    static void movementSpeedAttribute(ByteBuf b, long selfRuntimeId) {
        ByteBufUtils.writeVarInt(b, ID_UPDATE_ATTRIBUTES);
        ByteBufUtils.writeVarLong(b, selfRuntimeId);
        ByteBufUtils.writeVarInt(b, 1);                    // attribute count
        b.writeFloatLE(0.0f);                              // min
        b.writeFloatLE(3.4028235E38f);                     // max
        b.writeFloatLE(0.1f);                              // current (vanilla walk speed)
        b.writeFloatLE(0.1f);                              // default
        ByteBufUtils.writeString(b, "minecraft:movement");
        ByteBufUtils.writeVarInt(b, 0);                    // modifier count
    }

    /** Turn the client's "/" input on. Without it the client refuses to send a command at all. */
    static void setCommandsEnabled(ByteBuf b, boolean enabled) {
        ByteBufUtils.writeVarInt(b, ID_SET_COMMANDS_ENABLED);
        b.writeBoolean(enabled);
    }

    /**
     * The manifest of commands the client may send. At 113 this is a JSON blob, not the binary tree of
     * later versions; a command missing from it is dropped client-side and never reaches the server.
     */
    static void availableCommands(ByteBuf b, String json) {
        ByteBufUtils.writeVarInt(b, ID_AVAILABLE_COMMANDS);
        ByteBufUtils.writeString(b, json);
        ByteBufUtils.writeString(b, "");                   // unused second JSON blob
    }

    /** A Respawn body (protocol 113): the spawn position as three little-endian floats (eye y). */
    static void respawn(ByteBuf b, float x, float y, float z) {
        ByteBufUtils.writeVarInt(b, ID_RESPAWN);
        b.writeFloatLE(x);
        b.writeFloatLE(y);
        b.writeFloatLE(z);
    }

    /**
     * ChangeDimension (0x3D): move the client to another world — it drops the terrain it holds, shows a
     * loading screen, and waits for chunks followed by PlayStatus(PLAYER_SPAWN).
     *
     * <p>The dimension here is <b>Bedrock's</b> numbering (0 overworld, 1 nether, 2 end), not Java's
     * {@code -1} for the nether: the two editions disagree, and this is the boundary that translates.
     * The id is signed-varint encoded, as every id-like field on this protocol is.
     *
     * <p>Unverified against a real 1.1.5 client — it is the one packet in this path nobody has watched
     * land. {@code -Djedrock.pe.changeDimension=false} skips it and falls back to a plain chunk resend,
     * which leaves the sky wrong but cannot hang a client on a loading screen.
     */
    static void changeDimension(ByteBuf b, int bedrockDimension, float x, float y, float z, boolean respawn) {
        ByteBufUtils.writeVarInt(b, ID_CHANGE_DIMENSION);
        ByteBufUtils.writeSignedVarInt(b, bedrockDimension);
        b.writeFloatLE(x);
        b.writeFloatLE(y);
        b.writeFloatLE(z);
        b.writeBoolean(respawn);
    }
}
