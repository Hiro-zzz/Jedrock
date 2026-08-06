package com.jedrock.core.player;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerArmorChangeEvent;
import com.jedrock.api.event.player.PlayerHealthChangeEvent;
import com.jedrock.api.event.player.PlayerKickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.entity.EntityIds;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.inventory.Cursor;
import com.jedrock.core.inventory.CustomStackTrail;
import com.jedrock.core.inventory.SlotEchoGuard;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.text.ChatText;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // ===== Who this player can see =====
    //
    // Maintained by PlayerTracker, which is also the only thing allowed to change it. Because the
    // visibility rule is symmetric, this one set answers both "whose avatars is this client holding"
    // and "who is holding this one" — so a relay about this player iterates it directly instead of the
    // whole roster. Concurrent because the relay reads it from other players' network threads while the
    // tracker edits it from this one.

    private final Set<CorePlayer> visible = ConcurrentHashMap.newKeySet();

    /** The players whose avatars this client holds — and, symmetrically, who holds this one. */
    public Set<CorePlayer> getVisible() {
        return visible;
    }

    /** Whether this client currently holds {@code other}'s avatar. */
    public boolean sees(CorePlayer other) {
        return visible.contains(other);
    }

    /** Start tracking {@code other}; {@code true} if this call is the one that added them. */
    boolean see(CorePlayer other) {
        return visible.add(other);
    }

    /** Stop tracking {@code other}; {@code true} if this call is the one that removed them. */
    boolean unsee(CorePlayer other) {
        return visible.remove(other);
    }

    // ===== Status effects =====
    //
    // What this player is under, and until when. The client is the one animating any of it — this is
    // only what the server needs its own answers from (see EffectService): taking one away, re-stating
    // it to a client that has just arrived somewhere, and the two decisions the core actually owns.
    // Written from the applying thread and read from the game loop, so it is concurrent; an EnumMap
    // would be smaller but is not, and this map is empty for almost every player almost always.

    private final java.util.Map<com.jedrock.api.entity.Effect, com.jedrock.core.effect.ActiveEffect>
            effects = new ConcurrentHashMap<>(4);

    /** The live effect table. Owned by {@code EffectService} — nothing else should write to it. */
    public java.util.Map<com.jedrock.api.entity.Effect, com.jedrock.core.effect.ActiveEffect> getEffects() {
        return effects;
    }

    /**
     * Whether this player's avatar is currently withheld from other clients. Invisibility is done by not
     * spawning the avatar rather than by an entity flag, so it needs no wire support on any edition and
     * behaves identically on all four. Maintained by {@code PlayerTracker}, which is also what reads it.
     */
    private volatile boolean invisible = false;

    public boolean isInvisible() {
        return invisible;
    }

    void setInvisible(boolean invisible) {
        this.invisible = invisible;
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

    /**
     * Which regions this player is standing in, remembered between movement reports so the core can tell a
     * crossing from a step. Owned by the movement path; see {@code RegionManager.updateMembership}. Costs
     * one field on a server that has no regions at all.
     */
    private final com.jedrock.core.region.RegionMembership regionMembership =
            new com.jedrock.core.region.RegionMembership();

    public com.jedrock.core.region.RegionMembership getRegionMembership() {
        return regionMembership;
    }

    /**
     * The two corners this player has marked with {@code /region pos1} / {@code pos2}, {@code null} until
     * they mark one. Kept on the player rather than in the command so two operators can select at the same
     * time, and so a disconnect throws a half-made selection away instead of leaving it lying around.
     */
    private volatile int[] regionCorner1;
    private volatile int[] regionCorner2;

    public void setRegionCorner(boolean first, int x, int y, int z) {
        int[] corner = {x, y, z};
        if (first) {
            regionCorner1 = corner;
        } else {
            regionCorner2 = corner;
        }
    }

    /** The marked corner as {@code {x, y, z}}, or {@code null} if it hasn't been marked. */
    public int[] getRegionCorner(boolean first) {
        int[] corner = first ? regionCorner1 : regionCorner2;
        return corner == null ? null : corner.clone();
    }

    /** The item this player is carrying on the cursor while a window is open (empty when none). */
    private final Cursor cursor = new Cursor();

    public Cursor getCursor() {
        return cursor;
    }

    // ===== Open container (a world chest, or a script-owned virtual menu) =====

    private int openWindowId = 0;      // 0 = only the player's own inventory is open
    private Container openContainer;   // the chest / menu container being viewed (null when none)
    /** True for a world chest (its edits persist); false for a transient script menu. */
    private boolean openContainerPersistent;
    /** Non-null when the open container is a button menu: its slots are read-only and clicks call this. */
    private com.jedrock.core.inventory.MenuClick openMenuClick;

    public int getOpenWindowId() {
        return openWindowId;
    }

    public Container getOpenContainer() {
        return openContainer;
    }

    public boolean hasContainerOpen() {
        return openContainer != null;
    }

    /** Whether the open container's edits should be persisted (a world chest) rather than dropped (a menu). */
    public boolean isOpenContainerPersistent() {
        return openContainerPersistent;
    }

    /** The click handler if the open container is a button menu, or {@code null} for a storage container. */
    public com.jedrock.core.inventory.MenuClick getOpenMenuClick() {
        return openMenuClick;
    }

    /** Open a world chest: a storage container whose edits persist. */
    public void openContainer(int windowId, Container container) {
        openContainer(windowId, container, true, null);
    }

    /**
     * Open a container of any kind. {@code persistent} marks a world chest (edits saved) from a transient
     * menu; a non-null {@code menuClick} makes it a read-only button menu whose clicks fire the handler.
     */
    public void openContainer(int windowId, Container container, boolean persistent,
                              com.jedrock.core.inventory.MenuClick menuClick) {
        this.openWindowId = windowId;
        this.openContainer = container;
        this.openContainerPersistent = persistent;
        this.openMenuClick = menuClick;
    }

    public void closeContainer() {
        this.openWindowId = 0;
        this.openContainer = null;
        this.openContainerPersistent = false;
        this.openMenuClick = null;
    }

    /** The sidebar as it was last sent (legacy-rendered), kept for connections that need a repaint. */
    private volatile String sidebarTitle;
    private volatile String[] sidebarLines;

    /**
     * This player as scripts see them — the one view, kept here so it lives and dies with the player.
     * Scripts compare players with {@code ==}, which Rhino answers by reference, so every crossing into
     * JavaScript has to hand back the same object. Typed as {@code Object} because the core knows nothing
     * about the scripting layer; the plugin host owns what this is (see {@code ScriptWrapFactory}).
     */
    private volatile Object scriptView;

    @SuppressWarnings("unchecked")
    public <T> T scriptView() {
        return (T) scriptView;
    }

    public void scriptView(Object view) {
        this.scriptView = view;
    }

    /** A button menu shown as a text list (the Bedrock fallback), pickable with {@code /pick}; null if none. */
    private volatile com.jedrock.core.inventory.ListMenu pendingMenu;

    public com.jedrock.core.inventory.ListMenu getPendingMenu() {
        return pendingMenu;
    }

    public void setPendingMenu(com.jedrock.core.inventory.ListMenu menu) {
        this.pendingMenu = menu;
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

    /**
     * As {@link #addToInventory(int)}, but for a stack that is <em>something</em> — a custom item, and
     * whatever has happened to that particular one. Everything moving an existing stack into a player's
     * inventory goes through this rather than the plain form, or the stack arrives as the ordinary item
     * it is merely drawn as.
     */
    public int addToInventory(int state, String customKey, String customData) {
        return inventory.give(state, 0, STORAGE_SLOTS, customKey, customData);
    }

    /** As above, for a stack that is also enchanted — the identity a move must not drop. */
    public int addToInventory(int state, String customKey, String customData,
                              com.jedrock.api.item.Enchantments enchantments) {
        return inventory.give(state, 0, STORAGE_SLOTS, customKey, customData, enchantments);
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

    /**
     * Notified whenever this player's visible equipment may have changed — the server wires this to
     * redraw the hand or the armor on every other client's copy of the avatar. Split in two so a
     * hotbar switch doesn't re-send the armor (and vice versa).
     */
    public interface EquipmentListener {
        void heldItemChanged(CorePlayer player);

        void armorChanged(CorePlayer player);
    }

    private volatile EquipmentListener equipmentListener;

    public void setEquipmentListener(EquipmentListener listener) {
        this.equipmentListener = listener;
    }

    /** Fire the held-item hook (no-op before the server wires it, e.g. in tests). */
    private void heldItemMayHaveChanged() {
        EquipmentListener listener = equipmentListener;
        if (listener != null) {
            listener.heldItemChanged(this);
        }
    }

    /** Fire the armor hook (no-op before the server wires it, e.g. in tests). */
    private void armorMayHaveChanged() {
        EquipmentListener listener = equipmentListener;
        if (listener != null) {
            listener.armorChanged(this);
        }
    }

    /**
     * The item registry, for turning a stack's custom-item key into the name and lore the client is shown.
     * Null until the server wires it (and in tests), which simply means every item looks vanilla.
     */
    private volatile com.jedrock.core.item.ItemRegistry items;

    public void setItems(com.jedrock.core.item.ItemRegistry items) {
        this.items = items;
    }

    /**
     * The display for one slot: the custom item's name and lore, plus whatever <em>this</em> stack is
     * enchanted with, or {@code null} for a plain ordinary one.
     *
     * <p>The two halves come from different places on purpose. A name belongs to the <b>definition</b> and
     * is shared by every stack that names it; enchantments belong to the <b>stack</b>. So the key answers
     * the first and the slot answers the second, and an ordinary sword that happens to be enchanted gets a
     * display even though nothing defines it.
     */
    private com.jedrock.api.item.ItemDisplay displayAt(int slot) {
        return displayFor(inventory, slot);
    }

    /** The same, for any container — a chest window shows a stack the way its owner's inventory would. */
    public com.jedrock.api.item.ItemDisplay displayFor(
            com.jedrock.core.inventory.Container container, int slot) {
        com.jedrock.api.item.Enchantments enchantments = container.enchantmentsAt(slot);
        com.jedrock.api.item.ItemDisplay named = displayForKey(container.customKeyAt(slot));
        if (named != null) {
            return enchantments.isEmpty() ? named : named.withEnchantments(enchantments);
        }
        return enchantments.isEmpty()
                ? null : com.jedrock.api.item.ItemDisplay.enchanted(enchantments);
    }

    /**
     * How a stack carrying {@code key} should be named and described, or {@code null} if it is an ordinary
     * one. Public because a chest window is filled by {@code ContainerService} out of somebody else's
     * container, and a named sword should read the same whichever box it is sitting in.
     */
    public com.jedrock.api.item.ItemDisplay displayForKey(String key) {
        if (key == null) {
            return null; // the overwhelmingly common case — no lookup, no allocation
        }
        com.jedrock.core.item.ItemRegistry registry = items;
        com.jedrock.core.item.CoreCustomItem item = registry == null ? null : registry.get(key);
        if (item == null) {
            return null; // a key nothing defines: the vanilla name is the honest answer
        }
        return new com.jedrock.api.item.ItemDisplay(
                ChatText.toLegacy(item.getDisplayName()), legacyLore(item.getLore()));
    }

    private static String[] legacyLore(String[] lore) {
        if (lore.length == 0) {
            return lore;
        }
        String[] out = new String[lore.length];
        for (int i = 0; i < lore.length; i++) {
            out[i] = ChatText.toLegacy(lore[i] == null ? "" : lore[i]);
        }
        return out;
    }

    /** Every slot's display, or {@code null} when nothing in the inventory is a custom item. */
    private com.jedrock.api.item.ItemDisplay[] inventoryDisplay() {
        com.jedrock.api.item.ItemDisplay[] display = null;
        for (int slot = 0; slot < INV_SLOTS; slot++) {
            com.jedrock.api.item.ItemDisplay one = displayAt(slot);
            if (one == null) {
                continue;
            }
            if (display == null) {
                display = new com.jedrock.api.item.ItemDisplay[INV_SLOTS];
            }
            display[slot] = one;
        }
        return display; // null = an ordinary inventory, and the wire is byte-identical to before
    }

    /** Push one inventory slot to the client (a live pickup / consume — refreshes the hotbar HUD). */
    public void syncSlot(int slot) {
        slotEchoGuard.arm(slot, System.nanoTime());
        connection.setInventorySlot(slot, inventory.stateAt(slot), inventory.countAt(slot), displayAt(slot));
        if (slot == heldSlot) {
            heldItemMayHaveChanged(); // the hand itself changed — other clients must redraw it
        }
    }

    /**
     * Push the whole inventory to the client (a reset — join, respawn, game-mode switch). Both
     * equipment hooks fire: a full resync can have changed the hand and the armor, and on Bedrock the
     * armor slots aren't part of this packet, so they need their own push.
     */
    public void syncInventory() {
        slotEchoGuard.armAll(System.nanoTime());
        stackTrail.clear(); // the client is about to be told everything — no half-move is outstanding
        connection.setInventory(inventory.states(), inventory.counts(), inventoryDisplay());
        heldItemMayHaveChanged();
        armorMayHaveChanged();
    }

    /**
     * Separates a Bedrock client's own inventory move from its echo of a move the <em>server</em> made —
     * armed by every push below, consulted by {@link com.jedrock.core.inventory.ContainerService} before
     * it trusts a client report. See {@link SlotEchoGuard}.
     */
    private final SlotEchoGuard slotEchoGuard = new SlotEchoGuard(INV_SLOTS);

    /**
     * Carries a custom stack's identity across a drag the client made and only reported afterwards — the
     * other half of the same problem. See {@link CustomStackTrail}.
     */
    private final CustomStackTrail stackTrail = new CustomStackTrail();

    /** True while {@code slot} is still inside the echo window of a server-authored push. */
    public boolean isSlotEchoGuarded(int slot) {
        return slotEchoGuard.isGuarded(slot, System.nanoTime());
    }

    /** The trail of the last stack a client report displaced — read and written by {@code ContainerService}. */
    public CustomStackTrail getStackTrail() {
        return stackTrail;
    }

    // ===== Inventory API (scripting-facing; operates on the 36 storage slots 0-35) =====

    @Override
    public int getInventorySize() {
        return STORAGE_SLOTS;
    }

    @Override
    public int getItem(int slot) {
        return (slot >= 0 && slot < STORAGE_SLOTS) ? inventory.stateAt(slot) : 0;
    }

    @Override
    public int getItemCount(int slot) {
        return (slot >= 0 && slot < STORAGE_SLOTS) ? inventory.countAt(slot) : 0;
    }

    @Override
    public void setItem(int slot, int state, int count) {
        if (slot < 0 || slot >= STORAGE_SLOTS) {
            return;
        }
        inventory.set(slot, state, count);
        syncSlot(slot);
    }

    @Override
    public int giveItem(int state, int count) {
        return giveItem(state, count, null);
    }

    /**
     * As {@link #giveItem(int, int)}, but the stacks carry {@code customKey} — the identity that makes
     * them a custom item rather than the ordinary one they are drawn as ({@code null} for a plain give).
     * The one place stacks are handed to a player in bulk, so a script's {@code items.give} and
     * {@code /give} put things in the inventory exactly the same way.
     *
     * @return how many actually fit
     */
    public int giveItem(int state, int count, String customKey) {
        return giveItem(state, count, customKey, com.jedrock.api.item.Enchantments.NONE);
    }

    /** As above, for stacks that arrive enchanted — a defined item that comes with one, or {@code /enchant}. */
    public int giveItem(int state, int count, String customKey,
                        com.jedrock.api.item.Enchantments enchantments) {
        if (state <= 0 || count <= 0) {
            return 0;
        }
        int fit = 0;
        while (fit < count && addToInventory(state, customKey, null, enchantments) >= 0) {
            fit++;
        }
        if (fit > 0) {
            syncInventory();
        }
        return fit;
    }

    @Override
    public int removeItem(int state, int count) {
        if (state <= 0 || count <= 0) {
            return 0;
        }
        int removed = 0;
        while (removed < count && takeItem(state) >= 0) {
            removed++;
        }
        if (removed > 0) {
            syncInventory();
        }
        return removed;
    }

    @Override
    public int countItem(int state) {
        int total = 0;
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            if (inventory.stateAt(slot) == state) {
                total += inventory.countAt(slot);
            }
        }
        return total;
    }

    @Override
    public void clearInventory() {
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            inventory.clear(slot);
        }
        syncInventory();
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
        health = settle(health - Math.max(0, amount));
        return health;
    }

    /**
     * Set health directly (e.g. reset to {@link #MAX_HEALTH} on respawn), clamped to 0..max, and refresh
     * the client's health HUD — the client-facing setter used by scripts and the respawn resets.
     */
    @Override
    public void setHealth(int value) {
        health = settle(value);
        connection.setHealth(health);
    }

    /**
     * The one place a health number is decided: clamp it, offer it to listeners, clamp whatever they say
     * back, and hand over the result. Both ways in go through this, which is the point — a script that
     * wants to know when somebody's health moved should not have to know how it moved.
     *
     * <p>Re-entrancy is the trap here: a listener calling {@code setHealth} from inside the event would
     * come straight back round. A flag holds the door — while a change is settling, a nested one is
     * applied plainly, without announcing itself.
     */
    private int settle(int proposed) {
        int clamped = Math.max(0, Math.min(MAX_HEALTH, proposed));
        EventBus bus = eventBus;
        if (bus == null || !bus.hasListeners(PlayerHealthChangeEvent.class)) {
            return clamped;
        }
        // Checked before "did anything change", because the outer call has not written its own value yet:
        // to a listener calling setHealth from inside the event, health still reads as it did before the
        // change being settled, and comparing values here would miss the write entirely.
        if (settlingHealth) {
            // Don't announce it — that is the loop — but do record it. What the listener wrote is a later
            // decision than the one being settled, and the caller must not overwrite it with a proposal
            // made before the listener ran.
            nestedHealthWrite = true;
            return clamped;
        }
        if (clamped == health) {
            return clamped; // "health changed" has to mean it changed
        }
        settlingHealth = true;
        nestedHealthWrite = false;
        PlayerHealthChangeEvent event;
        try {
            event = bus.post(new PlayerHealthChangeEvent(this, health, clamped));
        } finally {
            settlingHealth = false;
        }
        if (nestedHealthWrite) {
            return health; // last write wins: setHealth inside the handler beats setNewHealth on it
        }
        if (event.isCancelled()) {
            return health; // left exactly where it was
        }
        return Math.max(0, Math.min(MAX_HEALTH, event.getNewHealth()));
    }

    /** True while {@link #settle} is inside a listener — see its note on re-entrancy. */
    private volatile boolean settlingHealth;
    /** Set when a listener changed health directly, so the settling call knows not to undo it. */
    private volatile boolean nestedHealthWrite;

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

    /** The selected hotbar slot (0-8), as last reported by the client. */
    private volatile int heldSlot = 0;

    @Override
    public int getHeldItemSlot() {
        return heldSlot;
    }

    /**
     * The {@linkplain com.jedrock.api.item.CustomItem custom item} key of whatever is in hand, or
     * {@code null} for an ordinary item. A key, not a definition — see {@code ItemRegistry}.
     */
    public String getHeldItemKey() {
        return inventory.customKeyAt(heldSlot);
    }

    /** Record a hotbar switch reported by the client. Out-of-range slots are ignored. */
    public void setHeldItemSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            this.heldSlot = slot;
        }
    }

    @Override
    public int getHeldItem() {
        return inventory.stateAt(heldSlot);
    }

    @Override
    public int getArmor(com.jedrock.api.player.ArmorSlot slot) {
        return inventory.stateAt(slot.inventorySlot());
    }

    /** What the stack in their hand is enchanted with; never null. */
    public com.jedrock.api.item.Enchantments getHeldEnchantments() {
        return inventory.enchantmentsAt(heldSlot);
    }

    /**
     * The combined level of one enchantment across the four worn pieces — vanilla's own way of adding
     * armor enchantments up, and the number protection and thorns are read from. {@code 0} when nothing
     * worn carries it, which is the overwhelmingly common case and costs four array reads.
     */
    public int armorEnchantmentLevel(com.jedrock.api.item.Enchantment enchantment) {
        int total = 0;
        for (com.jedrock.api.player.ArmorSlot slot : com.jedrock.api.player.ArmorSlot.values()) {
            total += inventory.enchantmentsAt(slot.inventorySlot()).level(enchantment);
        }
        return total;
    }

    /** Whether any piece is worn — lets a caller skip an all-empty armor packet. */
    public boolean hasArmor() {
        for (com.jedrock.api.player.ArmorSlot slot : com.jedrock.api.player.ArmorSlot.values()) {
            if (getArmor(slot) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wear (or, with state 0, remove) a piece. The armor slots sit past the 36 storage slots, so this
     * is the only way in; the change syncs to the wearer's own inventory view and, through the
     * equipment hook, onto everyone else's copy of their avatar.
     */
    @Override
    public void setArmor(com.jedrock.api.player.ArmorSlot slot, int state) {
        // Listeners may refuse the piece. Nothing has been drawn anywhere yet, so a refusal simply
        // doesn't happen — unlike the window paths, where the client moved the piece first.
        if (eventBus != null && eventBus.hasListeners(PlayerArmorChangeEvent.class)
                && eventBus.post(new PlayerArmorChangeEvent(this, slot, getArmor(slot), state))
                        .isCancelled()) {
            return;
        }
        inventory.set(slot.inventorySlot(), state, state == 0 ? 0 : 1);
        syncInventory(); // refreshes the wearer's own view and fires the equipment hooks
    }

    /** Chat display name; null = none set, so getDisplayName falls back to the real name. */
    private volatile String displayName;

    @Override
    public String getDisplayName() {
        String shown = displayName;
        return shown != null ? shown : name;
    }

    @Override
    public void setDisplayName(String displayName) {
        this.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
    }

    @Override
    public World getWorld() {
        return world;
    }

    /**
     * The same world, typed — what every core collaborator actually needs. A player's world is the one
     * thing that used to be a constant on the server (there was only ever one); now that a player can be
     * standing in any of several, "which world" is a question asked <em>of the player</em>, and this is
     * where it is answered.
     */
    public CoreWorld getCoreWorld() {
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

    /**
     * How a teleport actually happens. The player itself can only move its own state; telling the client,
     * relaying the move to everyone else and — since worlds became plural — swapping the terrain under
     * them are all the server's business. It installs this on every player it registers, so
     * {@code player.teleport(...)} means the same thing from a command, a script and the api.
     */
    @FunctionalInterface
    public interface Teleporter {
        boolean teleport(CorePlayer player, Location to);
    }

    private volatile Teleporter teleporter;

    public void setTeleporter(Teleporter teleporter) {
        this.teleporter = teleporter;
    }

    @Override
    public void teleport(Location location) {
        Teleporter handler = this.teleporter;
        if (handler != null) {
            handler.teleport(this, location);
            return;
        }
        // No server behind this player (a bare test fixture): move the state and nothing else.
        enterWorld(location);
    }

    /**
     * Move this player's own state — position, and the world if the destination names another one.
     * The inside of a teleport, called once the server has decided one is happening; calling
     * {@link #teleport} from here instead would ask the server to decide all over again.
     */
    public void enterWorld(Location location) {
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
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // Render the markup once here (like sendMessage); the connection just frames the legacy strings.
        connection.sendTitle(ChatText.toLegacy(title == null ? "" : title),
                ChatText.toLegacy(subtitle == null ? "" : subtitle), fadeIn, stay, fadeOut);
    }

    @Override
    public void sendActionBar(String text) {
        connection.sendActionBar(ChatText.toLegacy(text == null ? "" : text));
    }

    @Override
    public void clearTitle() {
        connection.clearTitle();
    }

    @Override
    public void setSidebar(String title, java.util.List<String> lines) {
        // Render the markup here (like sendTitle); the connection frames the legacy strings per version.
        // Read the elements as Object, not String: Rhino's NativeArray *is* a java.util.List, so a script's
        // array arrives unconverted and a concatenated line is a ConsString, not a String — a String local
        // here would checkcast and throw. Every CharSequence stringifies the same way.
        java.util.List<?> raw = lines;
        String renderedTitle = ChatText.toLegacy(title == null ? "" : title);
        String[] rendered = new String[raw == null ? 0 : raw.size()];
        for (int i = 0; i < rendered.length; i++) {
            Object line = raw.get(i);
            rendered[i] = ChatText.toLegacy(line == null ? "" : line.toString());
        }
        // Kept so a connection that can't hold the panel itself can be repainted (see
        // PlayerConnection#sidebarRepaintTicks): the rendered form is exactly what was sent.
        this.sidebarTitle = renderedTitle;
        this.sidebarLines = rendered;
        connection.setSidebar(renderedTitle, rendered);
    }

    @Override
    public void clearSidebar() {
        this.sidebarTitle = null;
        this.sidebarLines = null;
        connection.clearSidebar();
    }

    /** Whether a sidebar is currently shown — the cheap gate the repaint loop reads first. */
    public boolean hasSidebar() {
        return sidebarLines != null;
    }

    /** The sidebar title as it was sent (legacy-rendered), or {@code null} if none is shown. */
    public String getSidebarTitle() {
        return sidebarTitle;
    }

    /** The sidebar lines as they were sent (legacy-rendered), or {@code null} if none is shown. */
    public String[] getSidebarLines() {
        return sidebarLines;
    }

    @Override
    public void setBossBar(String title, float progress, String color) {
        float clamped = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        connection.setBossBar(ChatText.toLegacy(title == null ? "" : title), clamped, bossBarColorId(color));
    }

    @Override
    public void clearBossBar() {
        connection.clearBossBar();
    }

    /** Map a colour name to the canonical boss-bar colour id (the JE wire values); unknown → purple. */
    private static int bossBarColorId(String color) {
        if (color == null) {
            return 5;
        }
        return switch (color.toLowerCase(java.util.Locale.ROOT)) {
            case "pink" -> 0;
            case "blue" -> 1;
            case "red" -> 2;
            case "green" -> 3;
            case "yellow" -> 4;
            case "white" -> 6;
            default -> 5; // purple
        };
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
