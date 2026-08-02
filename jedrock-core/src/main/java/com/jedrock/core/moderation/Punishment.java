package com.jedrock.core.moderation;

import java.util.Locale;

/**
 * One punishment on the books: what it is, who it is on, who put it there, and when it lapses.
 *
 * <p><b>Targets are names, not uuids</b>, which is worth stating out loud because it is a trade rather
 * than an oversight. This server has no Mojang authentication at all — a 0.14 client picks whatever name
 * it likes at the login screen — so a uuid is not a stronger identity here, it is merely a less usable
 * one. It also has to be possible to ban somebody who has never connected, and a name is all you have
 * then. The same reasoning already put {@code ops.txt} on names. The cost is real and worth knowing: a
 * player who changes their name walks around a ban, which is what {@link Kind#IP_BAN} is for.
 *
 * <p>An <b>ip ban</b> targets an address instead, and is the blunter instrument for exactly that reason —
 * addresses are shared by households and reassigned by providers, so it catches people it did not mean to.
 *
 * <p>{@code expiresAt} of {@code 0} means permanent. Nothing sweeps expired entries on a timer: they are
 * read as absent the moment they lapse, and dropped the next time the file is written. A punishment that
 * has run out is not an event anybody is waiting for.
 */
public record Punishment(Kind kind, String target, String reason, String issuer,
                         long issuedAt, long expiresAt) {

    /** What a punishment stops somebody doing. */
    public enum Kind {
        /** Refused at the login gate, by name. */
        BAN("bans"),
        /** Refused at the login gate, by the address they connect from. */
        IP_BAN("ip-bans"),
        /** Allowed in, but nothing they say reaches anybody else. */
        MUTE("mutes");

        private final String table;

        Kind(String table) {
            this.table = table;
        }

        /** The {@link com.jedrock.core.data.DataStore} table this kind is persisted in. */
        public String table() {
            return table;
        }

        /** The name a command or a script uses: {@code ban}, {@code ip}, {@code mute}. */
        public String shortName() {
            return this == IP_BAN ? "ip" : name().toLowerCase(Locale.ROOT);
        }

        /** Resolve a script's or a command's word, or {@code null} if it names nothing. */
        public static Kind byName(String name) {
            if (name == null) {
                return null;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "ban", "bans" -> BAN;
                case "ip", "ip-ban", "ipban", "ip-bans" -> IP_BAN;
                case "mute", "mutes" -> MUTE;
                default -> null;
            };
        }
    }

    /** The default when nobody said why. */
    public static final String NO_REASON = "No reason given";

    public Punishment {
        target = target == null ? "" : target.trim();
        reason = reason == null || reason.isBlank() ? NO_REASON : sanitize(reason);
        issuer = issuer == null || issuer.isBlank() ? "Server" : sanitize(issuer);
        expiresAt = Math.max(0L, expiresAt);
    }

    /** Whether this one never lapses on its own. */
    public boolean isPermanent() {
        return expiresAt == 0L;
    }

    /** Whether it has already run out as of {@code now}. */
    public boolean isExpired(long now) {
        return !isPermanent() && now >= expiresAt;
    }

    /** Milliseconds left, or {@code -1} for a permanent one. */
    public long remaining(long now) {
        return isPermanent() ? -1L : Math.max(0L, expiresAt - now);
    }

    /**
     * A newline would end the line this is stored on and take the rest of the file's meaning with it, and
     * a reason typed by a moderator is arbitrary text. Everything else — {@code =}, {@code |}, markup —
     * survives, because the format is a first-{@code =} split and a limited join.
     */
    private static String sanitize(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
