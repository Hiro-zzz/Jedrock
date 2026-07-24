package com.jedrock.core.inventory;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerArmorChangeEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two equipment events: what a player wears and what they hold. Both are posted from
 * {@link ContainerService}, which is where every path that changes either one ends up.
 */
class EquipmentEventsTest {

    private static final int DIAMOND_HELMET = 310 << 4;
    private static final int IRON_HELMET = 306 << 4;
    private static final int STONE = 1 << 4;
    private static final int DIRT = 3 << 4;

    private final CoreWorld world = new CoreWorld("equip", Dimension.OVERWORLD, 1L);
    private final EventBus events = new EventBus();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ContainerService containers =
            new ContainerService(players, world, events, new PlayerBroadcast(players));

    /** Minimal connection: nothing here asserts on packets, only on core state. */
    private static class Conn implements PlayerConnection {
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
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
    }

    private final Conn connection = new Conn();

    private CorePlayer join(GameMode mode) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "Wearer", connection, world,
                world.getSpawnLocation(), mode, events);
        players.add(p);
        return p;
    }

    // ===== Armor =====

    @Test
    void wearingAPieceAnnouncesTheSlotAndBothStates() {
        CorePlayer player = join(GameMode.CREATIVE);
        player.setArmor(ArmorSlot.HELMET, IRON_HELMET);
        List<PlayerArmorChangeEvent> seen = new ArrayList<>();
        events.register(PlayerArmorChangeEvent.class, seen::add);

        player.setArmor(ArmorSlot.HELMET, DIAMOND_HELMET);

        assertEquals(1, seen.size());
        assertEquals(ArmorSlot.HELMET, seen.get(0).getSlot());
        assertEquals(IRON_HELMET, seen.get(0).getPrevious(), "what was worn");
        assertEquals(DIAMOND_HELMET, seen.get(0).getNext(), "what is being put on");
        assertEquals(DIAMOND_HELMET, player.getArmor(ArmorSlot.HELMET));
    }

    @Test
    void cancellingSetArmorLeavesThePieceOff() {
        CorePlayer player = join(GameMode.CREATIVE);
        events.register(PlayerArmorChangeEvent.class, e -> e.setCancelled(true));

        player.setArmor(ArmorSlot.CHESTPLATE, DIAMOND_HELMET);

        assertEquals(0, player.getArmor(ArmorSlot.CHESTPLATE), "the refused piece was never worn");
    }

    /** A creative client drags a helmet into slot 36; the mirror fires the event like any other path. */
    @Test
    void aCreativeDragIntoAnArmorSlotFires() {
        CorePlayer player = join(GameMode.CREATIVE);
        List<PlayerArmorChangeEvent> seen = new ArrayList<>();
        events.register(PlayerArmorChangeEvent.class, seen::add);

        containers.onCreativeSetSlot(connection, ArmorSlot.HELMET.inventorySlot(), DIAMOND_HELMET, 1);

        assertEquals(1, seen.size());
        assertEquals(ArmorSlot.HELMET, seen.get(0).getSlot());
        assertEquals(DIAMOND_HELMET, player.getArmor(ArmorSlot.HELMET));
    }

    /** Refusing that drag puts the slot back — the client is corrected by the resync that follows. */
    @Test
    void aRefusedCreativeDragIsPutBack() {
        CorePlayer player = join(GameMode.CREATIVE);
        events.register(PlayerArmorChangeEvent.class, e -> e.setCancelled(true));

        containers.onCreativeSetSlot(connection, ArmorSlot.BOOTS.inventorySlot(), DIAMOND_HELMET, 1);

        assertEquals(0, player.getArmor(ArmorSlot.BOOTS), "the slot was restored");
    }

    /** A survival window click can undress a player too — the diff catches it whatever the click did. */
    @Test
    void shiftingArmorOutOfItsSlotFires() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.setArmor(ArmorSlot.HELMET, IRON_HELMET);
        List<PlayerArmorChangeEvent> seen = new ArrayList<>();
        events.register(PlayerArmorChangeEvent.class, seen::add);

        containers.onWindowClick(connection, ArmorSlot.HELMET.inventorySlot(), 0, true);

        assertEquals(1, seen.size(), "taking a piece off is a change like putting one on");
        assertEquals(IRON_HELMET, seen.get(0).getPrevious());
        assertEquals(0, seen.get(0).getNext(), "the slot is now empty");
        assertEquals(0, player.getArmor(ArmorSlot.HELMET));
    }

    @Test
    void aRefusedShiftKeepsTheArmorOn() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.setArmor(ArmorSlot.HELMET, IRON_HELMET);
        events.register(PlayerArmorChangeEvent.class, e -> e.setCancelled(true));

        containers.onWindowClick(connection, ArmorSlot.HELMET.inventorySlot(), 0, true);

        assertEquals(IRON_HELMET, player.getArmor(ArmorSlot.HELMET), "the piece stays on");
    }

    @Test
    void anOrdinaryClickPostsNoArmorEvent() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.addToInventory(STONE);
        List<PlayerArmorChangeEvent> seen = new ArrayList<>();
        events.register(PlayerArmorChangeEvent.class, seen::add);

        containers.onWindowClick(connection, 0, 0, true); // hotbar → main storage

        assertTrue(seen.isEmpty(), "moving a block around is not an equipment change");
    }

    // ===== Held item =====

    @Test
    void switchingHotbarSlotsAnnouncesBothSlotsAndItems() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 1);
        player.getInventory().set(4, DIRT, 1);
        List<PlayerHeldItemChangeEvent> seen = new ArrayList<>();
        events.register(PlayerHeldItemChangeEvent.class, seen::add);

        containers.onHeldSlotChange(connection, 4);

        assertEquals(1, seen.size());
        assertEquals(0, seen.get(0).getPreviousSlot());
        assertEquals(4, seen.get(0).getNewSlot());
        assertEquals(STONE, seen.get(0).getPreviousItem());
        assertEquals(DIRT, seen.get(0).getNewItem());
        assertEquals(4, player.getHeldItemSlot());
    }

    @Test
    void cancellingLeavesTheServerHoldingTheOldSlot() {
        CorePlayer player = join(GameMode.SURVIVAL);
        player.getInventory().set(0, STONE, 1);
        player.getInventory().set(7, DIRT, 1);
        events.register(PlayerHeldItemChangeEvent.class, e -> e.setCancelled(true));

        containers.onHeldSlotChange(connection, 7);

        assertEquals(0, player.getHeldItemSlot(), "the switch was not recorded");
        assertEquals(STONE, player.getHeldItem(), "so the hand still holds the old stack");
    }

    @Test
    void reReportingTheCurrentSlotIsNotAChange() {
        CorePlayer player = join(GameMode.SURVIVAL);
        containers.onHeldSlotChange(connection, 3);
        List<PlayerHeldItemChangeEvent> seen = new ArrayList<>();
        events.register(PlayerHeldItemChangeEvent.class, seen::add);

        containers.onHeldSlotChange(connection, 3); // the client re-reports what it already holds

        assertTrue(seen.isEmpty(), "the player didn't choose anything");
        assertEquals(3, player.getHeldItemSlot());
    }
}
