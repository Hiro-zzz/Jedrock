package com.jedrock.core.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.api.world.Dimension;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The identity QoL surface: the chat display name (set / fallback / reset) and the ping default. */
class CorePlayerIdentityTest {

    private final CoreWorld world = new CoreWorld("idworld", Dimension.OVERWORLD, 1L);

    private CorePlayer player(PlayerConnection c) {
        return new CorePlayer(UUID.randomUUID(), "Tester", c, world, world.getSpawnLocation(), GameMode.SURVIVAL);
    }

    /** Minimal connection: every method is the interface default / a no-op. */
    private static class NoopConnection implements PlayerConnection {
        @Override public void sendPacket(Object packet) {}
        @Override public void sendMessage(String message) {}
        @Override public void addToTab(UUID uuid, String name) {}
        @Override public void removeFromTab(UUID uuid) {}
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) {}
        @Override public void hidePlayer(UUID uuid, long entityId) {}
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) {}
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) {}
        @Override public void setGameMode(GameMode mode) {}
        @Override public void swingArm(long entityId) {}
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) {}
        @Override public void sendBlockChange(int x, int y, int z, int state) {}
        @Override public void close(String reason) {}
        @Override public boolean isActive() { return true; }
        @Override public String getAddress() { return "test"; }
        @Override public com.jedrock.api.protocol.ProtocolVersion getProtocolVersion() {
            return com.jedrock.api.protocol.ProtocolVersion.JE_1_12_2;
        }
    }

    @Test
    void displayNameDefaultsSetsAndResets() {
        CorePlayer p = player(new NoopConnection());
        assertEquals("Tester", p.getDisplayName(), "defaults to the real name");

        p.setDisplayName("{red}Boss");
        assertEquals("{red}Boss", p.getDisplayName(), "a set display name is returned as-is");
        assertEquals("Tester", p.getName(), "the real name never changes");

        p.setDisplayName("  ");
        assertEquals("Tester", p.getDisplayName(), "blank resets to the real name");
        p.setDisplayName(null);
        assertEquals("Tester", p.getDisplayName(), "null resets too");
    }

    @Test
    void pingIsUnknownOnAConnectionThatCannotMeasure() {
        CorePlayer p = player(new NoopConnection());
        assertEquals(-1, p.getPing(), "the interface default is -1 (unknown)");
    }

    @Test
    void heldItemFollowsTheSelectedSlot() {
        CorePlayer p = player(new NoopConnection());
        int stone = com.jedrock.api.world.Blocks.state(1, 0);
        int sword = com.jedrock.api.world.Blocks.state(276, 0);
        p.setItem(0, stone, 1);
        p.setItem(3, sword, 1);

        assertEquals(0, p.getHeldItemSlot(), "slot 0 by default");
        assertEquals(stone, p.getHeldItem());

        p.setHeldItemSlot(3);
        assertEquals(sword, p.getHeldItem(), "the hand follows the selected slot");

        p.setHeldItemSlot(9);   // out of the hotbar
        p.setHeldItemSlot(-1);
        assertEquals(3, p.getHeldItemSlot(), "an out-of-range switch is ignored");
    }

    @Test
    void theHeldItemHookFiresOnlyForTheHand() {
        CorePlayer p = player(new NoopConnection());
        int[] fired = {0};
        p.setHeldItemListener(who -> fired[0]++);

        p.setItem(0, com.jedrock.api.world.Blocks.state(1, 0), 1); // the held slot → relay
        assertEquals(1, fired[0], "changing the hand notifies");

        p.setItem(5, com.jedrock.api.world.Blocks.state(1, 0), 1); // another slot → silent
        assertEquals(1, fired[0], "a change elsewhere in the inventory does not");

        p.clearInventory(); // a full resync always notifies (the hand may have emptied)
        assertEquals(2, fired[0]);
    }
}
