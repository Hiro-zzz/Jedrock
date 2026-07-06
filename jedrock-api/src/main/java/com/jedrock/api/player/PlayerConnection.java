package com.jedrock.api.player;

import com.jedrock.api.protocol.ProtocolVersion;

/**
 * Absolute abstraction for a player's network connection.
 * This should never expose concrete packet classes or ByteBufs directly in the API.
 *
 * Implementations can use lazy parsing under the hood.
 */
public interface PlayerConnection {

    /**
     * The protocol this connection is using.
     */
    ProtocolVersion getProtocolVersion();

    /**
     * Remote address representation (abstracted).
     */
    String getAddress();

    /**
     * Send a message / packet abstraction.
     *
     * The Object parameter keeps the API 100% protocol-agnostic (no packet types in api module).
     * Typical usage from core:
     *   - Pass a ClientboundPacket impl (from network layer)
     *   - Or a pre-built ByteBuf
     */
    void sendPacket(Object packet); // Object to keep API completely protocol agnostic

    /**
     * Send a system/chat message to the client.
     * Protocol-agnostic on purpose: the implementation decides how to encode it,
     * so higher layers (e.g. the core player) never touch concrete packets.
     */
    void sendMessage(String message);

    /** Add a player entry to this client's tab / player list. */
    void addToTab(java.util.UUID uuid, String name);

    /** Remove a player entry from this client's tab / player list. */
    void removeFromTab(java.util.UUID uuid);

    /**
     * Spawn another player's avatar in this client's world. The entry must already be
     * in the tab / player list (JE requires it for the avatar to render).
     *
     * @param entityId server-assigned id of the avatar's subject ({@code Entity#getEntityId})
     */
    void showPlayer(java.util.UUID uuid, String name, long entityId,
                    double x, double y, double z, float yaw, float pitch);

    /** Despawn a player avatar previously shown via {@link #showPlayer}. */
    void hidePlayer(java.util.UUID uuid, long entityId);

    /** Move a player avatar previously shown via {@link #showPlayer} to an absolute position. */
    void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch);

    /**
     * Reposition <em>this</em> client's own player — e.g. the blind judge snapping an illegal move
     * back to the last valid spot, so the client re-syncs with the server's authoritative position.
     */
    void teleport(double x, double y, double z, float yaw, float pitch);

    /** Play another player's arm-swing animation on this client (attack / dig / interact). */
    void swingArm(long entityId);

    /**
     * Set another player's pose on this client — crouch, sprint and item-use (eat / drink / block /
     * draw bow). These share a flags field on the wire (all in one PE {@code DATA_FLAGS} long; crouch
     * and sprint in the one JE flags byte), so they are always sent together to avoid one clearing
     * another.
     */
    void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem);

    /**
     * Push a single block change to this client so an edit made by any player becomes visible.
     * @param state the new canonical block state {@code (id << 4) | meta} (0 = air, i.e. a break)
     */
    void sendBlockChange(int x, int y, int z, int state);

    /**
     * Close the connection.
     */
    void close(String reason);

    /**
     * @return true if the underlying channel is active.
     */
    boolean isActive();
}
