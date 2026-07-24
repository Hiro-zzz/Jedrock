package com.jedrock.network.pe;

/**
 * MCPE 1.1.5 (protocol 113) packet ids and protocol constants, shared across the PE layer.
 *
 * <p>Extracted so {@link PeSession}, {@link PeBlockEditDecoder} and {@link PeRakNetServer} share
 * one source of truth for the wire numbers instead of each carrying their own copies.
 */
final class McpeProtocol {

    private McpeProtocol() {}

    /** Every MCPE game packet is wrapped behind this single header byte. */
    static final int GAME_PACKET_WRAPPER = 0xFE;

    // --- Packet ids ---
    static final int ID_LOGIN = 0x01;
    static final int ID_PLAY_STATUS = 0x02;
    static final int ID_RESOURCE_PACKS_INFO = 0x06;
    static final int ID_RESOURCE_PACK_RESPONSE = 0x08;
    static final int ID_TEXT = 0x09;
    static final int ID_START_GAME = 0x0B;
    static final int ID_ADD_PLAYER = 0x0C;
    static final int ID_ADD_ENTITY = 0x0D;        // outbound: spawn a non-player entity (a puppet)
    static final int ID_REMOVE_ENTITY = 0x0E;
    static final int ID_MOVE_ENTITY = 0x12;       // outbound: move a non-player entity
    static final int ID_MOVE_PLAYER = 0x13;
    static final int ID_UPDATE_BLOCK = 0x16;
    static final int ID_UPDATE_ATTRIBUTES = 0x1E; // outbound: movement-speed fix (PMMP 113: 0x1e — was 0x1D)
    static final int ID_ENTITY_FALL = 0x25;       // inbound: the client reports a fall (its distance)
    static final int ID_SET_HEALTH = 0x2A;        // outbound: set the player's health (0..20)
    static final int ID_RESPAWN = 0x2D;           // outbound: place the player after a death-screen respawn
    static final int ID_MOB_EQUIPMENT = 0x1F;     // both ways: the item in an entity's hand (held-item sync)
    static final int ID_MOB_ARMOR_EQUIPMENT = 0x20; // outbound: the four worn armor pieces, in one packet
    static final int ID_ADD_ITEM_ENTITY = 0x0f;     // outbound: a dropped-item entity, carrying its item
    static final int ID_INTERACT = 0x21;          // inbound: attack / interact with an entity
    static final int ID_USE_ITEM = 0x23;          // Win10 1.1.5 carries block placement here
    static final int ID_PLAYER_ACTION = 0x24;
    static final int ID_ENTITY_EVENT = 0x1C;      // outbound: one-shot entity event (hurt animation etc.)
    static final int ID_SET_ENTITY_DATA = 0x27;   // entity metadata (sneak pose etc.)
    static final int ID_ANIMATE = 0x2C;           // arm swing
    static final int ID_SET_TITLE = 0x59;         // outbound: title / subtitle / action-bar / times (protocol 113)
    static final int ID_LEVEL_SOUND_EVENT = 0x19; // outbound: entity-ish sounds (explode / levelup / note …)
    static final int ID_LEVEL_EVENT = 0x1a;       // outbound: world effects — 1000-series sounds, 0x4000|particle

    // --- SetTitle types (protocol 113, PMMP SetTitlePacket) ---
    static final int TITLE_TYPE_CLEAR = 0;
    static final int TITLE_TYPE_TITLE = 2;
    static final int TITLE_TYPE_SUBTITLE = 3;
    static final int TITLE_TYPE_ACTIONBAR = 4;
    static final int TITLE_TYPE_TIMES = 5;
    // MCPE 1.1 (protocol 113) uses ContainerSetContent (0x34) for inventory/creative content — NOT
    // the InventoryContent (0x31) of 1.2+. Its layout is: windowId, targetEid (entity id), slot count,
    // slots, then a hotbar-link count. Verified against PocketMine-MP at CURRENT_PROTOCOL = 113.
    static final int ID_BLOCK_ENTITY_DATA = 0x38;     // a block-entity's NBT (a chest tile), carried in the chunk tail
    static final int ID_CONTAINER_OPEN = 0x30;        // open a container GUI (chest)
    static final int ID_CONTAINER_CLOSE = 0x31;       // close a container (both directions)
    static final int ID_CONTAINER_SET_SLOT = 0x32;    // update one inventory slot (live hotbar refresh); inbound = a client move
    static final int ID_CONTAINER_SET_CONTENT = 0x34;
    static final int ID_ADVENTURE_SETTINGS = 0x37;
    static final int ID_SET_COMMANDS_ENABLED = 0x3B;  // toggle client-side "/" command parsing
    static final int ID_AVAILABLE_COMMANDS = 0x4E;    // JSON manifest of the commands the client may send
    static final int ID_COMMAND_STEP = 0x4F;          // inbound: a slash command the client parsed
    static final int ID_FULL_CHUNK_DATA = 0x3A;
    static final int ID_SET_PLAYER_GAME_TYPE = 0x3E; // live game-mode switch (gameType as signed varint)
    static final int ID_PLAYER_LIST = 0x3F;
    static final int ID_REQUEST_CHUNK_RADIUS = 0x45;
    static final int ID_CHUNK_RADIUS_UPDATED = 0x46;

    // --- PlayStatus values ---
    static final int PLAY_STATUS_LOGIN_SUCCESS = 0;
    static final int PLAY_STATUS_PLAYER_SPAWN = 3;

    // --- TextPacket types ---
    static final int TEXT_TYPE_RAW = 0;
    static final int TEXT_TYPE_CHAT = 1;

    // --- PlayerList actions ---
    static final int PLAYER_LIST_ADD = 0;
    static final int PLAYER_LIST_REMOVE = 1;

    // --- Inventory window ids (ContainerSetContent targets) ---
    static final int WINDOW_ID_PLAYER = 0;     // the player's own inventory
    static final int WINDOW_ID_ARMOR = 120;    // the wearer's own armor slots (ContainerIds::ARMOR)
    static final int WINDOW_ID_CREATIVE = 121; // the creative menu's item palette

    // --- AdventureSettings flags (protocol 113) ---
    static final int ADVENTURE_ALLOW_FLIGHT = 0x40;

    /** UpdateBlock flags: neighbours | network — tells the client to apply and re-render the change. */
    static final int UPDATE_BLOCK_FLAG_ALL = 0b0011;

    // --- PlayerAction action ids ---
    // In creative the client reports a break with CONTINUE_BREAK carrying the block position.
    /** InteractPacket action: a left-click = a melee attack (ACTION_RIGHT_CLICK = 1 is ignored). */
    static final int INTERACT_LEFT_CLICK = 2;

    /** EntityEvent event: a living entity is hurt (the damage flash + sound). */
    static final int ENTITY_EVENT_HURT = 2;

    static final int ACTION_RESPAWN = 7;       // the client clicked "Respawn" on the death screen
    static final int ACTION_START_BREAK = 0;
    static final int ACTION_ABORT_BREAK = 1;   // mining cancelled before completion
    static final int ACTION_STOP_BREAK = 2;    // mining finished (the survival break completion)
    static final int ACTION_CONTINUE_BREAK = 18;
    static final int ACTION_START_SPRINT = 9;
    static final int ACTION_STOP_SPRINT = 10;
    static final int ACTION_START_SNEAK = 11;
    static final int ACTION_STOP_SNEAK = 12;

    // --- Animate (arm swing) + entity metadata (sneak / sprint pose, nametag) ---
    static final int ANIMATE_SWING_ARM = 1;
    /** Entity metadata: DATA_FLAGS is a LONG property at index 0; sneak/sprint are bits of that long. */
    static final int DATA_FLAGS_INDEX = 0;
    static final int DATA_NAMETAG_INDEX = 4;   // string: the floating nametag above the entity
    /** Int at index 2: a falling block's {@code id | (meta << 8)} (PMMP {@code DATA_VARIANT} — 0.14 uses 20). */
    static final int DATA_VARIANT_INDEX = 2;
    static final int DATA_TYPE_LONG = 7;
    static final int DATA_TYPE_INT = 2;
    static final int DATA_TYPE_STRING = 4;
    static final int DATA_FLAG_SNEAKING_BIT = 1;
    static final int DATA_FLAG_SPRINTING_BIT = 3;
    static final int DATA_FLAG_ACTION_BIT = 4;    // "using an item" (eat / drink / block / draw bow)
    static final int DATA_FLAG_CAN_SHOW_NAMETAG_BIT = 14;
    static final int DATA_FLAG_ALWAYS_SHOW_NAMETAG_BIT = 15;
    /**
     * Flags every player avatar always carries so its nametag floats above its head like on Java.
     * These must ride along in <em>every</em> DATA_FLAGS write (e.g. pose changes) or a later write
     * would clear them and the name would stop showing.
     */
    static final long BASE_ENTITY_FLAGS =
            (1L << DATA_FLAG_CAN_SHOW_NAMETAG_BIT) | (1L << DATA_FLAG_ALWAYS_SHOW_NAMETAG_BIT);

    /** Immobile / no-AI (bit 16) — a hologram line must never drift or be pushed around. */
    static final int DATA_FLAG_IMMOBILE_BIT = 16;

    /**
     * The MCPE entity id of a dropped-item entity. Bedrock has no armor stand in the legacy eras, so a
     * hologram line hangs its name on one of these with no item attached — nothing renders but the text.
     * The same hack PocketMine's floating text uses (id 64 at both 0.14 and 1.1.5).
     */
    static final int ITEM_ENTITY_TYPE_ID = 64;

    /** PocketMine's own offset for floating text, so the name lands on the requested y. */
    static final double TEXT_LINE_Y_OFFSET = -0.75;


    /** MovePlayer mode: 0 normal (interpolated), 2 teleport (server reposition — used by the judge). */
    static final int MOVE_MODE_TELEPORT = 2;

    /** Block-face offsets (Bedrock uses the same order as Java): down, up, north, south, west, east. */
    static final int[] FACE_DX = {0, 0, 0, 0, -1, 1};
    static final int[] FACE_DY = {-1, 1, 0, 0, 0, 0};
    static final int[] FACE_DZ = {0, 0, -1, 1, 0, 0};

    /**
     * MCPE MovePlayer carries the <em>eye</em> position (feet + 1.62), while AddPlayer and StartGame
     * use feet. Confirmed by a standing client reporting y=59.62 while its feet were on a block top
     * at 58 (fractional .62 = the eye offset). The core works in feet, so convert on MovePlayer only.
     */
    static final float EYE_HEIGHT = 1.62f;
}
