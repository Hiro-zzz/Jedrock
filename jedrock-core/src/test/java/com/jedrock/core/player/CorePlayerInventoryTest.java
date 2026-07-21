package com.jedrock.core.player;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerKickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The minimal survival inventory: stacking on give, decrement on take, and the sync it pushes. */
class CorePlayerInventoryTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    private CorePlayer player(PlayerConnection c) {
        return new CorePlayer(UUID.randomUUID(), "Tester", c, world, world.getSpawnLocation(), GameMode.SURVIVAL);
    }

    @Test
    void giveStacksMatchingThenFillsEmptySlot() {
        CorePlayer p = player(new NoopConnection());
        int stone = Blocks.state(Blocks.STONE, 0);

        assertEquals(0, p.addToInventory(stone), "first stone → hotbar slot 0");
        assertEquals(0, p.addToInventory(stone), "second stone stacks in slot 0");
        assertEquals(stone, p.inventoryStates()[0]);
        assertEquals(2, p.inventoryCounts()[0]);
        assertEquals(0, p.inventoryStates()[1], "still one stack of stone, nothing spilled");

        int dirt = Blocks.state(Blocks.DIRT, 0);
        assertEquals(1, p.addToInventory(dirt), "dirt → next empty slot 1");
        assertEquals(dirt, p.inventoryStates()[1]);
        assertEquals(1, p.inventoryCounts()[1]);
    }

    @Test
    void giveRejectsAirAndFullInventory() {
        CorePlayer p = player(new NoopConnection());
        assertEquals(-1, p.addToInventory(0), "air is never an item");

        // Fill all 36 slots with distinct single items (id in the meta-free high bits).
        for (int i = 0; i < 36; i++) {
            assertTrue(p.addToInventory(Blocks.state(10 + i, 0)) >= 0);
        }
        assertEquals(-1, p.addToInventory(Blocks.state(200, 0)), "a full inventory drops the extra item");
    }

    @Test
    void giveItemSyncsTheSlotAndReportsWhetherItFit() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);
        int stone = Blocks.state(Blocks.STONE, 0);

        assertTrue(p.giveItem(stone), "fit into an empty inventory");
        assertEquals(0, conn.slot, "the affected slot was pushed to the client");
        assertEquals(stone, conn.slotState);
        assertEquals(1, conn.slotCount);

        // Fill the rest, then a give that can't fit returns false.
        for (int i = 1; i < 36; i++) {
            assertTrue(p.giveItem(Blocks.state(10 + i, 0)));
        }
        assertFalse(p.giveItem(Blocks.state(200, 0)), "a full inventory can't take the item");
    }

    @Test
    void takeDecrementsAndClearsSlot() {
        CorePlayer p = player(new NoopConnection());
        int stone = Blocks.state(Blocks.STONE, 0);
        p.addToInventory(stone);
        p.addToInventory(stone);

        assertEquals(0, p.takeItem(stone));
        assertEquals(1, p.inventoryCounts()[0]);
        assertEquals(0, p.takeItem(stone));
        assertEquals(0, p.inventoryStates()[0], "slot cleared when the last one is taken");
        assertEquals(-1, p.takeItem(stone), "nothing left to take");
    }

    @Test
    void syncSlotPushesTheChangedSlot() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);
        int stone = Blocks.state(Blocks.STONE, 0);
        int slot = p.addToInventory(stone);

        p.syncSlot(slot);
        assertEquals(slot, conn.slot);
        assertEquals(stone, conn.slotState);
        assertEquals(1, conn.slotCount);
    }

    @Test
    void syncInventoryPushesTheWholeModel() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);
        p.addToInventory(Blocks.state(Blocks.STONE, 0));

        p.syncInventory();
        assertArrayEquals(p.inventoryStates(), conn.states);
        assertArrayEquals(p.inventoryCounts(), conn.counts);
    }

    @Test
    void healthClampsOnDamageAndHeal() {
        CorePlayer p = player(new NoopConnection());
        assertEquals(20, p.getHealth(), "starts at full health");

        assertEquals(13, p.damage(7));
        assertEquals(0, p.damage(100), "damage never goes below zero");

        p.setHealth(25);
        assertEquals(20, p.getHealth(), "health never exceeds the max");
        p.setHealth(-5);
        assertEquals(0, p.getHealth());
    }

    @Test
    void getAddressComesFromTheConnection() {
        CorePlayer p = player(new NoopConnection());
        assertEquals("test", p.getAddress(), "getAddress delegates to the connection");
    }

    @Test
    void kickFiresACancellableRewritablePlayerKickEvent() {
        EventBus bus = new EventBus();
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "T", conn,
                world, world.getSpawnLocation(), GameMode.SURVIVAL, bus);

        // A listener cancels the kick — the connection must not close.
        EventBus.Subscription veto = bus.register(PlayerKickEvent.class, e -> e.setCancelled(true));
        p.kick("bye");
        assertNull(conn.closeReason, "a cancelled kick left the player connected");
        veto.remove();

        // A listener rewrites the reason before the disconnect.
        bus.register(PlayerKickEvent.class, e -> e.setReason("rewritten"));
        p.kick("original");
        assertEquals("rewritten", conn.closeReason, "the kick reason was rewritten");
    }

    @Test
    void kickWithoutABusClosesDirectly() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn); // 6-arg ctor: no event bus
        p.kick("bye");
        assertEquals("bye", conn.closeReason, "with no bus wired, kick closes straight away");
    }

    @Test
    void setHealthClampsAndSyncsToTheClient() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);
        assertEquals(20, p.getMaxHealth());

        p.setHealth(6);
        assertEquals(6, p.getHealth());
        assertEquals(6, conn.health, "the client's health HUD was refreshed");

        p.setHealth(999);
        assertEquals(20, conn.health, "clamped to max before it reached the client");
    }

    @Test
    void trackFallReturnsDistanceOnLanding() {
        CorePlayer p = player(new NoopConnection());
        // Descend 70 -> 68 -> 60 (still falling: returns 0 each step).
        assertEquals(0.0, p.trackFall(70, 68), 1e-6);
        assertEquals(0.0, p.trackFall(68, 60), 1e-6);
        // Landed at 60 (next report doesn't drop): distance = peak(70) - landing(60) = 10.
        assertEquals(10.0, p.trackFall(60, 60), 1e-6);
        // Nothing pending now.
        assertEquals(0.0, p.trackFall(60, 60), 1e-6);
    }

    @Test
    void trackFallIgnoresAJump() {
        CorePlayer p = player(new NoopConnection());
        // Jump up then land: peak-to-landing is tiny, so the distance is well under the damage floor.
        p.trackFall(64, 65);          // rising — not a fall
        p.trackFall(65, 65.2);        // still rising
        double d1 = p.trackFall(65.2, 64.8); // now descending
        assertEquals(0.0, d1, 1e-6);
        double landed = p.trackFall(64.8, 64); // stopped
        assertTrue(landed < 3, "a jump lands under the fall-damage threshold, got " + landed);
    }

    @Test
    void firstHitIsNotOnCooldownThenTheWindowBlocks() {
        CorePlayer p = player(new NoopConnection());
        // Regression: a fresh player's first hit must land (the initial sentinel must not read as an
        // active i-frame window — the overflow bug made every PvP hit no-op).
        assertFalse(p.isOnHurtCooldown(), "first hit lands");
        assertTrue(p.isOnHurtCooldown(), "an immediate second hit is inside the i-frame window");
    }

    @Test
    void resetFallDiscardsAnInProgressFall() {
        CorePlayer p = player(new NoopConnection());
        // A fall into the void never "lands", so its peak lingers until reset (as a teleport/respawn does).
        p.trackFall(120, 100);        // descending from a high peak (120), still falling
        p.resetFall();                // respawn: forget the fall
        // The next move (near spawn) must not bill the phantom 120 -> spawn drop.
        assertEquals(0.0, p.trackFall(65, 65), 1e-6, "reset fall is not billed on the next move");
    }

    @Test
    void titleRendersMarkupAndPassesTimingsToTheConnection() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);

        p.sendTitle("{red}Go", "{gray}subtitle", 5, 40, 5);
        assertEquals("§cGo", conn.title, "markup rendered to legacy codes");
        assertEquals("§7subtitle", conn.subtitle);
        assertEquals(5, conn.fadeIn);
        assertEquals(40, conn.stay);
        assertEquals(5, conn.fadeOut);

        p.sendTitle("{green}Hi", "");        // default-timing overload → 10/70/20
        assertEquals(10, conn.fadeIn);
        assertEquals(70, conn.stay);
        assertEquals(20, conn.fadeOut);
    }

    @Test
    void actionBarAndClearReachTheConnection() {
        CapturingConnection conn = new CapturingConnection();
        CorePlayer p = player(conn);

        p.sendActionBar("{yellow}Wave");
        assertEquals("§eWave", conn.actionBar);

        p.clearTitle();
        assertTrue(conn.titleCleared);
    }

    private static class NoopConnection implements PlayerConnection {
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.PE_1_1_5; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void sendMessage(String message) { }
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

    private static final class CapturingConnection extends NoopConnection {
        int[] states;
        int[] counts;
        int slot = -1;
        int slotState;
        int slotCount;
        int health = -1;
        String closeReason;
        String title, subtitle, actionBar;
        int fadeIn, stay, fadeOut;
        boolean titleCleared;
        @Override public void close(String reason) { this.closeReason = reason; }
        @Override public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            this.title = title; this.subtitle = subtitle;
            this.fadeIn = fadeIn; this.stay = stay; this.fadeOut = fadeOut;
        }
        @Override public void sendActionBar(String text) { this.actionBar = text; }
        @Override public void clearTitle() { this.titleCleared = true; }
        @Override public void setInventory(int[] states, int[] counts) {
            this.states = states.clone();
            this.counts = counts.clone();
        }
        @Override public void setInventorySlot(int slot, int state, int count) {
            this.slot = slot;
            this.slotState = state;
            this.slotCount = count;
        }
        @Override public void setHealth(int health) {
            this.health = health;
        }
    }
}
