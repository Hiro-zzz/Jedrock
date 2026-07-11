package com.jedrock.core.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.text.ChatText;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory player state. A thin wrapper over the abstract {@link PlayerConnection};
 * it holds no Netty or protocol details, only game-facing state.
 */
public final class CorePlayer implements Player {

    /**
     * Avatar entity ids start above the self-ids the protocol handlers hardcode for the
     * client's own player (JE JoinGame / PE StartGame both use 1), so they never collide.
     */
    private static final AtomicLong ENTITY_IDS = new AtomicLong(1000);

    private final UUID uniqueId;
    private final String name;
    private final PlayerConnection connection;
    private final long entityId = ENTITY_IDS.getAndIncrement();

    private volatile CoreWorld world;
    private volatile Location location;
    private volatile GameMode gameMode;
    private volatile boolean sneaking = false;
    private volatile boolean sprinting = false;
    private volatile boolean usingItem = false;

    public CorePlayer(UUID uniqueId, String name, PlayerConnection connection,
                      CoreWorld world, Location location, GameMode gameMode) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.connection = connection;
        this.world = world;
        this.location = location;
        // Set directly (not via setGameMode): the join sequence already told the client this mode, so
        // there's no packet to send here — only later /gamemode changes push a live update to the client.
        this.gameMode = gameMode;
    }

    @Override
    public UUID getUniqueId() {
        return uniqueId;
    }

    @Override
    public long getEntityId() {
        return entityId;
    }

    // ===== Survival inventory =====
    //
    // A deliberately minimal 36-slot inventory (0-8 hotbar, 9-35 main), tracked only so a survival
    // player's picked-up / mined blocks show up and placing consumes them. Creative ignores it (the
    // client has the creative menu). Each slot is a canonical (id<<4)|meta state + a count. Only ever
    // touched from the owning player's own network thread (their edits), so it needs no locking.

    private static final int INV_SLOTS = 36;
    private static final int MAX_STACK = 64;
    private final int[] invStates = new int[INV_SLOTS];
    private final int[] invCounts = new int[INV_SLOTS];

    /** Canonical state per inventory slot (index 0-8 hotbar, 9-35 main); 0 = empty. Read-only. */
    public int[] inventoryStates() {
        return invStates;
    }

    /** Item count per inventory slot, parallel to {@link #inventoryStates()}. Read-only. */
    public int[] inventoryCounts() {
        return invCounts;
    }

    /**
     * Add one {@code state} to the inventory, stacking onto a matching slot (up to {@link #MAX_STACK})
     * or filling the first empty one. @return the affected slot index, or -1 if it didn't fit (so the
     * caller can push just that slot).
     */
    public int giveItem(int state) {
        if (state == 0) {
            return -1;
        }
        for (int i = 0; i < INV_SLOTS; i++) {
            if (invStates[i] == state && invCounts[i] < MAX_STACK) {
                invCounts[i]++;
                return i;
            }
        }
        for (int i = 0; i < INV_SLOTS; i++) {
            if (invStates[i] == 0) {
                invStates[i] = state;
                invCounts[i] = 1;
                return i;
            }
        }
        return -1; // inventory full — drop it (no item entities in the illusion)
    }

    /** Remove one {@code state} from the inventory (a survival placement). @return the affected slot, or -1. */
    public int takeItem(int state) {
        if (state == 0) {
            return -1;
        }
        for (int i = 0; i < INV_SLOTS; i++) {
            if (invStates[i] == state && invCounts[i] > 0) {
                if (--invCounts[i] == 0) {
                    invStates[i] = 0;
                }
                return i;
            }
        }
        return -1;
    }

    /** Push one inventory slot to the client (a live pickup / consume — refreshes the hotbar HUD). */
    public void syncSlot(int slot) {
        connection.setInventorySlot(slot, invStates[slot], invCounts[slot]);
    }

    /** Push the whole inventory to the client (a reset — join, respawn, game-mode switch). */
    public void syncInventory() {
        connection.setInventory(invStates, invCounts);
    }

    // ===== Health (server-authoritative; the client only reports damage events) =====

    /** Full health in half-heart points (20 = 10 hearts). */
    public static final int MAX_HEALTH = 20;
    private volatile int health = MAX_HEALTH;

    public int getHealth() {
        return health;
    }

    /** Apply {@code amount} of damage, clamped to zero. @return the new health. */
    public int damage(int amount) {
        health = Math.max(0, health - Math.max(0, amount));
        return health;
    }

    /** Set health directly (e.g. reset to {@link #MAX_HEALTH} on respawn), clamped to 0..max. */
    public void setHealth(int value) {
        health = Math.max(0, Math.min(MAX_HEALTH, value));
    }

    /** Vanilla-style post-hit invulnerability window (half a second) for melee, in nanoseconds. */
    private static final long HURT_COOLDOWN_NANOS = 500_000_000L;
    /** Sentinel: never hit yet. Kept distinct so the first hit isn't compared against an overflowing delta. */
    private static final long NEVER_HURT = Long.MIN_VALUE;
    private volatile long lastHurtNanos = NEVER_HURT;

    /**
     * Melee invulnerability frames: {@code true} if this player was hit within the last half second (so
     * a rapid click-spam attacker can't stack hits faster than vanilla). Consumes the window — a
     * {@code false} result starts a fresh one. Used only for PvP; environmental damage is already
     * rate-limited by its own tick.
     */
    public boolean isOnHurtCooldown() {
        long now = System.nanoTime();
        // Guard the first hit explicitly: `now - Long.MIN_VALUE` overflows to a negative delta that would
        // read as "still on cooldown", so a fresh player could never be hurt. Once hit, both operands are
        // real nanoTimes and their difference can't overflow.
        if (lastHurtNanos != NEVER_HURT && now - lastHurtNanos < HURT_COOLDOWN_NANOS) {
            return true;
        }
        lastHurtNanos = now;
        return false;
    }

    /** Highest point of the current descent (server-side fall tracking for JE); NaN = not falling. */
    private double fallPeakY = Double.NaN;

    /**
     * Feed one vertical move into fall tracking, for editions that don't report falls themselves (Java
     * has no {@code EntityFall} packet, so the server watches the descent). While the player descends we
     * remember where the fall began; the moment they stop descending we return how far they dropped so
     * the caller can turn it into fall damage. @return the fall distance on landing, else 0.
     */
    public double trackFall(double prevY, double newY) {
        if (newY < prevY - 0.02) {                 // descending
            if (Double.isNaN(fallPeakY)) {
                fallPeakY = prevY;                 // fall just started — this is its top
            }
            return 0;
        }
        if (!Double.isNaN(fallPeakY)) {            // was falling, now stopped: landed at prevY
            double distance = fallPeakY - prevY;
            fallPeakY = Double.NaN;
            return Math.max(0, distance);
        }
        return 0;
    }

    /**
     * Discard any in-progress fall tracking, so the next descent starts fresh. Called after a server
     * teleport / respawn: a discontinuous jump isn't a fall, and a fall into the void never "lands" (so
     * {@link #trackFall} never clears its peak) — without this the first move after respawn could bill
     * the player for a phantom drop from the old peak height.
     */
    public void resetFall() {
        fallPeakY = Double.NaN;
    }

    /** Whether the player is currently crouching — used to sync pose to players who join later. */
    public boolean isSneaking() {
        return sneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    /** Whether the player is currently sprinting — synced (with sneak) to players who join later. */
    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    /** Whether the player is currently using an item (eat / drink / block / draw bow). */
    public boolean isUsingItem() {
        return usingItem;
    }

    public void setUsingItem(boolean usingItem) {
        this.usingItem = usingItem;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public void teleport(Location location) {
        // State-only for now; sending the position packet to the client is a later step.
        this.location = location;
        if (location.world() instanceof CoreWorld cw) {
            this.world = cw;
        }
    }

    @Override
    public GameMode getGameMode() {
        return gameMode;
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        if (gameMode == null || gameMode == this.gameMode) {
            return; // no change — don't churn the client with a redundant switch
        }
        this.gameMode = gameMode;
        connection.setGameMode(gameMode); // push the live switch (HUD + flight) to the client
    }

    @Override
    public void kick(String reason) {
        connection.close(reason);
    }

    @Override
    public void sendMessage(String message) {
        // Messages are authored in the unified {color} + Markdown markup; render to the legacy §
        // codes every edition understands before it reaches the protocol layer.
        connection.sendMessage(ChatText.toLegacy(message));
    }

    @Override
    public boolean isOnline() {
        return connection.isActive();
    }

    @Override
    public PlayerConnection getConnection() {
        return connection;
    }

    // ===== Entity =====

    @Override
    public void remove() {
        connection.close("removed");
    }

    @Override
    public boolean isAlive() {
        return isOnline();
    }

    @Override
    public String getType() {
        return "minecraft:player";
    }
}
