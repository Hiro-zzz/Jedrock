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
    static final int ID_REMOVE_ENTITY = 0x0E;
    static final int ID_MOVE_PLAYER = 0x13;
    static final int ID_UPDATE_ATTRIBUTES = 0x1D; // movement-speed fix
    static final int ID_INVENTORY_TRANSACTION = 0x1E;
    static final int ID_USE_ITEM = 0x23;          // Win10 1.1.5 carries block placement here
    static final int ID_PLAYER_ACTION = 0x24;
    // MCPE 1.1 (protocol 113) uses ContainerSetContent (0x34) for inventory/creative content — NOT
    // the InventoryContent (0x31) of 1.2+. Its layout is: windowId, targetEid (entity id), slot count,
    // slots, then a hotbar-link count. Verified against PocketMine-MP at CURRENT_PROTOCOL = 113.
    static final int ID_CONTAINER_SET_CONTENT = 0x34;
    static final int ID_ADVENTURE_SETTINGS = 0x37;
    static final int ID_FULL_CHUNK_DATA = 0x3A;
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

    // --- Inventory window ids (InventoryContent) ---
    static final int WINDOW_ID_PLAYER = 0;   // the player's own inventory (fills the hotbar)
    static final int WINDOW_ID_CREATIVE = 121; // the creative menu's item palette

    // --- AdventureSettings flags (protocol 113) ---
    static final int ADVENTURE_ALLOW_FLIGHT = 0x40;

    // --- PlayerAction action ids ---
    // In creative the client reports a break with CONTINUE_BREAK carrying the block position.
    static final int ACTION_START_BREAK = 0;
    static final int ACTION_CONTINUE_BREAK = 18;

    // --- InventoryTransaction: transaction types + UseItem action types + inventory-action sources ---
    static final int TRANSACTION_USE_ITEM = 2;
    static final int USE_ITEM_CLICK_BLOCK = 0; // place
    static final int USE_ITEM_BREAK_BLOCK = 2; // break
    static final int SOURCE_CONTAINER = 0;
    static final int SOURCE_WORLD = 2;
    static final int SOURCE_CREATIVE = 3;
    static final int SOURCE_TODO = 99999;

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
