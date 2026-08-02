package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Moderation;
import com.jedrock.core.moderation.Punishment;

import java.util.List;

/**
 * The {@code punishments} global — bans, ip-bans, mutes and the whitelist, as scripts see them.
 *
 * <pre>{@code
 *   punishments.ban('griefer', 'Broke spawn', '3d');     // '3d', 30000 (ms), or nothing for permanent
 *   if (punishments.isMuted(player)) { … }
 *   punishments.pardon('griefer');                        // every kind at once
 *   punishments.whitelist().add('alice');
 * }</pre>
 *
 * <p>Here for the same reason the {@code permissions} global is: without it the only way for a script to
 * ban somebody was to build a {@code '/ban ' + name + ' ' + reason} string and hand it to
 * {@code dispatchCommand} — which works right up until a name or a reason contains a space, and which
 * cannot answer a question at all. An anti-spam script wants to <em>ask</em> whether somebody is already
 * muted, not shell out to find out.
 *
 * <p>These are <b>server state</b>, like regions and permissions: written to the store immediately, and
 * not torn down when the plugin reloads. A script that bans somebody on Tuesday has banned them, not
 * borrowed them until the next hot reload.
 *
 * <p>Targets are names (and an ip ban's target is an address). See {@code Punishment} for why that, and
 * not a uuid, is the identity on a server with no authentication.
 */
public final class ScriptPunishments {

    private final Moderation moderation;
    private final com.jedrock.api.Server server;
    /** Recorded as the issuer, so the ban list says which plugin did it rather than "Server". */
    private final String issuer;

    ScriptPunishments(Moderation moderation, com.jedrock.api.Server server, String pluginName) {
        this.moderation = moderation;
        this.server = server;
        this.issuer = "script:" + pluginName;
    }

    // ===== Handing one out =====

    /** Ban a name permanently, with no reason given. */
    public void ban(Object target) {
        ban(target, null, null);
    }

    /** Ban a name permanently. */
    public void ban(Object target, String reason) {
        ban(target, reason, null);
    }

    /**
     * Ban a name. {@code duration} is a string like {@code '2d'}, a number of milliseconds, or
     * {@code null} for permanent. Disconnects them if they are online.
     */
    public void ban(Object target, String reason, Object duration) {
        String name = nameOf(target);
        record(Punishment.Kind.BAN, name, reason, duration);
        server.getPlayer(name).ifPresent(p -> p.kick("{red}You are banned.\n{gray}"
                + (reason == null ? Punishment.NO_REASON : reason)));
    }

    /** Ban the address a player is on, or an address given directly. */
    public void banIp(Object target, String reason, Object duration) {
        String address = server.getPlayer(nameOf(target))
                .map(p -> Moderation.hostOf(p.getAddress()))
                .orElseGet(() -> Moderation.hostOf(nameOf(target)));
        if (address.isBlank()) {
            throw new IllegalArgumentException("no address for '" + nameOf(target)
                    + "' — they are offline, so pass the address itself");
        }
        record(Punishment.Kind.IP_BAN, address, reason, duration);
        for (Player player : server.getPlayers()) {
            if (address.equalsIgnoreCase(Moderation.hostOf(player.getAddress()))) {
                player.kick("{red}Your address is banned.");
            }
        }
    }

    public void banIp(Object target, String reason) {
        banIp(target, reason, null);
    }

    /** Stop a name being heard — chat and every command that speaks. */
    public void mute(Object target, String reason, Object duration) {
        String name = nameOf(target);
        record(Punishment.Kind.MUTE, name, reason, duration);
        Punishment mute = moderation.getPunishments().find(Punishment.Kind.MUTE, name,
                System.currentTimeMillis());
        if (mute != null) {
            server.getPlayer(name).ifPresent(p -> p.sendMessage(moderation.muteNotice(mute)));
        }
    }

    public void mute(Object target, String reason) {
        mute(target, reason, null);
    }

    public void mute(Object target) {
        mute(target, null, null);
    }

    /** Disconnect somebody. Records nothing — a kick that should outlast the reconnect is a ban. */
    public boolean kick(Object target, String reason) {
        return server.getPlayer(nameOf(target)).map(p -> {
            p.kick(reason == null ? "{red}Kicked" : reason);
            return true;
        }).orElse(false);
    }

    public boolean kick(Object target) {
        return kick(target, null);
    }

    // ===== Lifting one =====

    /** Lift every punishment on a target. @return how many were lifted */
    public int pardon(Object target) {
        return moderation.getPunishments().pardon(nameOf(target));
    }

    /** Lift one kind — {@code 'ban'}, {@code 'ip'} or {@code 'mute'}. */
    public boolean pardon(Object target, String kind) {
        Punishment.Kind resolved = requireKind(kind);
        return moderation.getPunishments().remove(resolved, nameOf(target));
    }

    // ===== Asking =====

    public boolean isBanned(Object target) {
        return find(Punishment.Kind.BAN, nameOf(target)) != null;
    }

    public boolean isMuted(Object target) {
        return find(Punishment.Kind.MUTE, nameOf(target)) != null;
    }

    /** Whether an address is banned. Pass an address, or a player to test the one they are connected on. */
    public boolean isIpBanned(Object target) {
        String address = server.getPlayer(nameOf(target))
                .map(p -> Moderation.hostOf(p.getAddress()))
                .orElseGet(() -> Moderation.hostOf(nameOf(target)));
        return find(Punishment.Kind.IP_BAN, address) != null;
    }

    /** The punishment in force, or {@code null}. {@code kind} is {@code 'ban'} / {@code 'ip'} / {@code 'mute'}. */
    public Info info(Object target, String kind) {
        Punishment found = find(requireKind(kind), nameOf(target));
        return found == null ? null : new Info(found);
    }

    /** Every punishment of a kind that is currently in force. */
    public Info[] list(String kind) {
        List<Punishment> live = moderation.getPunishments()
                .list(requireKind(kind), System.currentTimeMillis());
        Info[] out = new Info[live.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Info(live.get(i));
        }
        return out;
    }

    /** When a player was last here, in millis since the epoch; {@code 0} if this server never saw them go. */
    public double lastSeen(Object target) {
        return moderation.getLastSeen().lastSeen(nameOf(target));
    }

    /** The whitelist — the other half of the same gate. */
    public ScriptWhitelist whitelist() {
        return new ScriptWhitelist(moderation.getWhitelist());
    }

    // ===== Shapes a script sees =====

    /** One punishment, read-only, as a bean rather than a record so a script reads {@code getReason()}. */
    public static final class Info {
        private final Punishment punishment;

        Info(Punishment punishment) {
            this.punishment = punishment;
        }

        public String getKind() { return punishment.kind().shortName(); }

        public String getTarget() { return punishment.target(); }

        public String getReason() { return punishment.reason(); }

        public String getIssuer() { return punishment.issuer(); }

        public double getIssuedAt() { return punishment.issuedAt(); }

        /** Millis since the epoch, or 0 for a permanent one. */
        public double getExpiresAt() { return punishment.expiresAt(); }

        public boolean isPermanent() { return punishment.isPermanent(); }

        /** Millis left, or -1 if permanent. */
        public double getRemaining() { return punishment.remaining(System.currentTimeMillis()); }

        @Override
        public String toString() {
            return getKind() + "[" + getTarget() + "]";
        }
    }

    /** The whitelist as scripts see it. */
    public static final class ScriptWhitelist {
        private final com.jedrock.core.moderation.Whitelist whitelist;

        ScriptWhitelist(com.jedrock.core.moderation.Whitelist whitelist) {
            this.whitelist = whitelist;
        }

        public boolean isEnabled() { return whitelist.isEnabled(); }

        public void setEnabled(boolean enabled) { whitelist.setEnabled(enabled); }

        public boolean has(Object target) { return whitelist.contains(nameOf(target)); }

        public boolean add(Object target) { return whitelist.add(nameOf(target)); }

        public boolean remove(Object target) { return whitelist.remove(nameOf(target)); }

        public String[] names() { return whitelist.names().toArray(new String[0]); }
    }

    // ===== Plumbing =====

    private Punishment find(Punishment.Kind kind, String target) {
        return moderation.getPunishments().find(kind, target, System.currentTimeMillis());
    }

    private void record(Punishment.Kind kind, String target, String reason, Object duration) {
        long millis = durationMillis(duration);
        long now = System.currentTimeMillis();
        moderation.getPunishments().add(new Punishment(kind, target, reason, issuer, now,
                millis == Durations.PERMANENT ? 0L : now + millis));
    }

    /** {@code null} / {@code '2d'} / a number of milliseconds — all three, because all three read naturally. */
    private static long durationMillis(Object duration) {
        Object value = ScriptJson.unwrap(duration);
        if (value == null || value instanceof org.mozilla.javascript.Undefined) {
            return Durations.PERMANENT;
        }
        if (value instanceof Number number) {
            long millis = number.longValue();
            return millis <= 0 ? Durations.PERMANENT : millis;
        }
        long parsed = Durations.parse(value.toString());
        if (parsed == Durations.NOT_A_DURATION) {
            throw new IllegalArgumentException("'" + value + "' is not a duration — try '30m', '2d', "
                    + "a number of milliseconds, or nothing for permanent");
        }
        return parsed;
    }

    private static Punishment.Kind requireKind(String kind) {
        Punishment.Kind resolved = Punishment.Kind.byName(kind);
        if (resolved == null) {
            throw new IllegalArgumentException("'" + kind + "' is not a kind — 'ban', 'ip' or 'mute'");
        }
        return resolved;
    }

    /** A player, a script player, or a plain name — all of which a script will pass. */
    private static String nameOf(Object target) {
        Object value = ScriptJson.unwrap(target);
        Player player = ScriptWrapFactory.unwrapPlayer(value);
        if (player != null) {
            return player.getName();
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        throw new IllegalArgumentException("punishments expects a player or a name");
    }
}
