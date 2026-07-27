package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.InventoryClickEvent;
import com.jedrock.api.event.player.PlayerArmorChangeEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
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
     * <p><b>Java</b> opens a real, server-authoritative chest window. <b>Neither Bedrock era gets one</b>,
     * and both dead ends are client-verified: the retail 1.1.5 crashes on a block-bound chest window (it
     * builds a chest block-entity only from chunk data, which a virtual menu has none of) and raises no GUI
     * for an entity-bound one, while on 0.14 a menu window simply never comes up. So on Bedrock a menu
     * becomes a <b>text list</b> driven by {@code /pick}, in one of two shapes:
     *
     * <ul>
     *   <li>a <b>button</b> menu lists its labelled slots ({@code labels}), and picking one fires the same
     *       {@link MenuClick} the window would have;</li>
     *   <li>a <b>storage</b> menu (no click handler) lists its <em>contents</em>: picking a slot number
     *       takes that stack, {@code /pick put} puts the held one in, {@code /pick close} is done. The list
     *       redraws after every transfer, so it stays up the way a window would.</li>
     * </ul>
     *
     * <p>That second shape is what makes {@code menus} storage work on Bedrock at all: a window is the one
     * mechanism these clients don't have, so the transfer moved into the list rather than waiting for one.
     * It is the same trade world chests already make on 1.1.5 (click-transfer instead of a window), with
     * commands standing in for the right-click a virtual menu has no block to receive.
     *
     * <p>The only thing still refused is a button menu whose slots carry no labels — a list has nothing to
     * offer there, and unlike storage there is no content to fall back on.
     *
     * @param labels per-slot option labels for the list fallback, or {@code null} entries for plain items
     * @return {@code true} if the menu was shown (as a window or a list), {@code false} if it couldn't be
     */
    public boolean openMenu(CorePlayer player, String title, Container container, String[] labels,
                            MenuClick onClick) {
        PlayerConnection connection = player.getConnection();
        if (connection.getProtocolVersion().isBedrock()) {
            if (openAsList(player, title, container, labels, onClick)) {
                return true; // a button menu with labels
            }
            // No labels: storage (no click handler) becomes a transfer list; a button menu has nothing.
            return onClick == null && openAsStorageList(player, title, container);
        }
        player.openContainer(MENU_WINDOW_ID, container, false, onClick);
        connection.openContainer(MENU_WINDOW_ID, title, container.size(), 0, 0, 0);
        sendChestContents(player, connection);
        return true;
    }

    /**
     * Show a button menu as a text list on a client that can't open a window (either Bedrock era): store
     * it as the player's pending {@link ListMenu} and print the options, which they pick with
     * {@code /pick <label>}. Needs a click handler and at least one labelled button, or there is nothing
     * to offer — the caller decides what an empty return means for its edition.
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

    // ===== The storage list (Bedrock's stand-in for a chest window) =====
    //
    // A button list only ever signals, so it was never enough for storage — the one menu shape that has to
    // move items. Since neither Bedrock era will raise a window for a blockless menu, the transfer moved
    // into the list: an option per occupied slot takes that stack out, `put` puts the held one in, `close`
    // is done. The list is reprinted after every transfer, which is what makes it behave like an open
    // window rather than a one-shot prompt.

    /** Chosen instead of a slot: put the held stack in / close the list. Outside any real slot index. */
    private static final int PUT_SLOT = -1;
    private static final int CLOSE_SLOT = -2;
    private static final String PUT_LABEL = "put";
    private static final String CLOSE_LABEL = "close";

    /**
     * Show a storage menu as a transfer list and make it the player's pending {@link ListMenu}. Always
     * succeeds — an empty container still offers {@code put}, which is how anything gets into it.
     */
    private boolean openAsStorageList(CorePlayer player, String title, Container container) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<Integer> states = new java.util.ArrayList<>();
        for (int slot = 0; slot < container.size(); slot++) {
            if (container.isEmpty(slot)) {
                continue; // an empty slot is nothing to take — only occupied ones are options
            }
            labels.add(Integer.toString(slot + 1)); // 1-based: what the player reads and types
            slots.add(slot);
            states.add(container.stateAt(slot));
        }
        labels.add(PUT_LABEL);
        slots.add(PUT_SLOT);
        states.add(0);
        labels.add(CLOSE_LABEL);
        slots.add(CLOSE_SLOT);
        states.add(0);

        player.setPendingMenu(new ListMenu(title, labels,
                slots.stream().mapToInt(Integer::intValue).toArray(),
                states.stream().mapToInt(Integer::intValue).toArray(),
                (p, slot, state) -> onStorageListPick(p, title, container, slot)));
        printStorageList(player, title, container);
        return true;
    }

    /** Draw the list: the title, a line per occupied slot, then the two verbs. */
    private void printStorageList(CorePlayer player, String title, Container container) {
        player.sendMessage(title == null || title.isEmpty() ? "{gold}Хранилище:" : title);
        boolean any = false;
        for (int slot = 0; slot < container.size(); slot++) {
            if (container.isEmpty(slot)) {
                continue;
            }
            any = true;
            // There is no item-name table in the core (a block is an id by design), so a stack is shown as
            // its state and count and chosen by slot number — the number is the label, not the name.
            player.sendMessage(" {gray}• {white}/pick " + (slot + 1)
                    + " {dark_gray}— {gray}#" + container.stateAt(slot) + " ×" + container.countAt(slot));
        }
        if (!any) {
            player.sendMessage(" {dark_gray}(пусто)");
        }
        player.sendMessage(" {gray}• {white}/pick " + PUT_LABEL + " {dark_gray}— положить предмет из руки");
        player.sendMessage(" {gray}• {white}/pick " + CLOSE_LABEL + " {dark_gray}— закрыть");
    }

    /**
     * One pick on a storage list: take a stack, put the held one in, or close. Anything that moved is
     * followed by a redraw, so the player is looking at the container as it now stands — the list's
     * equivalent of a window resync.
     */
    private void onStorageListPick(CorePlayer player, String title, Container container, int slot) {
        if (slot == CLOSE_SLOT) {
            player.setPendingMenu(null);
            player.sendMessage("{gray}Закрыто.");
            return;
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (slot == PUT_SLOT) {
            int held = player.getHeldItemSlot();
            int have = heldCount(player, held);
            if (have <= 0) {
                player.sendMessage("{gray}В руке ничего нет");
            } else {
                int moved = putStack(player, container, held, creative);
                player.sendMessage(moved > 0
                        ? "{gray}Положено ×" + moved + (moved < have ? " {dark_gray}(хранилище полно)" : "")
                        : "{gray}Хранилище полно");
            }
        } else if (slot >= 0 && slot < container.size()) {
            int have = container.countAt(slot);
            int moved = takeStack(player, container, slot, creative);
            if (moved > 0) {
                player.sendMessage("{gray}" + (creative ? "Убрано ×" : "Взято ×") + moved
                        + (moved < have ? " {dark_gray}(инвентарь полон)" : ""));
            } else {
                player.sendMessage("{gray}Инвентарь полон");
            }
        }
        // A menu's contents are transient (never world state), so nothing is marked dirty here.
        openAsStorageList(player, title, container); // redraw, and re-arm the pick
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
            int have = chest.countAt(i);
            int moved = takeStack(player, chest, i, creative);
            if (moved > 0) {
                world.markDirty();
                player.sendMessage("{gray}" + (creative ? "Убрано из сундука ×" : "Взято из сундука ×") + moved
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
        int have = heldCount(player, heldSlot);
        if (have <= 0) {
            if (have == 0) {
                player.sendMessage("{gray}В руке ничего нет");
            }
            return;
        }
        int moved = putStack(player, chest, heldSlot, creative);
        if (moved > 0) {
            world.markDirty();
            player.sendMessage("{gray}Положено в сундук ×" + moved
                    + (moved < have ? " {dark_gray}(сундук полон)" : ""));
        } else {
            player.sendMessage("{gray}Сундук полон");
        }
    }

    // ===== The two transfer primitives =====
    //
    // Shared by the world-chest click-transfer and the Bedrock storage list, because the rule that keeps
    // them honest is subtle enough to be worth having in exactly one place: a CREATIVE player's inventory
    // is infinite and client-managed, so handing them real items out of a container would let a
    // put→take cycle mint items (the duplication this cost us once already — a deposit never consumes an
    // infinite hand, so taking the copy back is pure gain). Creative therefore takes by *destroying* the
    // stack and puts without consuming. Survival moves real items both ways and is symmetric.
    //
    // Both return how many items actually moved, and neither words a message or marks the world dirty —
    // that belongs to the caller, which knows whether it is holding a world chest or a transient menu.

    /** How many items the player holds in {@code heldSlot}, or {@code -1} if that isn't a hotbar slot. */
    private static int heldCount(CorePlayer player, int heldSlot) {
        if (heldSlot < 0 || heldSlot >= 9) {
            return -1;
        }
        Container inv = player.getInventory();
        return inv.stateAt(heldSlot) == 0 ? 0 : inv.countAt(heldSlot);
    }

    /** Move {@code container[slot]} into the player's inventory (as much as fits). @return how many moved */
    private int takeStack(CorePlayer player, Container container, int slot, boolean creative) {
        if (container.isEmpty(slot)) {
            return 0;
        }
        int state = container.stateAt(slot);
        int have = container.countAt(slot);
        if (creative) {
            container.clear(slot); // no real items to a creative player — just clear the stack
            return have;
        }
        int prev = -1, moved = 0;
        for (int c = 0; c < have; c++) {
            int into = player.addToInventory(state);
            if (into < 0) break; // inventory full
            if (into != prev) {
                if (prev >= 0) player.syncSlot(prev);
                prev = into;
            }
            moved++;
        }
        if (prev >= 0) player.syncSlot(prev);
        if (moved > 0) {
            container.set(slot, have - moved > 0 ? state : 0, have - moved);
        }
        return moved;
    }

    /** Move the player's held stack into {@code container} (as much as fits). @return how many moved */
    private int putStack(CorePlayer player, Container container, int heldSlot, boolean creative) {
        int have = heldCount(player, heldSlot);
        if (have <= 0) {
            return 0;
        }
        Container inv = player.getInventory();
        int state = inv.stateAt(heldSlot);
        int moved = 0;
        for (int c = 0; c < have; c++) {
            if (container.give(state, 0, container.size()) < 0) break; // container full
            moved++;
        }
        if (moved > 0 && !creative) { // survival consumes what was deposited; creative's hand is infinite
            inv.set(heldSlot, have - moved > 0 ? state : 0, have - moved);
            player.syncSlot(heldSlot);
        }
        return moved;
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
    /**
     * Re-send {@code container} to every player who has it open. Called when something other than a click
     * changed it — a script writing into a world chest reaches the same container object a player's open
     * window is bound to, so their screen has to be told, or they keep looking at a stale copy and their
     * next click is judged against contents that no longer exist.
     */
    public void refreshViewers(Container container) {
        for (CorePlayer player : players.online()) {
            if (player.getOpenContainer() == container) {
                sendChestContents(player, player.getConnection());
            }
        }
    }

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
        } else if (windowId == 0 && slot < CorePlayer.STORAGE_SLOTS) {
            // The player's own inventory (PE window 0: 0-8 hotbar, 9-35 main). Bedrock is
            // client-authoritative here in BOTH modes: the client moves the item in its own GUI and this
            // report is the only notice the server gets. In CREATIVE it is a pure mirror (kept so a chest
            // deposit knows the held item). In SURVIVAL it has to be applied too — ignoring it wholesale
            // (which is what closed the chest-deposit dupe) meant the next full resync, i.e. closing the
            // inventory, put every moved item back where the server still had it, so a survival player
            // could not rearrange their inventory at all.
            //
            // What must still be refused is the *echo*: the same client reports a slot the server has just
            // changed, carrying the value it held before. Content can't tell an echo from a real move, so
            // timing does — a freshly pushed slot is guarded and the server's value is re-asserted instead
            // (the client drew the stale one, so it needs correcting either way). See CorePlayer.
            Container inv = player.getInventory();
            if (player.getGameMode() != GameMode.SURVIVAL) {
                inv.set(slot, state, count); // creative: mirror it, the client is the owner
                return;
            }
            if (player.isSlotEchoGuarded(slot)) {
                player.syncSlot(slot);
                return;
            }
            int before = inv.stateAt(slot);
            inv.set(slot, state, count);
            if (slot == player.getHeldItemSlot() && inv.stateAt(slot) != before) {
                broadcast.heldItem(player); // the hand itself changed — redraw it on every other client
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
