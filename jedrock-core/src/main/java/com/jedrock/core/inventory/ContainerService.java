package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.InventoryClickEvent;
import com.jedrock.api.event.player.PlayerArmorChangeEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;

/**
 * Everything that moves an item between slots: the player window, the creative mirror, and chests.
 *
 * <p>The server only ever <em>stores and moves</em> items — there is no crafting, no smelting and no item
 * entity, so a stack that doesn't fit anywhere simply stops existing. What makes this worth its own class
 * is that the two editions disagree about who owns a window: <b>Java</b> is server-authoritative and gets
 * the player inventory nested inside the chest window, while <b>Bedrock</b> reports its own moves and
 * (on 1.1.5, which crashes on a real chest window) trades through a click-transfer instead. Both funnel
 * through here so the survival inventory has exactly one owner and a deposit can't duplicate a stack.
 */
public final class ContainerService {

    private final PlayerRegistry players;
    private final CoreWorld world;
    private final EventBus events;
    private final PlayerBroadcast broadcast;

    public ContainerService(PlayerRegistry players, CoreWorld world, EventBus events,
                            PlayerBroadcast broadcast) {
        this.players = players;
        this.world = world;
        this.events = events;
        this.broadcast = broadcast;
    }

    public void onWindowClick(PlayerConnection connection, int coreSlot, int button, boolean shift) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
            return; // creative manages its own inventory client-side — don't apply or resync over it
        }
        Container inv = player.getInventory();
        Cursor cur = player.getCursor();
        // Let a listener veto the click before it's applied. A cancel still falls through to the resync
        // below, so the client's optimistic move is reverted.
        boolean vetoed = events.hasListeners(InventoryClickEvent.class)
                && events.post(new InventoryClickEvent(player, coreSlot, button, shift)).isCancelled();
        // coreSlot < 0 = an unbacked slot (crafting grid) or a click mode we don't model — resync only.
        if (!vetoed && coreSlot >= 0 && coreSlot < inv.size()) {
            // A click can dress or undress the player — dragging a helmet in, or shifting one out — and
            // what it ends up doing depends on the cursor, so the armor is compared after the fact
            // rather than predicted. Only snapshotted when somebody is listening.
            int[] wornBefore = armorSnapshot(player);
            if (shift) {
                // Quick-move to the "other" region: hotbar↔main, and armor/off-hand back into storage.
                int from, to;
                if (coreSlot < 9) { from = 9; to = 36; }        // hotbar → main
                else if (coreSlot < 36) { from = 0; to = 9; }   // main → hotbar
                else { from = 0; to = 36; }                     // armor / off-hand → storage
                InventoryClick.shift(inv, coreSlot, from, to);
            } else {
                InventoryClick.normal(inv, cur, coreSlot, button == 1);
            }
            vetoArmorChanges(player, wornBefore);
        }
        // Server is authoritative: resync the whole window + cursor, overriding any client misprediction.
        player.syncInventory();
        connection.setCursorItem(cur.state(), cur.count());
    }

    public void onWindowClose(PlayerConnection connection) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        // In creative, only intervene when closing a chest — closing the creative menu must not disturb it.
        if (player.getGameMode() != GameMode.SURVIVAL && !player.hasContainerOpen()) {
            return;
        }
        player.closeContainer(); // a chest, if any, is no longer open
        Cursor cur = player.getCursor();
        // Return any carried item to storage; whatever doesn't fit is lost (no item entities to drop).
        while (!cur.isEmpty() && player.addToInventory(cur.state()) >= 0) {
            cur.set(cur.state(), cur.count() - 1);
        }
        cur.clear();
        player.syncInventory();
        connection.setCursorItem(0, 0);
    }

    /**
     * Window id for a player's open chest (a player has at most one container open). Must be ≥ 2: MCPE
     * reserves 0/1 (PMMP assigns custom windows from {@code max(2, …)}) and a 1.1.5 client crashes on a
     * ContainerOpen with id 1; 119-122 are the off-hand / armor / creative / hotbar windows. 10 is safe
     * on every edition (Java accepts any container window id).
     */
    private static final int CHEST_WINDOW_ID = 10;
    /** Window id for a script-opened virtual menu; distinct from the chest id (a player has one open anyway). */
    private static final int MENU_WINDOW_ID = 11;

    /**
     * Open a script-owned virtual menu — a chest window backed by {@code container}, not by any world
     * block. A non-null {@code onClick} makes it a read-only button menu whose clicks call the handler;
     * a null one makes it a transient storage container (its edits are not persisted).
     *
     * <p>Java and Bedrock <b>0.14</b>, which open real chest windows. The retail <b>1.1.5</b> client crashes
     * on a chest window (it binds the GUI to a block tile a virtual menu has no equivalent of — the same
     * reason world chests trade through click-transfer there), so opening one to a 1.1.5 player is refused.
     * On the client-authoritative PE window a storage menu works cleanly; a button menu's read-only
     * revert is best-effort (see {@link #onContainerSetSlot}).
     *
     * <p>The retail <b>1.1.5</b> can't show a window, so a <em>button</em> menu there degrades to a text
     * <b>list</b>: the labelled slots ({@code labels}) become options the player chooses with {@code /pick}.
     * A menu with no labels (or no click handler) has nothing to list, so it's refused on 1.1.5.
     *
     * @param labels per-slot option labels for the list fallback, or {@code null} entries for plain items
     * @return {@code true} if the menu was shown (as a window or a list), {@code false} if it couldn't be
     */
    public boolean openMenu(CorePlayer player, String title, Container container, String[] labels,
                            MenuClick onClick) {
        PlayerConnection connection = player.getConnection();
        if (connection.getProtocolVersion() == ProtocolVersion.PE_1_1_5) {
            return openAsList(player, title, container, labels, onClick);
        }
        player.openContainer(MENU_WINDOW_ID, container, false, onClick);
        connection.openContainer(MENU_WINDOW_ID, title, container.size(), 0, 0, 0);
        sendChestContents(player, connection);
        return true;
    }

    /**
     * Show a button menu as a text list on a client that can't open a window (1.1.5): store it as the
     * player's pending {@link ListMenu} and print the options, which they pick with {@code /pick <label>}.
     * Needs a click handler and at least one labelled button, or there is nothing to offer.
     */
    private boolean openAsList(CorePlayer player, String title, Container container, String[] labels,
                               MenuClick onClick) {
        if (onClick == null || labels == null) {
            return false;
        }
        java.util.List<String> optionLabels = new java.util.ArrayList<>();
        java.util.List<Integer> optionSlots = new java.util.ArrayList<>();
        java.util.List<Integer> optionStates = new java.util.ArrayList<>();
        for (int slot = 0; slot < labels.length && slot < container.size(); slot++) {
            if (labels[slot] != null && !labels[slot].isEmpty()) {
                optionLabels.add(labels[slot]);
                optionSlots.add(slot);
                optionStates.add(container.stateAt(slot));
            }
        }
        if (optionLabels.isEmpty()) {
            return false; // no labelled buttons — nothing a list can offer
        }
        int[] slots = optionSlots.stream().mapToInt(Integer::intValue).toArray();
        int[] states = optionStates.stream().mapToInt(Integer::intValue).toArray();
        player.setPendingMenu(new ListMenu(title, optionLabels, slots, states, onClick));
        player.sendMessage(title == null || title.isEmpty() ? "{gold}Pick one:" : title);
        for (String label : optionLabels) {
            player.sendMessage(" {gray}• {white}/pick " + label);
        }
        return true;
    }

    public boolean onUseBlock(PlayerConnection connection, int x, int y, int z) {
        CorePlayer clicker = players.getByConnectionOrNull(connection);
        // Let listeners gate the right-click on any block. Cancelling consumes the click — no chest opens
        // and no block is placed against it — so a plugin can protect a block or handle it itself.
        if (clicker != null && events.hasListeners(PlayerInteractBlockEvent.class)) {
            int state = world.getBlockId(x, y, z);
            if (events.post(new PlayerInteractBlockEvent(clicker, x, y, z, state)).isCancelled()) {
                return true; // consumed: suppress both the chest open and any placement
            }
        }
        if (Blocks.idOf(world.getBlockId(x, y, z)) != Blocks.CHEST) {
            return false; // not interactable — let the caller place the held block
        }
        // A chest: consume the right-click (so no block is placed on it) and open it. Works in creative
        // too — the creative inventory is mirrored server-side via onCreativeSetSlot, so the chest's
        // player-inventory half is tracked.
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player != null) {
            Container chest = world.getChestContainer(x, y, z);
            player.openContainer(CHEST_WINDOW_ID, chest);
            connection.openContainer(CHEST_WINDOW_ID, "Chest", 27, x, y, z);
            sendChestContents(player, connection);
        }
        return true;
    }

    public boolean onChestInteract(PlayerConnection connection, int x, int y, int z, int heldSlot) {
        if (Blocks.idOf(world.getBlockId(x, y, z)) != Blocks.CHEST) {
            return false; // not a chest — let the caller place the held block
        }
        // Click-transfer chest (the retail 1.1.5 client crashes on a real chest window): a plain
        // right-click withdraws the first stack, a sneaking right-click deposits the held hotbar slot.
        // Works in both modes — creative deposits its (infinite) held item without consuming it, and its
        // held item is mirrored server-side from the client's MobEquipment (see PeSession). The click is
        // always consumed so no block is placed on the chest.
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player != null) {
            Container chest = world.getChestContainer(x, y, z);
            boolean creative = player.getGameMode() == GameMode.CREATIVE;
            if (player.isSneaking()) {
                chestDeposit(player, chest, heldSlot, creative);
            } else {
                chestWithdraw(player, chest, creative);
            }
        }
        return true; // it's a chest — always suppress the placement
    }

    /**
     * Right-click transfer out of the chest, one stack per click. In <b>survival</b> the first non-empty
     * stack moves into the player's inventory (as much as fits). In <b>creative</b> the player's inventory
     * is infinite and client-managed, so handing real items over would let a deposit→withdraw cycle mint
     * items (the reported duplication: deposit never consumes an infinite hand, so withdrawing the copy is
     * pure gain). Instead a creative click just removes the stack from the chest — an edit of its contents.
     */
    private void chestWithdraw(CorePlayer player, Container chest, boolean creative) {
        for (int i = 0; i < chest.size(); i++) {
            if (chest.isEmpty(i)) {
                continue;
            }
            int state = chest.stateAt(i);
            int have = chest.countAt(i);
            if (creative) {
                chest.clear(i);                 // no real items to a creative player — just clear the stack
                world.markDirty();
                player.sendMessage("{gray}Убрано из сундука ×" + have);
                return;
            }
            int prev = -1, moved = 0;
            for (int c = 0; c < have; c++) {
                int slot = player.addToInventory(state);
                if (slot < 0) break; // inventory full
                if (slot != prev) {
                    if (prev >= 0) player.syncSlot(prev);
                    prev = slot;
                }
                moved++;
            }
            if (prev >= 0) player.syncSlot(prev);
            if (moved > 0) {
                chest.set(i, have - moved > 0 ? state : 0, have - moved);
                world.markDirty();
                player.sendMessage("{gray}Взято из сундука ×" + moved
                        + (moved < have ? " {dark_gray}(инвентарь полон)" : ""));
            }
            return; // one stack per click
        }
        player.sendMessage("{gray}Сундук пуст");
    }

    /**
     * Deposit the player's held hotbar slot ({@code heldSlot}, 0-8) into the chest (as much as fits).
     * The amount is exactly what the slot holds (in creative that comes from the client's MobEquipment
     * mirror). In survival the deposited items are consumed from the slot; in {@code creative} the hand is
     * infinite, so the slot is left untouched — but the count is honest, not a forced stack, so a
     * deposit→withdraw cycle can't inflate.
     */
    private void chestDeposit(CorePlayer player, Container chest, int heldSlot, boolean creative) {
        if (heldSlot < 0 || heldSlot >= 9) {
            return;
        }
        Container inv = player.getInventory();
        int state = inv.stateAt(heldSlot);
        int have = inv.countAt(heldSlot);
        if (state == 0 || have <= 0) {
            player.sendMessage("{gray}В руке ничего нет");
            return;
        }
        int moved = 0;
        for (int c = 0; c < have; c++) {
            if (chest.give(state, 0, chest.size()) < 0) break; // chest full
            moved++;
        }
        if (moved > 0) {
            if (!creative) { // survival consumes the deposited items; creative's are infinite
                inv.set(heldSlot, have - moved > 0 ? state : 0, have - moved);
                player.syncSlot(heldSlot);
            }
            world.markDirty();
            player.sendMessage("{gray}Положено в сундук ×" + moved
                    + (moved < have ? " {dark_gray}(сундук полон)" : ""));
        } else {
            player.sendMessage("{gray}Сундук полон");
        }
    }

    public void onChestClick(PlayerConnection connection, int windowSlot, int button, boolean shift) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null || !player.hasContainerOpen()) {
            return; // works in creative too — the chest window is server-authoritative in both modes
        }
        Container chest = player.getOpenContainer();
        int chestSize = chest.size();
        boolean inChest = windowSlot >= 0 && windowSlot < chestSize;

        // A button menu: its slots never move items — a click on one is a signal, and the window is
        // redrawn as it was. The handler runs (under the script lock, in the menu's implementation).
        MenuClick menu = player.getOpenMenuClick();
        if (menu != null) {
            if (inChest) {
                menu.onClick(player, windowSlot, chest.stateAt(windowSlot));
            }
            sendChestContents(player, connection); // resync: nothing moved
            return;
        }

        Container inv = player.getInventory();
        // Chest-window layout for an N-slot chest: 0..N-1 the chest, then the player's main (core 9-35),
        // then the hotbar (core 0-8).
        Container target;
        int index;
        if (inChest) {
            target = chest; index = windowSlot;
        } else if (windowSlot >= chestSize && windowSlot < chestSize + 27) {
            target = inv; index = 9 + (windowSlot - chestSize);
        } else if (windowSlot >= chestSize + 27 && windowSlot < chestSize + 36) {
            target = inv; index = windowSlot - (chestSize + 27);
        } else {
            target = null; index = -1; // outside / unmodelled — resync only
        }
        if (target != null) {
            if (shift) {
                // Quick-move across the two containers: chest → player storage, or player → chest.
                if (inChest) {
                    InventoryClick.shiftTo(chest, index, inv, 0, 36);
                } else {
                    InventoryClick.shiftTo(inv, index, chest, 0, chestSize);
                }
            } else {
                InventoryClick.normal(target, player.getCursor(), index, button == 1);
            }
            if (player.isOpenContainerPersistent()) {
                world.markDirty(); // a world-chest edit must be persisted; a menu's is transient
            }
        }
        sendChestContents(player, connection);
    }

    /**
     * Push the open chest window's contents. The two editions frame it differently: <b>Java</b> puts the
     * player inventory <em>inside</em> the chest window (27 chest + 27 main + 9 hotbar = 63 slots), and is
     * server-authoritative (also resyncs the cursor); <b>Bedrock</b>'s chest window is just the 27 chest
     * slots — the player inventory is the separate window 0 the client already owns.
     */
    private void sendChestContents(CorePlayer player, PlayerConnection connection) {
        Container chest = player.getOpenContainer();
        int windowId = player.getOpenWindowId();
        if (connection.getProtocolVersion().isBedrock()) {
            connection.setWindowItems(windowId, chest.states(), chest.counts());
            return;
        }
        int chestSize = chest.size();
        int[] ps = player.inventoryStates();
        int[] pc = player.inventoryCounts();
        int[] states = new int[chestSize + 36];
        int[] counts = new int[chestSize + 36];
        for (int i = 0; i < chestSize; i++) {          // chest
            states[i] = chest.stateAt(i);
            counts[i] = chest.countAt(i);
        }
        for (int i = 0; i < 27; i++) {                 // player main (core 9-35)
            states[chestSize + i] = ps[9 + i];
            counts[chestSize + i] = pc[9 + i];
        }
        for (int i = 0; i < 9; i++) {                  // player hotbar (core 0-8)
            states[chestSize + 27 + i] = ps[i];
            counts[chestSize + 27 + i] = pc[i];
        }
        connection.setWindowItems(windowId, states, counts);
        connection.setCursorItem(player.getCursor().state(), player.getCursor().count());
    }

    public void onContainerSetSlot(PlayerConnection connection, int windowId, int slot, int state, int count) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null || slot < 0) {
            return;
        }
        // Bedrock is client-authoritative for an open chest window: it already moved the item and just
        // tells us the new slot value.
        if (player.hasContainerOpen() && windowId == player.getOpenWindowId()) {
            Container chest = player.getOpenContainer();
            MenuClick menu = player.getOpenMenuClick();
            if (menu != null) {
                // A button menu: slots are read-only. Fire the click for the tapped slot, then re-send the
                // window so the client's optimistic move is undone. (Cross-window moves on the client-
                // authoritative PE path can't be perfectly reverted — button menus there are best-effort.)
                if (slot < chest.size()) {
                    menu.onClick(player, slot, chest.stateAt(slot));
                }
                connection.setWindowItems(windowId, chest.states(), chest.counts());
                return;
            }
            if (slot < chest.size()) {
                chest.set(slot, state, count);
                if (player.isOpenContainerPersistent()) {
                    world.markDirty(); // a world-chest edit persists; a menu's is transient
                }
            }
        } else if (windowId == 0 && player.getGameMode() == GameMode.CREATIVE) {
            // The player's own inventory (PE window 0: 0-8 hotbar, 9-35 main). Only trust the client's
            // report in CREATIVE, where the inventory is client-authoritative and we merely mirror it so a
            // chest deposit knows the held item. In SURVIVAL the server owns the inventory (mining, placing
            // and chest transfers all flow through it), so a client echo must be IGNORED — otherwise the
            // 1.1.5 client's ContainerSetSlot echo right after a chest deposit re-adds the just-moved stack,
            // duplicating it (the item ends up in the chest AND back in the inventory).
            Container inv = player.getInventory();
            if (slot < 36) {
                inv.set(slot, state, count);
            }
        }
    }

    public void onCreativeSetSlot(PlayerConnection connection, int coreSlot, int state, int count) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null || player.getGameMode() != GameMode.CREATIVE) {
            return; // only a creative client sets slots this way — ignore an unexpected survival sender
        }
        Container inv = player.getInventory();
        if (coreSlot >= 0 && coreSlot < inv.size()) {
            int[] wornBefore = armorSnapshot(player);
            inv.set(coreSlot, state, count); // mirror only; the creative client already shows it
            // A creative player dragging armor into slots 36-39 dresses their avatar for everyone else;
            // dropping something into the held slot redraws the hand the same way.
            if (coreSlot >= ArmorSlot.HELMET.inventorySlot() && coreSlot <= ArmorSlot.BOOTS.inventorySlot()) {
                if (vetoArmorChanges(player, wornBefore)) {
                    // Refused: the creative client already drew the piece on itself, so correct it with
                    // the slot as the server now holds it — the same correction a refused edit gets.
                    player.syncSlot(coreSlot);
                    return;
                }
                broadcast.armor(player);
            } else if (coreSlot == player.getHeldItemSlot()) {
                broadcast.heldItem(player);
            }
        }
    }

    public void onHeldSlotChange(PlayerConnection connection, int slot) {
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        // Let listeners refuse the switch. Only a real move between hotbar slots is announced — a client
        // re-reporting the slot it already holds isn't a choice the player made. Cancelling means the
        // server doesn't record the switch, so nothing that reads the held item sees it and no other
        // client redraws the hand; the switcher's own hotbar stays where they put it (no edition here has
        // a packet to move it back).
        int previousSlot = player.getHeldItemSlot();
        if (slot >= 0 && slot < 9 && slot != previousSlot
                && events.hasListeners(PlayerHeldItemChangeEvent.class)) {
            PlayerHeldItemChangeEvent event = new PlayerHeldItemChangeEvent(player, previousSlot, slot,
                    player.getHeldItem(), player.getInventory().stateAt(slot));
            if (events.post(event).isCancelled()) {
                return;
            }
        }
        player.setHeldItemSlot(slot);
        broadcast.heldItem(player);
    }

    /**
     * The four worn states, or {@code null} when nothing is listening for armor changes — the snapshot
     * exists only to compare against, so an unlistened server doesn't take one.
     */
    private int[] armorSnapshot(CorePlayer player) {
        if (!events.hasListeners(PlayerArmorChangeEvent.class)) {
            return null;
        }
        ArmorSlot[] slots = ArmorSlot.values();
        int[] worn = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            worn[i] = player.getArmor(slots[i]);
        }
        return worn;
    }

    /**
     * Post one {@link PlayerArmorChangeEvent} per piece that {@code wornBefore} says has changed, and put
     * back any piece a listener refused. The caller resyncs the window afterwards, which is what actually
     * tells the client about the reversal.
     *
     * @return {@code true} if at least one change was refused
     */
    private boolean vetoArmorChanges(CorePlayer player, int[] wornBefore) {
        if (wornBefore == null) {
            return false; // nobody listening — nothing was snapshotted
        }
        boolean refused = false;
        Container inv = player.getInventory();
        for (ArmorSlot slot : ArmorSlot.values()) {
            int previous = wornBefore[slot.ordinal()];
            int next = player.getArmor(slot);
            if (previous == next) {
                continue;
            }
            if (events.post(new PlayerArmorChangeEvent(player, slot, previous, next)).isCancelled()) {
                // Written straight to the container: setArmor would post the event a second time.
                inv.set(slot.inventorySlot(), previous, previous == 0 ? 0 : 1);
                refused = true;
            }
        }
        return refused;
    }
}
