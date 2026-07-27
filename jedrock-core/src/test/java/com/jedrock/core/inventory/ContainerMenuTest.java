package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
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
 * slot math, and what each Bedrock era gets instead of a window (a list, or a refusal when even a list
 * has nothing to offer). The storage list's own transfers live in {@link BedrockStorageListTest}.
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
    void aRetail115StorageMenuBecomesATransferList() {
        Conn conn = new Conn(ProtocolVersion.PE_1_1_5);
        CorePlayer player = join(conn);

        boolean opened = containers.openMenu(player, "Shop", new Container(27), null, null);

        assertTrue(opened, "storage has contents to list even when a window is impossible");
        assertFalse(player.hasContainerOpen(), "the 1.1.5 client crashes on a chest window — no window");
        assertEquals(java.util.List.of("put", "close"), player.getPendingMenu().labels(),
                "an empty container still offers the verb that fills it");
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
    void a014StorageMenuBecomesATransferListToo() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(0, DIAMOND, 1);

        boolean opened = containers.openMenu(player, "Shop", menu, null, null);

        assertTrue(opened);
        assertFalse(player.hasContainerOpen(),
                "a menu window doesn't come up on a real 0.14 client either — the list is the mechanism");
        assertEquals(java.util.List.of("1", "put", "close"), player.getPendingMenu().labels(),
                "the occupied slot is listed by its 1-based number");
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
    void aButtonMenuWithNoLabelsIsRefusedOn014Too() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        Container menu = new Container(27);
        menu.set(3, DIAMOND, 1); // an item, but no label — and a button menu has no contents to fall back on

        boolean opened = containers.openMenu(player, "Menu", menu, null, (p, slot, state) -> { });

        assertFalse(opened, "a list can't offer an unlabelled button, and 0.14 raises no menu window");
        assertFalse(player.hasContainerOpen());
    }

    @Test
    void a014WorldChestStillTakesTheClientsMoveThroughItsWindow() {
        Conn conn = new Conn(ProtocolVersion.PE_0_14);
        CorePlayer player = join(conn);
        // A real chest block: unlike a blockless menu, 0.14 does raise a window for one, so the
        // client-authoritative report path stays live for world chests.
        world.setBlockId(4, 70, 4, Blocks.CHEST << 4);
        assertTrue(containers.onUseBlock(conn, 4, 70, 4));
        int windowId = player.getOpenWindowId();

        containers.onContainerSetSlot(conn, windowId, 5, DIAMOND, 2); // the client says it filled slot 5

        Container chest = world.getChestContainer(4, 70, 4);
        assertEquals(DIAMOND, chest.stateAt(5), "the client-authoritative move was applied");
        assertEquals(2, chest.countAt(5));
        assertTrue(world.isDirty(), "a world chest's edits persist, unlike a menu's");
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
