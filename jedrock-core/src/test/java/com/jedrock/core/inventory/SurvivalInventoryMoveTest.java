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
 * A survival player rearranging their <em>own</em> inventory on Bedrock (window 0), which the client owns
 * and merely reports. The two halves of the same rule: a real move must stick across the resync that
 * closing the inventory triggers, and the client's echo of a move the server made must not — that echo is
 * what duplicated a chest deposit.
 */
class SurvivalInventoryMoveTest {

    private static final int STONE = 1 << 4;
    private static final int DIAMOND = 264 << 4;
    private static final int PLAYER_WINDOW = 0;

    private final CoreWorld world = new CoreWorld("inv", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ContainerService containers =
            new ContainerService(players, world, events, new PlayerBroadcast(players));

    private final Conn conn = new Conn(ProtocolVersion.PE_1_1_5);

    private CorePlayer join(GameMode mode) {
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), mode);
        players.add(player);
        return player;
    }

    @Test
    void aSurvivalPlayerMovesAnItemOutOfTheHandAndItStaysThere() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 12); // hotbar slot 0 — the hand
        // The client moved the stack from the hand into main storage and reports both slots.
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 0, 0, 0);
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 20, STONE, 12);

        assertTrue(player.getInventory().isEmpty(0), "the hand was emptied");
        assertEquals(STONE, player.getInventory().stateAt(20), "and the stack is in storage");
        assertEquals(12, player.getInventory().countAt(20));

        // Closing the inventory resyncs it — the move must survive that, which is the reported bug.
        containers.onWindowClose(conn);

        assertEquals(0, conn.lastInventory[0], "the resync no longer puts the item back in the hand");
        assertEquals(STONE, conn.lastInventory[20]);
    }

    @Test
    void theSameMoveWorksBackTowardsTheHand() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(20, DIAMOND, 3);
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 20, 0, 0);
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 0, DIAMOND, 3);
        containers.onWindowClose(conn);

        assertEquals(DIAMOND, player.getInventory().stateAt(0), "storage → hand sticks too");
        assertTrue(player.getInventory().isEmpty(20));
        assertEquals(DIAMOND, conn.lastInventory[0]);
    }

    @Test
    void theEchoOfAServerAuthoredPushIsRefusedAndTheClientIsCorrected() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 8);

        player.syncSlot(0);            // the server changed slot 0 and pushed it — the guard is armed
        player.getInventory().set(0, STONE, 3);
        player.syncSlot(0);
        conn.slotPushes = 0;

        // The client echoes the value the slot held *before* the server touched it.
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 0, STONE, 8);

        assertEquals(3, player.getInventory().countAt(0), "the stale echo was refused");
        assertEquals(1, conn.slotPushes, "and the client was corrected rather than believed");
        assertEquals(3, conn.lastSlotCount);
    }

    @Test
    void aSurvivalChestDepositIsNotUndoneByTheEcho() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 10);
        player.setSneaking(true); // sneak + right-click = deposit the held slot
        int x = 0, y = 70, z = 0;
        world.setBlockId(x, y, z, Blocks.CHEST << 4);

        boolean handled = containers.onChestInteract(conn, x, y, z, 0);
        assertTrue(handled);
        Container chest = world.getChestContainer(x, y, z);
        assertEquals(10, chest.countAt(0), "the stack went into the chest");
        assertTrue(player.getInventory().isEmpty(0), "and left the hand");

        // The 1.1.5 client now echoes the pre-deposit hand. Trusting it is the old duplication bug.
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 0, STONE, 10);

        assertTrue(player.getInventory().isEmpty(0), "the echo did not re-add the deposited stack");
        assertEquals(10, chest.countAt(0), "and the chest still holds exactly what was deposited");
    }

    @Test
    void creativeIsStillAPlainMirrorAndIsNeverGuarded() {
        CorePlayer player = join(GameMode.CREATIVE);
        player.syncSlot(0); // a push that would guard the slot in survival

        containers.onContainerSetSlot(conn, PLAYER_WINDOW, 0, DIAMOND, 64);

        assertEquals(DIAMOND, player.getInventory().stateAt(0),
                "creative owns its inventory outright — its report is mirrored, never second-guessed");
        assertEquals(64, player.getInventory().countAt(0));
    }

    @Test
    void aReportForTheArmorSlotsIsNotAWindowZeroMove() {
        CorePlayer player = join(GameMode.SURVIVAL);
        containers.onContainerSetSlot(conn, PLAYER_WINDOW, CorePlayer.STORAGE_SLOTS, DIAMOND, 1);

        assertTrue(player.getInventory().isEmpty(CorePlayer.STORAGE_SLOTS),
                "armor rides its own PE window, so window 0 stops at the 36 storage slots");
    }

    /** Records the inventory packets the core pushes back at the client. */
    private static final class Conn implements PlayerConnection {
        private final ProtocolVersion version;
        int[] lastInventory = new int[CorePlayer.INV_SLOTS];
        int slotPushes;
        int lastSlotCount;

        Conn(ProtocolVersion version) { this.version = version; }

        @Override public void setInventory(int[] states, int[] counts) {
            this.lastInventory = states.clone();
        }
        @Override public void setInventorySlot(int slot, int state, int count) {
            slotPushes++;
            lastSlotCount = count;
        }
        @Override public ProtocolVersion getProtocolVersion() { return version; }

        @Override public void sendMessage(String message) { }
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
