package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.command.PickCommand;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage list — what a {@code menus} storage container becomes on Bedrock, where no window will come
 * up. Unlike a button list it has to actually move items, and unlike a button list it has to survive being
 * picked: a window stays open across transfers, so the list redraws itself instead of being consumed.
 */
class BedrockStorageListTest {

    private static final int STONE = 1 << 4;
    private static final int DIAMOND = 264 << 4;

    private final CoreWorld world = new CoreWorld("bag", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ContainerService containers =
            new ContainerService(players, world, events, new PlayerBroadcast(players));
    private final PickCommand pick = new PickCommand();

    private final Conn conn = new Conn(ProtocolVersion.PE_1_1_5);

    private CorePlayer join(GameMode mode) {
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), mode);
        players.add(player);
        return player;
    }

    @Test
    void pickingASlotNumberTakesThatStackAndTheListStaysUp() {
        CorePlayer player = join(GameMode.SURVIVAL);
        Container bag = new Container(27);
        bag.set(0, DIAMOND, 3);
        bag.set(4, STONE, 10);
        containers.openMenu(player, "Bag", bag, null, null);

        assertEquals(java.util.List.of("1", "5", "put", "close"), player.getPendingMenu().labels(),
                "slots are listed 1-based, then the two verbs");

        pick.execute(null, player, new String[]{"5"}); // the stone in slot 4

        assertTrue(bag.isEmpty(4), "the stack left the container");
        assertEquals(STONE, player.getInventory().stateAt(0), "and reached the player");
        assertEquals(10, player.getInventory().countAt(0));
        assertNotNull(player.getPendingMenu(), "the list redrew itself rather than being consumed");
        assertEquals(java.util.List.of("1", "put", "close"), player.getPendingMenu().labels(),
                "and it now shows what is actually left");
    }

    @Test
    void pickingPutDepositsTheHeldStack() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 7); // hotbar slot 0 = the hand
        Container bag = new Container(27);
        containers.openMenu(player, "Bag", bag, null, null);

        pick.execute(null, player, new String[]{"put"});

        assertEquals(STONE, bag.stateAt(0), "the held stack went in");
        assertEquals(7, bag.countAt(0));
        assertTrue(player.getInventory().isEmpty(0), "and survival consumed it from the hand");
        assertEquals(java.util.List.of("1", "put", "close"), player.getPendingMenu().labels());
    }

    @Test
    void pickingCloseEndsTheList() {
        CorePlayer player = join(GameMode.SURVIVAL);
        containers.openMenu(player, "Bag", new Container(27), null, null);

        pick.execute(null, player, new String[]{"close"});

        assertNull(player.getPendingMenu(), "closed for good — no redraw");
    }

    @Test
    void aFullInventoryTakesWhatFitsAndLeavesTheRest() {
        CorePlayer player = join(GameMode.SURVIVAL);
        Container inv = player.getInventory();
        for (int slot = 0; slot < CorePlayer.STORAGE_SLOTS; slot++) {
            inv.set(slot, DIAMOND, Container.MAX_STACK); // not one free slot, and nothing stone can join
        }
        Container bag = new Container(27);
        bag.set(0, STONE, 5);
        containers.openMenu(player, "Bag", bag, null, null);

        pick.execute(null, player, new String[]{"1"});

        assertEquals(5, bag.countAt(0), "nothing could move, so nothing was lost");
    }

    @Test
    void creativeTakesByClearingRatherThanByBeingGivenItems() {
        CorePlayer player = join(GameMode.CREATIVE);
        Container bag = new Container(27);
        bag.set(0, DIAMOND, 4);
        containers.openMenu(player, "Bag", bag, null, null);

        pick.execute(null, player, new String[]{"1"});

        assertTrue(bag.isEmpty(0), "the stack is gone from the container");
        assertTrue(player.getInventory().isEmpty(0),
                "but no real items were handed to an infinite inventory — that is the put/take dupe");
    }

    @Test
    void creativePutsWithoutConsumingTheInfiniteHand() {
        CorePlayer player = join(GameMode.CREATIVE);
        player.getInventory().set(0, STONE, 6);
        Container bag = new Container(27);
        containers.openMenu(player, "Bag", bag, null, null);

        pick.execute(null, player, new String[]{"put"});

        assertEquals(6, bag.countAt(0), "the honest held count went in, not a forced stack");
        assertEquals(6, player.getInventory().countAt(0), "and creative's hand is untouched");
    }

    @Test
    void aButtonListIsStillConsumedByItsOnePick() {
        CorePlayer player = join(GameMode.SURVIVAL);
        Container menu = new Container(9);
        menu.set(2, DIAMOND, 1);
        String[] labels = new String[9];
        labels[2] = "Warrior";
        int[] picked = {-1};
        containers.openMenu(player, "Class", menu, labels, (p, slot, state) -> picked[0] = slot);

        pick.execute(null, player, new String[]{"Warrior"});

        assertEquals(2, picked[0]);
        assertNull(player.getPendingMenu(), "a button menu is one pick, unlike storage");
    }

    @Test
    void aWorldChestIsUnaffectedAndStillMarksTheWorldDirty() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 4);
        player.setSneaking(true);
        world.setBlockId(2, 70, 2, com.jedrock.api.world.Blocks.CHEST << 4);

        containers.onChestInteract(conn, 2, 70, 2, 0);

        assertEquals(4, world.getChestContainer(2, 70, 2).countAt(0),
                "the click-transfer still works through the shared primitives");
        assertTrue(world.isDirty(), "and a world chest still persists, unlike a menu");
        assertFalse(player.getInventory().stateAt(0) == STONE, "survival consumed the deposited stack");
    }

    /** A connection that only needs to swallow chat — the list lives entirely in messages. */
    private static final class Conn implements PlayerConnection {
        private final ProtocolVersion version;
        final java.util.List<String> messages = new java.util.ArrayList<>();

        Conn(ProtocolVersion version) { this.version = version; }

        @Override public ProtocolVersion getProtocolVersion() { return version; }
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
