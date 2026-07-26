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
     * Inject a raw packet at this connection — a numeric {@code packetId} and its {@code payload} (every byte
     * after the id) — framed for this connection's edition (JE VarInt id, PE game-batch, 0.14 wrapper). The
     * escape hatch behind the scripting {@code packets.send(...)}: it lets a plugin speak the wire directly.
     * Fires the outbound packet taps like any other send. Default: a no-op (a connection double that doesn't
     * model the wire simply ignores it).
     */
    default void sendRawPacket(int packetId, byte[] payload) {}

    /**
     * Send a system/chat message to the client.
     * Protocol-agnostic on purpose: the implementation decides how to encode it,
     * so higher layers (e.g. the core player) never touch concrete packets.
     */
    void sendMessage(String message);

    /**
     * Show a title + subtitle on this client with fade-in / stay / fade-out timings (ticks). Strings arrive
     * already rendered to the edition-agnostic legacy form. Each edition encodes it in its own way (JE Title
     * packet, PE SetTitle); a connection with no title concept (MCPE 0.14) may fall back to a chat line.
     * Default: a no-op.
     */
    default void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {}

    /** Show a line above the hotbar (the action bar). Default: a no-op. */
    default void sendActionBar(String text) {}

    /** Clear any title / subtitle currently shown. Default: a no-op. */
    default void clearTitle() {}

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

    /**
     * Spawn a non-player <b>puppet</b> entity of the given canonical {@code type} in this client's world.
     * Unlike {@link #showPlayer} (which spawns a player avatar via the tab list), this writes the edition's
     * spawn-mob / add-entity packet, mapping {@code type} to that edition's numeric entity id. {@code uuid}
     * is the entity's uuid (JE 1.12.2 SpawnMob carries it; other editions ignore it). A connection that
     * can't render it may ignore the call.
     */
    /**
     * Spawn an item prop — a dropped-item entity rendering {@code state} (canonical
     * {@code (id << 4) | meta}, a block id included) at a position. Its own packet on every edition,
     * because an item stack isn't a mob. Spawned immobile, so it stays exactly where it was put.
     * Default no-op.
     */
    default void spawnItemEntity(long entityId, java.util.UUID uuid,
                                 double x, double y, double z, int state) {}

    /**
     * Spawn a falling-block prop — an entity whose body is a <b>full-size</b> block, unlike the small
     * model an item prop renders. Pinned in place where the edition allows it (see the implementations:
     * JE 1.8 has no no-gravity field, so it is the one that may drift). Default no-op.
     */
    default void spawnFallingBlock(long entityId, java.util.UUID uuid,
                                   double x, double y, double z, int state) {}

    default void spawnEntity(long entityId, java.util.UUID uuid, com.jedrock.api.entity.EntityType type,
                             double x, double y, double z, float yaw, float pitch) {}

    /** Move a puppet entity previously shown via {@link #spawnEntity} to an absolute position. */
    default void moveEntity(long entityId, double x, double y, double z, float yaw, float pitch) {}

    /** Despawn a puppet entity previously shown via {@link #spawnEntity}. */
    default void removeEntity(long entityId) {}

    /**
     * Set the floating text above a puppet entity ({@code null} / empty removes it). Every edition carries
     * it as entity metadata, but at its own index and its own string encoding, so this stays a request for
     * an effect rather than a wire format. A player avatar ignores it — its floating name is its player name.
     */
    default void setEntityNameTag(long entityId, String nameTag) {}

    /**
     * Set a puppet entity's visual flags — a canonical {@link com.jedrock.api.entity.PuppetFlag} mask, which
     * each edition maps onto its own flags field. Always sent as the entity's <em>whole</em> flag set: every
     * edition packs these bits into one field, so a partial write would clear the flags left out.
     */
    default void setEntityFlags(long entityId, int flags) {}

    /**
     * Spawn an entity whose only job is to float one line of {@code text} at {@code (x, y, z)} — a hologram
     * line. There is no canonical entity type for this: each edition picks whatever it can make invisible
     * and hang a name on, and compensates its own rendering offset so the text lands at {@code y}. Despawn
     * it with {@link #removeEntity}; re-text it with {@link #setEntityNameTag}.
     */
    default void spawnTextLine(long entityId, java.util.UUID uuid, double x, double y, double z, String text) {}

    /** Move a player avatar previously shown via {@link #showPlayer} to an absolute position. */
    void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch);

    /**
     * Reposition <em>this</em> client's own player — e.g. the blind judge snapping an illegal move
     * back to the last valid spot, so the client re-syncs with the server's authoritative position.
     */
    void teleport(double x, double y, double z, float yaw, float pitch);

    /**
     * Change <em>this</em> client's own game mode live (survival ↔ creative …), so the client flips its
     * HUD and abilities (flight allowed only in creative / spectator). Each edition encodes it in its
     * own way; a connection that can't switch mode on the wire (e.g. MCPE 0.14) applies it on next join.
     */
    void setGameMode(GameMode mode);

    /**
     * Replace this client's shown inventory with a 36-slot model (indices 0-8 hotbar, 9-35 main); each
     * entry is a canonical {@code (id << 4) | meta} state (0 = empty) with a parallel count. Used for
     * the minimal survival inventory — a mined block appears, a placed one is consumed. A connection
     * with no server-side inventory concept (e.g. creative, or MCPE 0.14) may ignore it.
     */
    default void setInventory(int[] states, int[] counts) {}

    /**
     * Update a single inventory slot (0-8 hotbar, 9-35 main) to a canonical {@code (id << 4) | meta}
     * state and count (0 = empty). Used for a live survival pickup / consume so the hotbar refreshes
     * immediately, where a full {@link #setInventory} resend does not.
     */
    default void setInventorySlot(int slot, int state, int count) {}

    /**
     * Set the player's health bar (0..20, in half-heart points). The server is authoritative for health
     * even in the illusionist model — the client only <em>reports</em> damage events (e.g. a fall); the
     * core decides the number and pushes it here. A connection that doesn't model health may ignore it.
     */
    default void setHealth(int health) {}

    /** Play another player's arm-swing animation on this client (attack / dig / interact). */
    void swingArm(long entityId);

    /**
     * Set the item shown on this client's own cursor (the stack "carried" on the mouse while a window is
     * open); {@code state == 0} clears it. Kept in sync as the server applies window clicks. A connection
     * with no cursor concept may ignore it.
     */
    default void setCursorItem(int state, int count) {}

    /**
     * Open a container window (a chest) on this client: {@code windowId} identifies it for later slot
     * updates and clicks, {@code title} is the shown name, {@code slots} the container's slot count (27
     * for a chest), and {@code (x, y, z)} is the block position (Bedrock's ContainerOpen needs it; Java
     * ignores it). Follow with {@link #setWindowItems} to fill it. A connection with no window concept
     * may ignore it.
     */
    default void openContainer(int windowId, String title, int slots, int x, int y, int z) {}

    /**
     * Replace the contents of an open window ({@code windowId}) with {@code states} / {@code counts},
     * already ordered in that window's slot order (for a chest: the 27 container slots, then the player's
     * 27 main + 9 hotbar slots). Each entry is a canonical {@code (id << 4) | meta} state (0 = empty).
     */
    default void setWindowItems(int windowId, int[] states, int[] counts) {}

    /**
     * Play the "hurt" animation on another player's avatar (the red damage flash + hurt sound) so a hit
     * is visible to everyone watching. Sent for any damage source (PvP, fall, void). The victim's own
     * client shows the hit from its dropping health bar, so this only needs relaying to other clients. A
     * connection that can't render it may ignore it.
     */
    default void playHurtAnimation(long entityId) {}

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
     * Ask this client to play a canonical {@link com.jedrock.api.world.Sound} at a world position.
     * Volume and pitch are best-effort — JE honours both; the PE eras carry pitch only (and not on
     * every sound). Default no-op so a minimal connection compiles.
     */
    default void playSound(com.jedrock.api.world.Sound sound,
                           double x, double y, double z, float volume, float pitch) {}

    /**
     * Ask this client to draw a burst of {@code count} canonical
     * {@link com.jedrock.api.world.Particle}s around a world position, scattered within roughly
     * {@code spread} blocks. JE draws the burst from one packet; the PE eras get one packet per
     * particle, so implementations cap the count. Default no-op.
     */
    default void spawnParticle(com.jedrock.api.world.Particle particle,
                               double x, double y, double z, int count, double spread) {}

    /**
     * Round-trip latency to the client in milliseconds, or {@code -1} while unknown. JE connections
     * measure the keep-alive round trip; Bedrock connections read RakNet's own estimate.
     */
    default int getPing() {
        return -1;
    }

    /**
     * Ask this client to draw the given weather (rain / thunder sky / clear). Sent on every change and
     * once to a late joiner. Default no-op so a minimal connection compiles.
     */
    default void sendWeather(com.jedrock.api.world.Weather weather) {}

    /**
     * Send tab-completion matches for a partially typed command line — the completions the client offers
     * for the token under the cursor. Java sends a serverbound request and shows these; Bedrock completes
     * client-side from its command manifest and never asks, so this is a no-op there. Default no-op.
     */
    default void sendTabComplete(java.util.List<String> matches) {}

    /**
     * Show / update the sidebar: a title and lines (already rendered to legacy {@code §} form), top to
     * bottom. Java draws a real scoreboard panel; Bedrock, which has no scoreboard in either legacy era,
     * draws the same text in the HUD line above the hotbar. Default no-op.
     */
    default void setSidebar(String title, String[] lines) {}

    /** Remove the sidebar, if shown. Default no-op. */
    default void clearSidebar() {}

    /**
     * How often, in ticks, this client needs its sidebar re-sent to keep it on screen — {@code 0} (the
     * default) meaning never, because the client holds the panel itself.
     *
     * <p>A Java scoreboard is stateful client-side, so it is sent once and left alone. A Bedrock client
     * has no scoreboard at all and borrows a HUD line that fades on its own, so it asks to be repainted.
     * The core honours whatever a connection declares without knowing why — the same way it never learns
     * which protocol a player speaks.
     */
    default int sidebarRepaintTicks() {
        return 0;
    }

    /**
     * Show / update the boss bar: a title (already rendered to legacy {@code §} form), a fill fraction
     * ({@code 0}..{@code 1}), and a canonical colour id (0 pink, 1 blue, 2 red, 3 green, 4 yellow,
     * 5 purple, 6 white — the JE wire values). Java (1.12.2 packet, 1.8 wither illusion) and Bedrock 1.1.5
     * draw it; 0.14 predates boss bars and ignores it. Default no-op.
     */
    default void setBossBar(String title, float progress, int color) {}

    /** Remove the boss bar, if shown. Default no-op. */
    default void clearBossBar() {}

    /**
     * Draw {@code state} (canonical {@code (id << 4) | meta}, 0 = empty hand) in the hand of another
     * player's avatar — JE Entity Equipment / PE MobEquipment. Sent when the holder switches hotbar
     * slots, when the held stack changes, and once when the avatar spawns. Default no-op.
     */
    default void showHeldItem(long entityId, int state) {}

    /**
     * Draw the four worn armor pieces (canonical states, 0 = nothing) on another player's avatar —
     * JE Entity Equipment per piece / PE MobArmorEquipment in one packet. Arguments are head-to-feet,
     * the order both Bedrock eras put on the wire. Default no-op.
     */
    default void showArmor(long entityId, int helmet, int chestplate, int leggings, int boots) {}

    /**
     * Show <em>this</em> client its own worn armor. A separate call because the wearer's own copy is a
     * different packet from the one that dresses other people's avatars: JE renders it from the armor
     * slots of the inventory window (so this is a no-op there), while Bedrock needs the pieces pushed
     * to its dedicated armor window or the wearer sees nothing on themselves. Default no-op.
     */
    default void sendOwnArmor(int helmet, int chestplate, int leggings, int boots) {}

    /**
     * Close the connection.
     */
    void close(String reason);

    /**
     * @return true if the underlying channel is active.
     */
    boolean isActive();
}
