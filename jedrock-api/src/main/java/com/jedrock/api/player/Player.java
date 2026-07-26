package com.jedrock.api.player;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.entity.Entity;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.UUID;

/**
 * Absolute abstraction over a connected player.
 * Implementations should be extremely thin wrappers.
 * No direct access to packets or network inside this interface.
 *
 * <p>A player is also a {@link CommandSender} (it {@code getName()}s, {@code sendMessage()}s and carries
 * op/permission state), so a command can accept either a player or the console.
 */
public interface Player extends Entity, CommandSender {

    UUID getUniqueId();

    @Override
    String getName();

    /**
     * The player's chat prefix from their highest permission group (may carry {@code {color}} markup), or an
     * empty string if none. Substituted into the chat format as {@code %prefix%}.
     */
    String getPrefix();

    /**
     * The world the player is currently in.
     */
    World getWorld();

    /**
     * Current location. Implementations may use lazy position tracking.
     */
    Location getLocation();

    void teleport(Location location);

    /** Teleport to absolute coordinates in the current world, keeping the current facing. */
    default void teleport(double x, double y, double z) {
        Location cur = getLocation();
        teleport(new Location(getWorld(), x, y, z, cur.yaw(), cur.pitch()));
    }

    /** Teleport to absolute coordinates and facing in the current world. */
    default void teleport(double x, double y, double z, float yaw, float pitch) {
        teleport(new Location(getWorld(), x, y, z, yaw, pitch));
    }

    /**
     * Round-trip latency to this player's client, in milliseconds, or {@code -1} while unknown (just
     * after join, or a connection that can't measure it). JE measures the keep-alive round trip;
     * Bedrock reads the RakNet transport's own estimate.
     */
    default int getPing() {
        return getConnection().getPing();
    }

    /**
     * The name shown for this player in chat — defaults to the real {@link #getName()}. A custom
     * display name is authored text: it may carry the unified {@code {color}} / Markdown markup and is
     * rendered as-is in the chat format (the real name, being client-controlled, is escaped instead).
     * Identity stays the real name everywhere else (commands, tab list, joins, permissions).
     */
    default String getDisplayName() {
        return getName();
    }

    /** Set the chat display name; {@code null} or blank resets to the real name. Default: a no-op. */
    default void setDisplayName(String displayName) {}

    /**
     * The selected hotbar slot (0-8). Clients report switches; the server tracks it so placement and
     * the cross-edition held-item visual know what's in the hand. Default 0 for a minimal player.
     */
    default int getHeldItemSlot() {
        return 0;
    }

    /** The canonical {@code (id << 4) | meta} state in the player's hand (0 = empty). */
    default int getHeldItem() {
        return getItem(getHeldItemSlot());
    }

    /** The canonical item state worn in {@code slot} (0 = nothing). */
    default int getArmor(ArmorSlot slot) {
        return 0;
    }

    /**
     * Wear {@code state} (0 = remove) in {@code slot}. Visible on this player's avatar to everyone,
     * cross-edition; the server simulates no protection. Default: a no-op.
     */
    default void setArmor(ArmorSlot slot, int state) {}

    /** Remove every worn piece. */
    default void clearArmor() {
        for (ArmorSlot slot : ArmorSlot.values()) {
            setArmor(slot, 0);
        }
    }

    /** Play a canonical sound to this player only, at their own position (a private UI ding). */
    default void playSound(com.jedrock.api.world.Sound sound) {
        playSound(sound, 1.0f, 1.0f);
    }

    /** As {@link #playSound(com.jedrock.api.world.Sound)} with explicit volume and pitch (both 1 = normal). */
    default void playSound(com.jedrock.api.world.Sound sound, float volume, float pitch) {
        Location at = getLocation();
        getConnection().playSound(sound, at.x(), at.y(), at.z(), volume, pitch);
    }

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    /**
     * Current health in half-heart points. Full is {@link #getMaxHealth()} (20 = 10 hearts). Health is
     * server-authoritative — the client only reports that it was hit; the server decides the number.
     */
    int getHealth();

    /** Full health in half-heart points (20 = 10 hearts). */
    int getMaxHealth();

    /**
     * Set health directly, clamped to {@code 0..}{@link #getMaxHealth()}, and refresh the client's health
     * HUD. Setting it to 0 does not itself trigger the death/respawn flow — that lives in the damage path.
     */
    void setHealth(int health);

    /** {@code true} while the player is crouching. */
    boolean isSneaking();

    /** {@code true} while the player is sprinting. */
    boolean isSprinting();

    /** {@code true} while the player is using an item (eating, drinking, blocking, drawing a bow). */
    boolean isUsingItem();

    /**
     * Give one item of the canonical block/item {@code state} ({@code (id << 4) | meta}) to the player's
     * storage inventory, stacking onto a match or filling the first empty slot, and refresh that slot on the
     * client. Only meaningful in survival — creative players carry the creative menu, not this inventory.
     *
     * @return {@code true} if it fit, {@code false} if the storage inventory was full
     */
    boolean giveItem(int state);

    // ===== Inventory (the 36 storage slots: 0-8 hotbar, 9-35 main) =====
    // Items are the canonical (id << 4) | meta state used everywhere (0 = empty), with a parallel count.
    // These operate on the server-side inventory and push the change to the client, so they are meaningful
    // in survival; a creative client manages its own inventory, so effects there are limited.

    /** Number of addressable inventory slots (36: 0-8 hotbar, 9-35 main). */
    int getInventorySize();

    /** Canonical {@code (id << 4) | meta} state at {@code slot} (0 = empty, or out of range). */
    int getItem(int slot);

    /** Stack count at {@code slot} (0 = empty, or out of range). */
    int getItemCount(int slot);

    /** Set {@code slot} to {@code state} + {@code count} ({@code state 0} or {@code count <= 0} clears it) and sync it. */
    void setItem(int slot, int state, int count);

    /** Give {@code count} items of {@code state}, stacking, then sync. @return how many actually fit. */
    int giveItem(int state, int count);

    /** Remove up to {@code count} items of {@code state} from anywhere in the inventory, then sync. @return how many were removed. */
    int removeItem(int state, int count);

    /** Total number of {@code state} items across the inventory. */
    int countItem(int state);

    /** {@code true} if the inventory holds at least one {@code state}. */
    default boolean hasItem(int state) {
        return countItem(state) > 0;
    }

    /** Empty every inventory slot and sync. */
    void clearInventory();

    /**
     * The player's remote network address (IP:port or an edition-specific string). A convenience over
     * {@code getConnection().getAddress()}.
     */
    String getAddress();

    /**
     * Kick with a message. Abstract - actual disconnect is implementation detail. Fires a cancellable
     * {@code PlayerKickEvent}; a listener may cancel it (leaving the player online) or rewrite the reason.
     */
    void kick(String reason);

    /**
     * Send a raw chat/system message. Keep abstraction.
     */
    void sendMessage(String message);

    /**
     * Show a large centred <b>title</b> with a smaller <b>subtitle</b> beneath it, using default timings
     * (10 / 70 / 20 ticks fade-in / stay / fade-out). Either may be empty. Text uses the unified
     * {@code {color}} + Markdown markup. Cross-edition; a client with no title concept (MCPE 0.14) falls
     * back to a chat line.
     */
    default void sendTitle(String title, String subtitle) {
        sendTitle(title, subtitle, 10, 70, 20);
    }

    /** As {@link #sendTitle(String, String)} but with explicit fade-in / stay / fade-out in <b>ticks</b>. */
    void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut);

    /** Show a line of text just above the hotbar (the <b>action bar</b>). Markup renders; cross-edition. */
    void sendActionBar(String text);

    /** Clear any title / subtitle currently shown to the player. */
    void clearTitle();

    /**
     * Show a <b>sidebar scoreboard</b>: a title and lines of text, top to bottom, authored in the unified
     * markup. Purely presentational — the server tracks no real scores; the lines are whatever you set,
     * and calling this again replaces them (updating only what changed, so it's cheap on a timer).
     *
     * <p>Cross-edition, by two different illusions. <b>Java</b> (1.8 and 1.12.2) gets a real sidebar
     * scoreboard down the right of the screen; the pre-1.13 client draws a small red number beside each
     * line (the vanilla look). <b>Bedrock</b> has no scoreboard in either legacy era, so the same text is
     * drawn in the HUD line above the hotbar — the field a held item's name uses, displaced upward — with
     * the title on top and the rows beneath it. That line fades by itself, so the server repaints it while
     * the sidebar is set; a script doesn't have to. At most
     * {@value com.jedrock.api.player.Player#SIDEBAR_MAX_LINES} lines are shown on any edition.
     */
    void setSidebar(String title, java.util.List<String> lines);

    /** Remove the sidebar scoreboard, if one is shown. */
    void clearSidebar();

    /** The most lines a {@link #setSidebar} sidebar shows; extra lines are dropped. */
    int SIDEBAR_MAX_LINES = 16;

    /**
     * Show a <b>boss bar</b> across the top of the screen: a title and a fill fraction, in the default
     * purple. Purely presentational — no entity, no combat; the bar shows whatever you set. Calling it
     * again updates the same bar. Cross-edition where the client can show one: Java 1.12.2 (its dedicated
     * packet), Java 1.8 (an invisible wither ridden by the player — the classic illusion) and Bedrock
     * 1.1.5 (BossEvent). Bedrock 0.14 predates boss bars and ignores it.
     *
     * @param progress the fill, {@code 0.0}..{@code 1.0} (clamped)
     */
    default void setBossBar(String title, float progress) {
        setBossBar(title, progress, "purple");
    }

    /**
     * As {@link #setBossBar(String, float)} but with a bar colour: {@code pink}, {@code blue}, {@code red},
     * {@code green}, {@code yellow}, {@code purple} or {@code white} (unknown falls back to purple).
     */
    void setBossBar(String title, float progress, String color);

    /** Remove the boss bar, if one is shown. */
    void clearBossBar();

    /**
     * @return true if the player is still connected
     */
    boolean isOnline();

    /**
     * Low-level connection handle if needed by higher modules.
     * Prefer not to expose - use only for extreme abstraction cases.
     */
    PlayerConnection getConnection();
}
