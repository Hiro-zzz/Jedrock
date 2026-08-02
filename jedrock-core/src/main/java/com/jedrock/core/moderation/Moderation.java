package com.jedrock.core.moderation;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerLoginEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.core.data.DataStore;
import com.jedrock.core.permission.OpList;
import com.jedrock.utils.text.ChatText;

/**
 * Who may be here, and who may speak — the punishments, the whitelist, and the two moments they are
 * applied.
 *
 * <p>Both moments already existed. A ban is a cancelled {@link PlayerLoginEvent}, which is what that event
 * was built for and says so in its own documentation; a mute is a suppressed chat line. So there is no new
 * gate here and no new hot path: this is a decision made at points the core was already routing through,
 * which also means a script can watch and overrule either of them at a higher priority, like any other
 * rule on this server.
 *
 * <p>The login check is answered on a network I/O thread before any player object exists. Everything it
 * reads is in memory.
 */
public final class Moderation {

    private final PunishmentStore punishments;
    private final Whitelist whitelist;
    private final LastSeen lastSeen;
    private final OpList ops;

    public Moderation(DataStore store, EventBus events, OpList ops) {
        this.punishments = new PunishmentStore(store);
        this.whitelist = new Whitelist(store);
        this.lastSeen = new LastSeen(store);
        this.ops = ops;

        // Registered unconditionally, unlike the optional rules elsewhere: a server that has never banned
        // anybody still has to answer "may this person connect", and a login is not a hot path — it
        // happens once per player, next to a world's worth of chunks.
        events.register(PlayerLoginEvent.class, event -> {
            String refusal = refusalFor(event.getUsername(), event.getAddress());
            if (refusal != null) {
                event.setKickReason(refusal);
                event.setCancelled(true);
            }
        });
        events.register(PlayerQuitEvent.class,
                event -> lastSeen.record(event.getPlayer().getName(), System.currentTimeMillis()));
    }

    public PunishmentStore getPunishments() {
        return punishments;
    }

    public Whitelist getWhitelist() {
        return whitelist;
    }

    public LastSeen getLastSeen() {
        return lastSeen;
    }

    // ===== The two decisions =====

    /**
     * Why {@code name} coming from {@code address} may not connect, or {@code null} if they may.
     *
     * <p>Order is deliberate: the address first, then the name, then the whitelist. An ip ban is the
     * broadest thing on the list and the one applied to somebody who is already evading a name ban, so it
     * is asked first; the whitelist is asked last because "you are not on the list" is the least
     * informative thing to be told and the least useful to leak.
     *
     * <p>An <b>operator skips the whitelist</b> but not a ban — see {@link Whitelist}.
     */
    public String refusalFor(String name, String address) {
        long now = System.currentTimeMillis();
        Punishment ipBan = punishments.find(Punishment.Kind.IP_BAN, hostOf(address), now);
        if (ipBan != null) {
            return message("banned", ipBan, now);
        }
        Punishment ban = punishments.find(Punishment.Kind.BAN, name, now);
        if (ban != null) {
            return message("banned", ban, now);
        }
        if (whitelist.isEnabled() && !whitelist.contains(name) && !ops.isOp(name)) {
            return "{red}This server is whitelisted.";
        }
        return null;
    }

    /** The mute in force against {@code name}, or {@code null} if they may speak. */
    public Punishment muteFor(String name) {
        return punishments.find(Punishment.Kind.MUTE, name, System.currentTimeMillis());
    }

    /**
     * Refuse a command that speaks, if whoever ran it is muted, and tell them why.
     *
     * <p>Plain chat is not the only way to say something: {@code /me}, {@code /msg} and {@code /say} all
     * put a player's words in front of other people, and a mute that stopped only the first of those
     * would be a rule anybody could step around in one keystroke. The console is never muted — there is
     * nobody to punish.
     *
     * @return {@code true} if the caller must stop
     */
    public boolean silence(com.jedrock.api.command.CommandSender sender) {
        if (!(sender instanceof com.jedrock.api.player.Player player)) {
            return false;
        }
        Punishment mute = muteFor(player.getName());
        if (mute == null) {
            return false;
        }
        sender.sendMessage(muteNotice(mute));
        return true;
    }

    /** What a muted player is told when they try to speak — once per attempt, only to them. */
    public String muteNotice(Punishment mute) {
        long now = System.currentTimeMillis();
        return "{red}You are muted" + until(mute, now) + "{red}. {gray}"
                + ChatText.escape(mute.reason());
    }

    private static String message(String verb, Punishment punishment, long now) {
        return "{red}You are " + verb + until(punishment, now) + "{red}.\n{gray}"
                + ChatText.escape(punishment.reason());
    }

    private static String until(Punishment punishment, long now) {
        return punishment.isPermanent() ? ""
                : "{red} for another {white}" + Durations.describe(punishment.remaining(now));
    }

    /**
     * The address without its port. A connection reports {@code 1.2.3.4:51234} and the port is a different
     * number every time somebody reconnects, so banning the whole string would ban one TCP connection.
     */
    public static String hostOf(String address) {
        if (address == null) {
            return "";
        }
        String value = address.trim();
        if (value.startsWith("/")) {
            value = value.substring(1); // Netty renders an InetSocketAddress with a leading slash
        }
        int colon = value.lastIndexOf(':');
        // Only strip a trailing :port — an IPv6 address is full of colons and ends in ] before one.
        if (colon > 0 && value.indexOf(':') == colon) {
            return value.substring(0, colon);
        }
        int bracket = value.lastIndexOf(']');
        return bracket > 0 && colon > bracket ? value.substring(0, colon) : value;
    }
}
