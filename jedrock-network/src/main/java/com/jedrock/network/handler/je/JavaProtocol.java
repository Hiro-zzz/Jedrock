package com.jedrock.network.handler.je;

import com.jedrock.api.player.GameMode;
import com.jedrock.network.JedrockConnection;
import com.jedrock.network.handler.ProtocolHandler;

import java.util.UUID;

/**
 * One Java Edition version's full behaviour, in a single swappable strategy:
 *
 * <ul>
 *   <li>the inbound state machine (login + play), inherited from {@link ProtocolHandler}, and</li>
 *   <li>the clientbound wire encoding the core drives through {@code PlayerConnection}.</li>
 * </ul>
 *
 * <p>Packet ids and formats differ per version, but the transport framing (VarInt length + VarInt id
 * + payload) does not — so {@link JedrockConnection} stays version-neutral: it owns the channel and
 * movement bookkeeping and delegates every version-specific encode to the {@code JavaProtocol}
 * installed for the client that connected. All legacy JE versions share the world's canonical
 * {@code (id << 4) | meta} block model, so no block translation is needed between them.
 *
 * <p>A fresh instance is created per connection (implementations may hold per-connection transient
 * state such as the creative hotbar), so implementations need not be thread-safe across connections.
 */
public interface JavaProtocol extends ProtocolHandler {

    /** Send a system/chat line to the client. */
    void sendSystemMessage(JedrockConnection c, String message);

    /** Show a title + subtitle with fade-in / stay / fade-out timings (ticks). Default: a no-op. */
    default void sendTitle(JedrockConnection c, String title, String subtitle,
                           int fadeIn, int stay, int fadeOut) {}

    /** Show a line above the hotbar (the action bar). Default: a no-op. */
    default void sendActionBar(JedrockConnection c, String text) {}

    /** Clear any title / subtitle shown. Default: a no-op. */
    default void clearTitle(JedrockConnection c) {}

    /** Add a player entry to the client's tab list. */
    void addToTab(JedrockConnection c, UUID uuid, String name);

    /** Remove a player entry from the client's tab list. */
    void removeFromTab(JedrockConnection c, UUID uuid);

    /** Spawn another player's avatar (its tab entry must already exist). */
    void showPlayer(JedrockConnection c, UUID uuid, String name, long entityId,
                    double x, double y, double z, float yaw, float pitch);

    /** Despawn an avatar previously shown via {@link #showPlayer}. */
    void hidePlayer(JedrockConnection c, UUID uuid, long entityId);

    /** Move an avatar previously shown via {@link #showPlayer} to an absolute position. */
    void moveAvatar(JedrockConnection c, long entityId, double x, double y, double z, float yaw, float pitch);

    /** Spawn a non-player puppet entity of the given canonical type (this version's spawn-mob packet). */
    void spawnEntity(JedrockConnection c, long entityId, java.util.UUID uuid,
                     com.jedrock.api.entity.EntityType type,
                     double x, double y, double z, float yaw, float pitch);

    /** Move a puppet entity previously shown via {@link #spawnEntity} (same wire as {@link #moveAvatar}). */
    default void moveEntity(JedrockConnection c, long entityId,
                            double x, double y, double z, float yaw, float pitch) {
        moveAvatar(c, entityId, x, y, z, yaw, pitch); // an entity teleport works for any entity id
    }

    /** Despawn a puppet entity previously shown via {@link #spawnEntity}. */
    void removeEntity(JedrockConnection c, long entityId);

    /** Set a puppet entity's floating name ({@code null} / empty hides it), in this version's metadata format. */
    void setEntityNameTag(JedrockConnection c, long entityId, String nameTag);

    /** Set a puppet entity's whole flags byte, from a canonical {@code PuppetFlag} mask. */
    void setEntityFlags(JedrockConnection c, long entityId, int flags);

    /** Spawn one hologram line: an invisible marker armor stand whose custom name is {@code text}. */
    void spawnTextLine(JedrockConnection c, long entityId, UUID uuid,
                       double x, double y, double z, String text);

    /** Reposition this client's own player (the blind judge snapping an illegal move back). */
    void teleportSelf(JedrockConnection c, double x, double y, double z, float yaw, float pitch);

    /** Switch this client's own game mode live (HUD + fly ability), in this version's packet format. */
    void setGameMode(JedrockConnection c, GameMode mode);

    /** Replace the client's inventory (36-slot model, 0-8 hotbar / 9-35 main) — the survival inventory. */
    void setInventory(JedrockConnection c, int[] states, int[] counts);

    /** Set the player's health bar (0..20) — used for server-tracked fall damage. */
    void setHealth(JedrockConnection c, int health);

    /** Update one inventory slot (core index 0-8 hotbar / 9-35 main) for a live survival pickup / consume. */
    void setInventorySlot(JedrockConnection c, int slot, int state, int count);

    /** Set the item shown on this client's own cursor while a window is open ({@code state} 0 = clear). */
    void setCursorItem(JedrockConnection c, int state, int count);

    /** Open a container window (chest) with {@code slots} container slots (block position for Bedrock). */
    void openContainer(JedrockConnection c, int windowId, String title, int slots, int x, int y, int z);

    /** Replace an open window's contents (already in that window's slot order). */
    void setWindowItems(JedrockConnection c, int windowId, int[] states, int[] counts);

    /** Play another player's arm-swing animation. */
    void swingArm(JedrockConnection c, long entityId);

    /** Play another player's hurt animation (damage flash + sound). */
    void playHurtAnimation(JedrockConnection c, long entityId);

    /** Set another player's pose (crouch / sprint / item-use), sent together as one flags update. */
    void setPose(JedrockConnection c, long entityId, boolean sneaking, boolean sprinting, boolean usingItem);

    /** Push a single block change. {@code state} is the canonical {@code (id << 4) | meta} (0 = air). */
    void sendBlockChange(JedrockConnection c, int x, int y, int z, int state);

    /** Serialize and send one chunk column to the client. */
    void streamChunk(JedrockConnection c, int chunkX, int chunkZ);

    /** Tell the client to drop a chunk column that fell out of range. */
    void unloadChunk(JedrockConnection c, int chunkX, int chunkZ);
}
