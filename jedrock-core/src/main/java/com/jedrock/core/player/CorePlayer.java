package com.jedrock.core.player;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerKickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.entity.EntityIds;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.inventory.Cursor;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.text.ChatText;

import java.util.UUID;

/**
 * In-memory player state. A thin wrapper over the abstract {@link PlayerConnection};
 * it holds no Netty or protocol details, only game-facing state.
 */
public final class CorePlayer implements Player {

    private final UUID uniqueId;
    private final String name;
    private final PlayerConnection connection;
    /** Shared with puppets via {@link EntityIds}, so an avatar id never collides with a puppet id. */
    private final long entityId = EntityIds.next();

    private volatile CoreWorld world;
    private volatile Location location;
    private volatile GameMode gameMode;
    private volatile boolean sneaking = false;
    private volatile boolean sprinting = false;
    private volatile boolean usingItem = false;
    /** For firing {@code PlayerKickEvent} from {@link #kick}; null in tests that don't wire the bus. */
    private final EventBus eventBus;

    public CorePlayer(UUID uniqueId, String name, PlayerConnection connection,
                      CoreWorld world, Location location, GameMode gameMode) {
        this(uniqueId, name, connection, world, location, gameMode, null);
    }

    public CorePlayer(UUID uniqueId, String name, PlayerConnection connection,
                      CoreWorld world, Location location, GameMode gameMode, EventBus eventBus) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.connection = connection;
        this.world = world;
        this.location = location;
        // Set directly (not via setGameMode): the join sequence already told the client this mode, so
        // there's no packet to send here — only later /gamemode changes push a live update to the client.
        this.gameMode = gameMode;
        this.eventBus = eventBus;
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

    /**
     * Player inventory layout (core slots): 0-8 hotbar, 9-35 main, 36-39 armor (helmet/chest/legs/boots),
     * 40 off-hand. Mining and pickups only ever fill the 36 <em>storage</em> slots (0-35); armor and the
     * off-hand are set only by an inventory move (a window click).
     */
    public static final int STORAGE_SLOTS = 36;
    public static final int INV_SLOTS = 41;
    private final Container inventory = new Container(INV_SLOTS);

    /** The full 41-slot player inventory (hotbar / main / armor / off-hand). */
    public Container getInventory() {
        return inventory;
    }

    /** The item this player is carrying on the cursor while a window is open (empty when none). */
    private final Cursor cursor = new Cursor();

    public Cursor getCursor() {
        return cursor;
    }

    // ===== Open container (a chest) =====

    private int openWindowId = 0;      // 0 = only the player's own inventory is open
    private Container openContainer;   // the chest container being viewed (null when none)

    public int getOpenWindowId() {
        return openWindowId;
    }

    public Container getOpenContainer() {
        return openContainer;
    }

    public boolean hasContainerOpen() {
        return openContainer != null;
    }

    public void openContainer(int windowId, Container container) {
        this.openWindowId = windowId;
        this.openContainer = container;
    }

    public void closeContainer() {
        this.openWindowId = 0;
        this.openContainer = null;
    }

    /** Canonical state per inventory slot (0-8 hotbar, 9-35 main, 36-39 armor, 40 off-hand); 0 = empty. */
    public int[] inventoryStates() {
        return inventory.states();
    }

    /** Item count per inventory slot, parallel to {@link #inventoryStates()}. */
    public int[] inventoryCounts() {
        return inventory.counts();
    }

    /**
     * Add one {@code state} to a storage slot (0-35), stacking onto a match or filling the first empty,
     * <b>without</b> touching the client. The core's own batch paths (chest moves, cursor drain) call this
     * and then sync in bulk; the client-facing {@link #giveItem(int)} is the scripting-friendly wrapper.
     * @return the affected slot index, or -1 if it didn't fit (so the caller can push just that slot).
     */
    public int addToInventory(int state) {
        return inventory.give(state, 0, STORAGE_SLOTS);
    }

    /** Give one {@code state} and refresh that slot on the client. @return true if it fit. (api {@link Player}) */
    @Override
    public boolean giveItem(int state) {
        int slot = addToInventory(state);
        if (slot < 0) {
            return false;
        }
        syncSlot(slot);
        return true;
    }

    /** Remove one {@code state} from a storage slot (a survival placement). @return the affected slot, or -1. */
    public int takeItem(int state) {
        return inventory.take(state, 0, STORAGE_SLOTS);
    }

    /** Push one inventory slot to the client (a live pickup / consume — refreshes the hotbar HUD). */
    public void syncSlot(int slot) {
        connection.setInventorySlot(slot, inventory.stateAt(slot), inventory.countAt(slot));
    }

    /** Push the whole inventory to the client (a reset — join, respawn, game-mode switch). */
    public void syncInventory() {
        connection.setInventory(inventory.states(), inventory.counts());
    }

    // ===== Health (server-authoritative; the client only reports damage events) =====

    /** Full health in half-heart points (20 = 10 hearts). */
    public static final int MAX_HEALTH = 20;
    private volatile int health = MAX_HEALTH;

    @Override
    public int getHealth() {
        return health;
    }

    @Override
    public int getMaxHealth() {
        return MAX_HEALTH;
    }

    /**
     * Apply {@code amount} of damage, clamped to zero, <b>without</b> touching the client — the combat
     * funnel syncs the resulting health itself. @return the new health.
     */
    public int damage(int amount) {
        health = Math.max(0, health - Math.max(0, amount));
        return health;
    }

    /**
     * Set health directly (e.g. reset to {@link #MAX_HEALTH} on respawn), clamped to 0..max, and refresh
     * the client's health HUD — the client-facing setter used by scripts and the respawn resets.
     */
    @Override
    public void setHealth(int value) {
        health = Math.max(0, Math.min(MAX_HEALTH, value));
        connection.setHealth(health);
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
    @Override
    public boolean isSneaking() {
        return sneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    /** Whether the player is currently sprinting — synced (with sneak) to players who join later. */
    @Override
    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    /** Whether the player is currently using an item (eat / drink / block / draw bow). */
    @Override
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
    public String getAddress() {
        return connection.getAddress();
    }

    @Override
    public void kick(String reason) {
        // A listener may veto the kick or rewrite its reason. No bus wired (tests) → kick straight away.
        if (eventBus != null) {
            PlayerKickEvent event = eventBus.post(new PlayerKickEvent(this, reason));
            if (event.isCancelled()) {
                return;
            }
            reason = event.getReason();
        }
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

    // ===== Permissions =====

    /** The server op list + group permissions, injected after construction (null in tests → no perms). */
    private volatile OpList opList;
    private volatile PermissionManager permissions;

    /** Wire this player to the server op list and group permissions so isOp/hasPermission/getPrefix resolve. */
    public void setPermissions(OpList opList, PermissionManager permissions) {
        this.opList = opList;
        this.permissions = permissions;
    }

    @Override
    public boolean isOp() {
        OpList ops = opList;
        return ops != null && ops.isOp(name);
    }

    @Override
    public boolean hasPermission(String permission) {
        // An unguarded (null/blank) node is always granted; an op holds every node; otherwise the group
        // system decides (wildcards + explicit deny).
        if (permission == null || permission.isBlank() || isOp()) {
            return true;
        }
        PermissionManager perms = permissions;
        return perms != null && perms.has(name, permission);
    }

    @Override
    public String getPrefix() {
        PermissionManager perms = permissions;
        return perms == null ? "" : perms.prefixOf(name);
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
