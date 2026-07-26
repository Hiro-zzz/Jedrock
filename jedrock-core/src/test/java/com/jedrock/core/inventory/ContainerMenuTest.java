package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual menus at the core level: opening one, the read-only button behaviour (a click is a signal, no
 * item moves), the storage behaviour (moves apply but aren't persisted), the generalized non-27 window
 * slot math, and the Bedrock refusal.
 */
class ContainerMenuTest {

    private static final int STONE = 1 << 4;
    private static final int DIAMOND = 264 << 4;

    private final CoreWorld world = new CoreWorld("menu", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ContainerService containers =
            new ContainerService(players, world, events, new PlayerBroadcast(players));

    private CorePlayer join(Conn conn) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        players.add(p);
        return p;
    }

    @Test
    void openingAMenuSendsTheWindowAndMarksItOpen() {
        Conn conn = new Conn(ProtocolVersion.JE_1_12_2);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(0, DIAMOND, 1);

        boolean opened = containers.openMenu(player, "Shop", menu, null, null);

        assertTrue(opened);
        assertTrue(player.hasContainerOpen());
        assertFalse(player.isOpenContainerPersistent(), "a menu is transient, not a world chest");
        assertEquals("Shop", conn.windowTitle);
        assertEquals(27, conn.windowSlots);
        assertEquals(27 + 36, conn.lastWindowItems.length, "chest slots + player inventory");
        assertEquals(DIAMOND, conn.lastWindowItems[0]);
    }

    @Test
    void aRetail115PlayerIsRefused() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5);
        CorePlayer player = join(conn);

        boolean opened = containers.openMenu(player, "Shop", new Container(27), null, null);

        assertFalse(opened, "the 1.1.5 client crashes on a chest window");
        assertFalse(player.hasContainerOpen());
    }

    @Test
    void aRetail115ButtonMenuBecomesAPickableList() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5);
        CorePlayer player = join(conn);
        Container menu = new Container(9);
        menu.set(2, DIAMOND, 1);
        menu.set(4, STONE, 1);
        String[] labels = new String[9];
        labels[2] = "Warrior";
        labels[4] = "Archer";
        int[] picked = {-1, -1};

        boolean opened = containers.openMenu(player, "Class", menu, labels,
                (p, slot, state) -> { picked[0] = slot; picked[1] = state; });

        assertTrue(opened, "a button menu with labels degrades to a list, not a refusal");
        assertFalse(player.hasContainerOpen(), "no window on 1.1.5 — a list instead");
        assertEquals(2, player.getPendingMenu().labels().size());
        assertTrue(conn.messages.stream().anyMatch(m -> m.contains("/pick Warrior")), conn.messages.toString());

        // Picking fires the click for that label's slot and item.
        assertTrue(player.getPendingMenu().pick(player, "archer"), "case-insensitive match");
        assertEquals(4, picked[0]);
        assertEquals(STONE, picked[1]);
    }

    @Test
    void thePickCommandChoosesFromThePendingListAndCompletesTheLabels() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5);
        CorePlayer player = join(conn);
        Container menu = new Container(9);
        menu.set(2, DIAMOND, 1);
        String[] labels = new String[9];
        labels[2] = "Warrior";
        int[] picked = {-1};
        containers.openMenu(player, "Class", menu, labels, (p, slot, state) -> picked[0] = slot);

        com.jedrock.core.command.PickCommand pick = new com.jedrock.core.command.PickCommand();
        assertEquals(java.util.List.of("Warrior"), pick.complete(null, player, new String[]{"War"}),
                "the pending menu's labels complete");

        pick.execute(null, player, new String[]{"warrior"});

        assertEquals(2, picked[0], "the pick fired the click for that label's slot");
        assertTrue(player.getPendingMenu() == null, "the list is consumed after a pick");
    }

    @Test
    void aRetail115MenuWithNoLabelsIsStillRefused() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5);
        CorePlayer player = join(conn);
        Container menu = new Container(9);
        menu.set(0, DIAMOND, 1); // an item, but no label — nothing a list can offer

        boolean opened = containers.openMenu(player, "X", menu, new String[9],
                (p, slot, state) -> { });

        assertFalse(opened, "no labelled buttons, so the list has nothing and is refused");
    }

    @Test
    void a014PlayerCanOpenAMenu() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(0, DIAMOND, 1);

        boolean opened = containers.openMenu(player, "Shop", menu, null, null);

        assertTrue(opened, "0.14 opens a real chest window");
        assertTrue(player.hasContainerOpen());
        assertEquals(27, conn.lastWindowItems.length, "PE window is just the chest slots");
        assertEquals(DIAMOND, conn.lastWindowItems[0]);
    }

    @Test
    void a014ButtonMenuWithLabelsBecomesAPickableList() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(9);
        menu.set(2, DIAMOND, 1);
        String[] labels = new String[9];
        labels[2] = "Warrior";
        int[] picked = {-1};

        boolean opened = containers.openMenu(player, "Class", menu, labels, (p, slot, state) -> picked[0] = slot);

        assertTrue(opened);
        assertFalse(player.hasContainerOpen(), "a menu window doesn't come up on 0.14 — the list does");
        assertEquals(1, player.getPendingMenu().labels().size());
        assertTrue(conn.messages.stream().anyMatch(m -> m.contains("/pick Warrior")), conn.messages.toString());

        assertTrue(player.getPendingMenu().pick(player, "Warrior"));
        assertEquals(2, picked[0], "the same click the window would have fired");
    }

    @Test
    void a014ButtonMenuClickRoutesThroughContainerSetSlotAndReverts() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(3, DIAMOND, 1);
        int[] clicked = {-1, -1};
        containers.openMenu(player, "Menu", menu, null, (p, slot, state) -> { clicked[0] = slot; clicked[1] = state; });
        int windowId = player.getOpenWindowId();

        // The client (authoritative) reports it emptied the button slot; the menu fires the click and
        // re-sends the window so the button is restored.
        containers.onContainerSetSlot(conn, windowId, 3, 0, 0);

        assertEquals(3, clicked[0], "the handler saw the tapped slot");
        assertEquals(DIAMOND, clicked[1], "and the button item");
        assertEquals(DIAMOND, menu.stateAt(3), "the button is untouched (the report was rejected)");
        assertEquals(DIAMOND, conn.lastWindowItems[3], "the window was re-sent to revert the client");
    }

    @Test
    void a014StorageMenuAppliesTheClientsMoveButDoesNotPersist() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        containers.openMenu(player, "Bag", menu, null, null); // a fresh, unbaked world starts clean
        int windowId = player.getOpenWindowId();

        containers.onContainerSetSlot(conn, windowId, 5, DIAMOND, 2); // the client says it put items in slot 5

        assertEquals(DIAMOND, menu.stateAt(5), "the client-authoritative move was applied");
        assertEquals(2, menu.countAt(5));
        assertFalse(world.isDirty(), "a menu's edits are transient");
    }

    @Test
    void aButtonMenuFiresTheHandlerAndMovesNothing() {
        Conn conn = new Conn(ProtocolVersion.JE_1_12_2);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(4, DIAMOND, 1);
        int[] clicked = {-1, -1};
        containers.openMenu(player, "Menu", menu, null, (p, slot, state) -> { clicked[0] = slot; clicked[1] = state; });
        player.getCursor().set(STONE, 5); // even holding an item, a button click must not deposit it

        containers.onChestClick(conn, 4, 0, false);

        assertEquals(4, clicked[0], "the handler saw the clicked slot");
        assertEquals(DIAMOND, clicked[1], "and the item in it");
        assertEquals(DIAMOND, menu.stateAt(4), "the button item is untouched");
        assertEquals(STONE, player.getCursor().state(), "the cursor is untouched");
    }

    @Test
    void aStorageMenuMovesItemsButDoesNotPersist() {
        Conn conn = new Conn(ProtocolVersion.JE_1_12_2);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(0, DIAMOND, 3);
        containers.openMenu(player, "Bag", menu, null, null); // a fresh, unbaked world starts clean

        // Left-click slot 0 picks the stack up onto the cursor (normal storage behaviour).
        containers.onChestClick(conn, 0, 0, false);

        assertEquals(DIAMOND, player.getCursor().state(), "the stack moved to the cursor");
        assertTrue(menu.isEmpty(0), "out of the menu slot");
        assertFalse(world.isDirty(), "a menu's edits are transient — the world isn't marked dirty");
    }

    @Test
    void theWindowSlotMathHandlesANonTwentySevenChest() {
        Conn conn = new Conn(ProtocolVersion.JE_1_12_2);
        CorePlayer player = join(conn);
        player.getInventory().set(0, STONE, 1); // core hotbar slot 0
        Container menu = new Container(9);       // a 1-row menu
        containers.openMenu(player, "One", menu, null, null);

        // In a 9-slot chest window, the hotbar sits at 9+27 .. 9+35. Shift-clicking the player's hotbar
        // slot 0 (window slot 36) quick-moves it into the chest.
        containers.onChestClick(conn, 9 + 27, 0, true);

        assertEquals(STONE, menu.stateAt(0), "the hotbar item shift-moved into the 9-slot menu");
    }

    /** Records the window packets a menu triggers. */
    private static final class Conn implements PlayerConnection {
        private final ProtocolVersion version;
        String windowTitle;
        int windowSlots;
        int[] lastWindowItems = new int[0];

        Conn(ProtocolVersion version) { this.version = version; }

        @Override public void openContainer(int windowId, String title, int slots, int x, int y, int z) {
            this.windowTitle = title; this.windowSlots = slots;
        }
        @Override public void setWindowItems(int windowId, int[] states, int[] counts) {
            this.lastWindowItems = states;
        }
        @Override public ProtocolVersion getProtocolVersion() { return version; }

        final java.util.List<String> messages = new java.util.ArrayList<>();
        @Override public void setCursorItem(int state, int count) { }
        @Override public void sendMessage(String message) { messages.add(message); }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
