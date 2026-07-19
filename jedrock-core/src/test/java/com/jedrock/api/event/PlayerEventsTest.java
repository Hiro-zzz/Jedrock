package com.jedrock.api.event;

import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.DamageCause;
import com.jedrock.api.event.player.GameModeChangeEvent;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerCommandEvent;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerDeathEvent;
import com.jedrock.api.event.player.PlayerLoginEvent;
import com.jedrock.api.event.player.PlayerPickupItemEvent;
import com.jedrock.api.event.player.PlayerRespawnEvent;
import com.jedrock.api.event.player.PlayerTeleportEvent;
import com.jedrock.api.event.player.PlayerToggleSneakEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.event.world.WorldSaveEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the mutable-field and cancellation contracts the core's wiring depends on — a renamed setter or a
 * lost default would silently change what a listener can do, so each is asserted here. (Player is passed as
 * {@code null}: these events are plain carriers and never dereference it.)
 */
class PlayerEventsTest {

    @Test
    void damageEventCarriesCauseAndAMutableAmount() {
        PlayerDamageEvent event = new PlayerDamageEvent(null, DamageCause.FALL, 6);
        assertEquals(DamageCause.FALL, event.getCause());
        assertEquals(6, event.getAmount());
        assertFalse(event.isCancelled(), "not cancelled by default");

        event.setAmount(2);
        assertEquals(2, event.getAmount(), "a listener can rescale the hit");
    }

    @Test
    void deathMessageCanBeRestyledOrSuppressed() {
        PlayerDeathEvent event = new PlayerDeathEvent(null, DamageCause.VOID, "X fell out of the world");
        assertEquals(DamageCause.VOID, event.getCause());
        assertEquals("X fell out of the world", event.getDeathMessage());

        event.setDeathMessage(null);
        assertNull(event.getDeathMessage(), "null suppresses the broadcast");
    }

    @Test
    void commandEventHoldsTheLineWithoutTheSlashAndIsRewritable() {
        PlayerCommandEvent event = new PlayerCommandEvent(null, "gamemode creative");
        assertEquals("gamemode creative", event.getCommand());

        event.setCommand("gamemode survival");
        assertEquals("gamemode survival", event.getCommand(), "a listener can rewrite arguments");
    }

    @Test
    void gameModeChangeCanBeRedirected() {
        GameModeChangeEvent event = new GameModeChangeEvent(null, GameMode.SURVIVAL, GameMode.CREATIVE);
        assertEquals(GameMode.SURVIVAL, event.getFrom());
        assertEquals(GameMode.CREATIVE, event.getNewGameMode());

        event.setNewGameMode(GameMode.SURVIVAL);
        assertEquals(GameMode.SURVIVAL, event.getNewGameMode(), "a listener can redirect the switch");
    }

    @Test
    void chatFormatDefaultsToNameAngleBracketsAndIsReplaceable() {
        PlayerChatEvent event = new PlayerChatEvent(null, "hi");
        assertEquals("hi", event.getMessage());
        assertEquals(PlayerChatEvent.DEFAULT_FORMAT, event.getFormat());
        assertTrue(event.getFormat().contains("%name%") && event.getFormat().contains("%s"),
                "the format carries both placeholders the core substitutes");

        event.setFormat("%s");
        assertEquals("%s", event.getFormat(), "a listener can drop the name entirely");
    }

    @Test
    void toggleEventsReportTheirNewState() {
        assertTrue(new PlayerToggleSneakEvent(null, true).isSneaking());
        assertFalse(new PlayerToggleSneakEvent(null, false).isSneaking());
    }

    private static final CoreWorld WORLD = new CoreWorld("events", Dimension.OVERWORLD, 1L);

    @Test
    void respawnLocationCanBeRedirected() {
        Location spawn = new Location(WORLD, 0, 64, 0, 0f, 0f);
        Location bed = new Location(WORLD, 100, 70, 100, 0f, 0f);
        PlayerRespawnEvent event = new PlayerRespawnEvent(null, spawn);
        assertSame(spawn, event.getRespawnLocation(), "defaults to the given location");

        event.setRespawnLocation(bed);
        assertSame(bed, event.getRespawnLocation(), "a listener can send them elsewhere");
    }

    @Test
    void teleportDestinationIsMutableAndFromIsNot() {
        Location from = new Location(WORLD, 0, 64, 0, 0f, 0f);
        Location to = new Location(WORLD, 10, 64, 10, 0f, 0f);
        Location redirect = new Location(WORLD, 20, 64, 20, 0f, 0f);
        PlayerTeleportEvent event = new PlayerTeleportEvent(null, from, to);
        assertSame(from, event.getFrom());
        assertSame(to, event.getTo());

        event.setTo(redirect);
        assertSame(redirect, event.getTo(), "a listener can redirect the teleport");
        assertSame(from, event.getFrom(), "origin stays put");
    }

    @Test
    void loginEventCarriesIdentityAndAMutableKickReason() {
        java.util.UUID id = java.util.UUID.randomUUID();
        PlayerLoginEvent event = new PlayerLoginEvent(id, "Steve", "1.2.3.4:5678");
        assertEquals(id, event.getUniqueId());
        assertEquals("Steve", event.getUsername());
        assertEquals("1.2.3.4:5678", event.getAddress());
        assertNull(event.getKickReason(), "no reason until a listener sets one");
        assertFalse(event.isCancelled());

        event.setCancelled(true);
        event.setKickReason("Not whitelisted");
        assertTrue(event.isCancelled());
        assertEquals("Not whitelisted", event.getKickReason());
    }

    @Test
    void pickupCarriesTheMinedStateAndIsCancellable() {
        PlayerPickupItemEvent event = new PlayerPickupItemEvent(null, 17 << 4);
        assertEquals(17 << 4, event.getState(), "the canonical state of the mined block");
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled(), "cancel = block breaks but the item isn't collected");
    }

    @Test
    void useItemAndTickReportTheirState() {
        assertTrue(new PlayerUseItemEvent(null, true).isUsing());
        assertFalse(new PlayerUseItemEvent(null, false).isUsing());
        assertEquals(40L, new ServerTickEvent(40L).getTick(), "the tick number rides along");
    }

    @Test
    void worldSaveEventExposesItsWorld() {
        assertSame(WORLD, new WorldSaveEvent(WORLD).getWorld(), "the world about to be written");
    }

    @Test
    void blockEventsExposeCoordinatesAndState() {
        BlockBreakEvent broken = new BlockBreakEvent(null, 1, 2, 3, 0x210);
        assertEquals(1, broken.getX());
        assertEquals(2, broken.getY());
        assertEquals(3, broken.getZ());
        assertEquals(0x210, broken.getState());

        PlayerInteractBlockEvent clicked = new PlayerInteractBlockEvent(null, 4, 5, 6, 54 << 4);
        assertEquals(4, clicked.getX());
        assertEquals(54 << 4, clicked.getState(), "a chest's canonical state");
    }
}
