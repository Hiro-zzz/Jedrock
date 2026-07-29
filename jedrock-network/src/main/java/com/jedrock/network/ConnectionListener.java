package com.jedrock.network;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;

import java.util.List;
import java.util.UUID;

/**
 * Bridge from the network layer up to whoever owns server state (the core).
 *
 * <p>The network module deliberately knows nothing about the core: it only exposes this
 * hook. The core implements it and registers via {@link NetworkServer#setConnectionListener}.
 * Callbacks arrive on Netty I/O threads — implementations must be thread-safe.
 */
public interface ConnectionListener {

    /** A client finished the login flow and is now in PLAY state. */
    void onLogin(PlayerConnection connection, UUID uuid, String username);

    /** A connection closed. May fire for connections that never logged in. */
    void onDisconnect(PlayerConnection connection);

    /** An in-game player sent a chat message. The core relays it to everyone. */
    void onChat(PlayerConnection connection, String message);

    /**
     * An in-game player reported a new position/look (client-authoritative movement).
     * Fires at the client's own rate (~20/s while moving) — keep implementations lean.
     * {@code y} is the feet position in both editions.
     */
    void onMove(PlayerConnection connection, double x, double y, double z, float yaw, float pitch);

    /**
     * An in-game player edited a block (break or place). The core applies it to the shared world
     * and relays it to everyone. {@code state} is the new canonical block state {@code (id << 4) |
     * meta} (0 = air = a break).
     */
    void onBlockChange(PlayerConnection connection, int x, int y, int z, int state);

    /**
     * Current number of online players, for the server-list ping (JE status + PE query). Queried
     * on I/O threads before login, so it must be cheap and thread-safe. Defaults to 0.
     */
    default int getOnlinePlayerCount() {
        return 0;
    }

    // ===== Packet taps (the raw-packet escape hatch) =====

    /**
     * Whether any packet tap is registered — the network layer's skip-everything gate. When {@code false}
     * (the common case) the hooks below do no work at all: no byte copy, no event. A single cheap read;
     * called on the packet hot path. Defaults to {@code false}.
     */
    default boolean hasPacketTaps() {
        return false;
    }

    /**
     * Offer a client→server packet ({@code id} + {@code payload}, the bytes after the id) to the packet taps
     * <em>before</em> the edition handles it. Returns {@code true} to <b>cancel</b> it — the core never sees
     * it. Only called when {@link #hasPacketTaps()} is {@code true}. Fires on an I/O thread.
     */
    default boolean onInboundPacket(PlayerConnection connection, int packetId, byte[] payload) {
        return false;
    }

    /**
     * Offer a server→client packet ({@code id} + {@code payload}) to the packet taps <em>before</em> it hits
     * the socket. Returns {@code true} to <b>cancel</b> it — nothing is sent. Only called when
     * {@link #hasPacketTaps()} is {@code true}. Fires on whatever thread is sending (often not the client's).
     */
    default boolean onOutboundPacket(PlayerConnection connection, int packetId, byte[] payload) {
        return false;
    }

    /** A player started or stopped sneaking (crouch pose); the core relays it to everyone else. */
    default void onSneak(PlayerConnection connection, boolean sneaking) {}

    /** A player started or stopped sprinting; the core relays it to everyone else. */
    default void onSprint(PlayerConnection connection, boolean sprinting) {}

    /** A player started or stopped using an item (eat / drink / block / draw bow); the core relays it. */
    default void onUseItem(PlayerConnection connection, boolean using) {}

    /** A player swung their arm (attack / dig / interact); the core relays it to everyone else. */
    default void onSwingArm(PlayerConnection connection) {}

    /**
     * A player clicked a slot in an open window. {@code coreSlot} is already translated to the core
     * inventory index (0-40; -1 for an unbacked slot like the crafting grid). {@code button} is 0 (left)
     * or 1 (right); {@code shift} is a quick-move (shift-click). The core applies it server-authoritatively
     * to the player's inventory + cursor and resyncs the client; the network layer confirms the
     * transaction. Modes we don't model (drag, number keys) arrive as a plain resync (no apply).
     */
    default void onWindowClick(PlayerConnection connection, int coreSlot, int button, boolean shift) {}

    /**
     * A player closed a window. The core returns any item left on the cursor to the inventory and clears
     * it (there are no item entities to drop in the illusion), then resyncs. Survival only.
     */
    default void onWindowClose(PlayerConnection connection) {}

    /**
     * A player right-clicked the block at {@code (x, y, z)}. If it's an interactable block (a chest) the
     * core "uses" it — opening its container window — and returns {@code true} so the caller suppresses
     * the block placement the same packet would otherwise be. Returns {@code false} for a plain block, so
     * the caller proceeds to place the held item. Default: not interactable.
     */
    default boolean onUseBlock(PlayerConnection connection, int x, int y, int z) {
        return false;
    }

    /**
     * A PE 1.1.5 player right-clicked the block at {@code (x, y, z)} — the click-transfer chest path, used
     * because that client crashes on a real chest window. If it's a chest the core moves a stack between
     * the chest and the player's inventory and returns {@code true} (so the caller suppresses the placement
     * the packet would otherwise be): a plain right-click <b>withdraws</b> the first stack from the chest;
     * a sneaking right-click <b>deposits</b> the player's held hotbar slot ({@code heldSlot}, 0-8). Survival
     * only. Returns {@code false} for a non-chest block. Default: not a chest.
     */
    default boolean onChestInteract(PlayerConnection connection, int x, int y, int z, int heldSlot) {
        return false;
    }

    /**
     * A player clicked {@code windowSlot} of their open chest window (slots 0-26 the chest, 27-62 their
     * own inventory). The core translates the slot, applies the click server-authoritatively to the chest
     * or the player inventory + cursor, and resyncs the window. {@code button} 0 left / 1 right; {@code
     * shift} quick-moves between the chest and the player. Survival only.
     */
    default void onChestClick(PlayerConnection connection, int windowSlot, int button, boolean shift) {}

    /**
     * A creative player set an inventory slot from the creative menu ({@code coreSlot} already translated;
     * {@code state} 0 = cleared). The core mirrors it into the player inventory server-side — creative is
     * otherwise client-managed — so an open chest's player-inventory half (and a later switch to survival)
     * reflects what the creative player actually holds. No client resync (the client already shows it).
     */
    default void onCreativeSetSlot(PlayerConnection connection, int coreSlot, int state, int count) {}

    /** The client switched its selected hotbar slot (0-8) — every edition reports this. */
    default void onHeldSlotChange(PlayerConnection connection, int slot) {}

    /**
     * A Bedrock client reported a container slot change (it drives its own inventory and sends a
     * ContainerSetSlot per changed slot — client-authoritative, unlike Java's server-authoritative click).
     * {@code windowId} 0 = the player's own inventory; the open chest's id = the chest container. The core
     * applies the reported {@code state}/{@code count} to that slot.
     */
    default void onContainerSetSlot(PlayerConnection connection, int windowId, int slot, int state, int count) {}

    /**
     * A player left-clicked (attacked) another entity. {@code targetEntityId} is the server-assigned id
     * of the victim's avatar ({@code Entity#getEntityId}); the core resolves it to a player and applies
     * melee damage. The client sends its own arm-swing separately ({@link #onSwingArm}). Fires on an I/O
     * thread. A widened id (long) so it fits both JE's int target and PE's varlong runtime id.
     */
    default void onAttack(PlayerConnection connection, long targetEntityId) {}

    /**
     * The game mode a client should join in: its last {@code /gamemode} choice this run if it has one,
     * otherwise the configured default. Queried by the join sequence (JE Join Game / PE StartGame) so a
     * returning player keeps their mode — the only way MCPE 0.14, which can't switch mode live, ever
     * changes. Called on an I/O thread before {@code onLogin}; must be cheap and thread-safe.
     */
    default GameMode gameModeFor(UUID uuid) {
        return GameMode.CREATIVE;
    }

    /**
     * The world a client should join <em>into</em>: the one it was last standing in, if the server
     * remembers players that way and that world is still loaded. Queried by the join sequence alongside
     * {@link #gameModeFor}, before a single chunk is serialized, so a returning player's client streams
     * the right terrain from the start rather than being walked across afterwards — a world switch during
     * a join is a Respawn or a ChangeDimension the client has no reason to be sent.
     *
     * <p>{@code null} (the default) means "the world this connection already points at" — the server's
     * default world. Called on an I/O thread before {@code onLogin}; must be cheap and thread-safe.
     */
    default com.jedrock.api.world.World worldFor(UUID uuid) {
        return null;
    }

    /**
     * The current game mode of an in-game player, by connection — lets an edition decide break timing
     * (creative mines instantly; survival breaks only when digging finishes). Defaults to creative
     * (instant) for an unknown connection, matching the pre-survival behaviour.
     */
    default GameMode gameModeOf(PlayerConnection connection) {
        return GameMode.CREATIVE;
    }

    /**
     * A player's client reported falling {@code fallDistance} blocks. In the illusionist model the
     * client is authoritative for its own physics, so it tells us (MCPE {@code EntityFall}); the core
     * turns the distance into fall damage rather than simulating gravity. Fires on an I/O thread.
     */
    default void onFall(PlayerConnection connection, float fallDistance) {}

    /**
     * A client asked to complete a partially typed command line ({@code partialLine} includes the leading
     * {@code /}, and its last whitespace token is what the cursor is on). The core resolves the matches
     * and pushes them back with {@link PlayerConnection#sendTabComplete}. Java clients send this
     * (serverbound Tab-Complete); Bedrock does its own client-side completion from the AvailableCommands
     * manifest, so it never calls this. Fires on an I/O thread. Default no-op.
     */
    default void onTabComplete(PlayerConnection connection, String partialLine) {}

    /** Name, help text and aliases of one in-game command — all an edition needs to advertise it. */
    record CommandInfo(String name, String description, List<String> aliases) {}

    /**
     * The in-game commands the core has registered. A Bedrock client validates a typed slash command
     * against a manifest the server sends it and silently drops anything it wasn't told about, so the
     * PE session advertises these in an {@code AvailableCommands} packet at spawn. Java clients need
     * nothing — they send {@code /…} straight through as chat.
     */
    default List<CommandInfo> commands() {
        return List.of();
    }
}
