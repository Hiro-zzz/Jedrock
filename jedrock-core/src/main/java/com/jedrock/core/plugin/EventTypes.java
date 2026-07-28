package com.jedrock.core.plugin;

import com.jedrock.api.event.Event;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.BlockPlaceEvent;
import com.jedrock.api.event.block.PlayerInteractBlockEvent;
import com.jedrock.api.event.player.GameModeChangeEvent;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerCommandEvent;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerDeathEvent;
import com.jedrock.api.event.player.InventoryClickEvent;
import com.jedrock.api.event.player.PlayerInteractEntityEvent;
import com.jedrock.api.event.player.PlayerArmorChangeEvent;
import com.jedrock.api.event.player.PlayerHeldItemChangeEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerKickEvent;
import com.jedrock.api.event.player.PlayerLoginEvent;
import com.jedrock.api.event.player.PlayerMoveEvent;
import com.jedrock.api.event.player.PlayerPickupItemEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.event.player.PlayerRegionEnterEvent;
import com.jedrock.api.event.player.PlayerRegionLeaveEvent;
import com.jedrock.api.event.player.PlayerRespawnEvent;
import com.jedrock.api.event.player.PlayerTeleportEvent;
import com.jedrock.api.event.player.PlayerWorldChangeEvent;
import com.jedrock.api.event.player.PlayerToggleSneakEvent;
import com.jedrock.api.event.player.PlayerToggleSprintEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.event.server.ServerStartEvent;
import com.jedrock.api.event.server.ServerStopEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.event.world.WeatherChangeEvent;
import com.jedrock.api.event.world.WorldSaveEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The bridge between a script's friendly event name and the Java event class the {@code EventBus} keys on.
 * A script writes {@code events.on('PlayerJoin', …)}; this resolves that to {@link PlayerJoinEvent}. Kept in
 * the core (not the {@code api}) so the event contracts stay ignorant of the scripting layer.
 *
 * <p>A name matches case-insensitively, with or without the {@code Event} suffix — {@code 'PlayerJoin'},
 * {@code 'playerjoin'} and {@code 'PlayerJoinEvent'} all resolve. This is the one place the script-visible
 * event vocabulary is defined; adding an event here is what makes it scriptable.
 */
public final class EventTypes {

    private EventTypes() {}

    private static final Map<String, Class<? extends Event>> BY_NAME = new LinkedHashMap<>();

    static {
        register("PlayerLogin", PlayerLoginEvent.class);
        register("PlayerJoin", PlayerJoinEvent.class);
        register("PlayerQuit", PlayerQuitEvent.class);
        register("PlayerChat", PlayerChatEvent.class);
        register("PlayerCommand", PlayerCommandEvent.class);
        register("PlayerMove", PlayerMoveEvent.class);
        register("PlayerRegionEnter", PlayerRegionEnterEvent.class);
        register("PlayerRegionLeave", PlayerRegionLeaveEvent.class);
        register("PlayerTeleport", PlayerTeleportEvent.class);
        register("PlayerWorldChange", PlayerWorldChangeEvent.class);
        register("PlayerRespawn", PlayerRespawnEvent.class);
        register("PlayerDamage", PlayerDamageEvent.class);
        register("PlayerDeath", PlayerDeathEvent.class);
        register("PlayerInteractEntity", PlayerInteractEntityEvent.class);
        register("PlayerPickupItem", PlayerPickupItemEvent.class);
        register("InventoryClick", InventoryClickEvent.class);
        register("PlayerKick", PlayerKickEvent.class);
        register("PlayerToggleSneak", PlayerToggleSneakEvent.class);
        register("PlayerToggleSprint", PlayerToggleSprintEvent.class);
        register("PlayerUseItem", PlayerUseItemEvent.class);
        register("GameModeChange", GameModeChangeEvent.class);
        register("BlockBreak", BlockBreakEvent.class);
        register("BlockPlace", BlockPlaceEvent.class);
        register("PlayerInteractBlock", PlayerInteractBlockEvent.class);
        register("PlayerArmorChange", PlayerArmorChangeEvent.class);
        register("PlayerHeldItemChange", PlayerHeldItemChangeEvent.class);
        register("ServerStart", ServerStartEvent.class);
        register("ServerStop", ServerStopEvent.class);
        register("ServerTick", ServerTickEvent.class);
        register("WeatherChange", WeatherChangeEvent.class);
        register("WorldSave", WorldSaveEvent.class);
    }

    private static void register(String name, Class<? extends Event> type) {
        BY_NAME.put(key(name), type);
    }

    private static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith("event") ? lower.substring(0, lower.length() - "event".length()) : lower;
    }

    /** The event class for a script name, or {@code null} if it isn't a known event. */
    public static Class<? extends Event> byName(String name) {
        return name == null ? null : BY_NAME.get(key(name));
    }
}
