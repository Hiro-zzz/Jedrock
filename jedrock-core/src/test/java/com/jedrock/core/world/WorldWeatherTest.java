package com.jedrock.core.world;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.world.WeatherChangeEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Weather;
import com.jedrock.core.player.CorePlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Weather: one enum on the world, broadcast on change, pushed to a late joiner, deduped. */
class WorldWeatherTest {

    private final CoreWorld world = new CoreWorld("weather", Dimension.OVERWORLD, 1L);

    /** Records every weather push; everything else is a no-op. */
    private static class WeatherConnection implements PlayerConnection {
        final List<Weather> sent = new ArrayList<>();
        @Override public void sendWeather(Weather weather) { sent.add(weather); }
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

    private CorePlayer join(WeatherConnection c) {
        CorePlayer p = new CorePlayer(UUID.randomUUID(), "W" + world.getPlayers().size(), c,
                world, world.getSpawnLocation(), GameMode.CREATIVE);
        world.addPlayer(p);
        return p;
    }

    @Test
    void changesBroadcastAndDedupe() {
        WeatherConnection c = new WeatherConnection();
        join(c);
        assertEquals(Weather.CLEAR, world.getWeather(), "a fresh world is clear");
        assertTrue(c.sent.isEmpty(), "joining a clear world sends nothing");

        world.setWeather(Weather.RAIN);
        world.setWeather(Weather.RAIN); // same state — no re-send
        world.setWeather(Weather.THUNDER);
        world.setWeather(null);         // ignored

        assertEquals(Weather.THUNDER, world.getWeather());
        assertEquals(List.of(Weather.RAIN, Weather.THUNDER), c.sent, "one push per actual change");
    }

    /** Every front door onto the sky goes through setWeather, so the event sees them all. */
    @Test
    void aListenerCanRefuseOrRedirectTheSky() {
        EventBus events = new EventBus();
        world.setEventBus(events);
        WeatherConnection c = new WeatherConnection();
        join(c);

        List<WeatherChangeEvent> seen = new ArrayList<>();
        events.register(WeatherChangeEvent.class, seen::add);
        world.setWeather(Weather.THUNDER);
        assertEquals(1, seen.size());
        assertEquals(Weather.CLEAR, seen.get(0).getFrom());
        assertEquals(Weather.THUNDER, seen.get(0).getTo());

        // Refused: the sky is unchanged and nothing was sent, so there is nothing to undo.
        events.register(WeatherChangeEvent.class, e -> e.setCancelled(true));
        world.setWeather(Weather.CLEAR);
        assertEquals(Weather.THUNDER, world.getWeather(), "the refused change never landed");
        assertEquals(List.of(Weather.THUNDER), c.sent, "and no client was told about it");
    }

    @Test
    void aRedirectedChangeSendsWhatTheListenerChose() {
        EventBus events = new EventBus();
        world.setEventBus(events);
        WeatherConnection c = new WeatherConnection();
        join(c);
        events.register(WeatherChangeEvent.class, e -> e.setTo(Weather.RAIN)); // no thunder on this server

        world.setWeather(Weather.THUNDER);

        assertEquals(Weather.RAIN, world.getWeather());
        assertEquals(List.of(Weather.RAIN), c.sent, "the client sees the redirect, not the request");
    }

    @Test
    void aLateJoinerWalksIntoTheCurrentSky() {
        world.setWeather(Weather.RAIN);

        WeatherConnection late = new WeatherConnection();
        join(late);
        assertEquals(List.of(Weather.RAIN), late.sent, "the joiner is told the non-clear weather");

        world.setWeather(Weather.CLEAR);
        WeatherConnection afterClear = new WeatherConnection();
        join(afterClear);
        assertTrue(afterClear.sent.isEmpty(), "a clear sky needs no push (the client default)");
    }
}
