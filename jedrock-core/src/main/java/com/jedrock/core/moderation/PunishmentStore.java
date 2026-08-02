package com.jedrock.core.moderation;

import com.jedrock.core.data.DataStore;
import com.jedrock.utils.JLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every ban, ip-ban and mute the server is holding, and the file they live in.
 *
 * <p>Persisted through {@link DataStore} rather than as its own text file, which is a deliberate
 * departure from {@code ops.txt} and {@code permissions.txt}. Those two stay text because an
 * administrator edits them by hand at two in the morning. A ban list is not edited by hand — it is
 * written by commands and read by the login gate — and it is the one piece of server state whose obvious
 * next want is <em>sharing</em>: a network of servers with one account of who is not welcome. That is the
 * case the storage layer exists for, so this uses it and inherits the jdbc backend for free.
 *
 * <p>Loaded once at boot, rewritten whenever it changes — the whole-table shape the store asks for.
 * Reads are from memory and safe from any thread, which matters because the login gate runs on an I/O
 * thread before a player exists.
 *
 * <p><b>Expiry is lazy.</b> Nothing ticks. A lapsed punishment reads as absent from the moment it lapses
 * and is dropped the next time the table is written, because a punishment running out is not an event
 * anyone is waiting for and a timer per ban would be a heartbeat bought for nothing.
 */
public final class PunishmentStore {

    private static final JLogger LOGGER = JLogger.getLogger("Moderation");

    private final DataStore store;
    /** Kind → (lower-cased target → punishment). Guarded by {@code this}; read under it too. */
    private final Map<Punishment.Kind, Map<String, Punishment>> live =
            new EnumMap<>(Punishment.Kind.class);

    public PunishmentStore(DataStore store) {
        this.store = store;
        for (Punishment.Kind kind : Punishment.Kind.values()) {
            live.put(kind, Collections.synchronizedMap(new LinkedHashMap<>()));
        }
        load();
    }

    // ===== Reading =====

    /**
     * The punishment of {@code kind} in force against {@code target} right now, or {@code null} — which
     * covers both "there was never one" and "there was and it has run out".
     */
    public Punishment find(Punishment.Kind kind, String target, long now) {
        if (target == null || target.isBlank()) {
            return null;
        }
        Punishment found = live.get(kind).get(key(target));
        return found == null || found.isExpired(now) ? null : found;
    }

    /** Every punishment of {@code kind} still in force, newest last. */
    public List<Punishment> list(Punishment.Kind kind, long now) {
        List<Punishment> out = new ArrayList<>();
        Map<String, Punishment> table = live.get(kind);
        synchronized (table) {
            for (Punishment p : table.values()) {
                if (!p.isExpired(now)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** How many punishments of {@code kind} are in force. */
    public int count(Punishment.Kind kind, long now) {
        return list(kind, now).size();
    }

    // ===== Writing =====

    /** Record a punishment, replacing any of the same kind already on that target. */
    public void add(Punishment punishment) {
        live.get(punishment.kind()).put(key(punishment.target()), punishment);
        save(punishment.kind());
        LOGGER.info(punishment.kind() + " on " + punishment.target() + " by " + punishment.issuer()
                + (punishment.isPermanent() ? " (permanent)"
                        : " for " + Durations.describe(punishment.expiresAt() - System.currentTimeMillis()))
                + ": " + punishment.reason());
    }

    /** Lift one punishment. @return {@code true} if there was one to lift */
    public boolean remove(Punishment.Kind kind, String target) {
        if (target == null || live.get(kind).remove(key(target)) == null) {
            return false;
        }
        save(kind);
        return true;
    }

    /**
     * Lift every kind of punishment on {@code target} — what {@code /pardon} means when nobody narrowed
     * it. @return how many were actually lifted
     */
    public int pardon(String target) {
        int lifted = 0;
        for (Punishment.Kind kind : Punishment.Kind.values()) {
            if (remove(kind, target)) {
                lifted++;
            }
        }
        return lifted;
    }

    // ===== Persistence =====

    /**
     * The stored form: {@code issuer|issuedAt|expiresAt|reason}. The reason goes last and the split is
     * limited to four, so a reason may contain {@code |} — and {@code =} is safe because the file format
     * splits on the <em>first</em> one. Only a newline is impossible, and {@link Punishment} takes those
     * out on the way in.
     */
    private static String encode(Punishment p) {
        return p.issuer() + "|" + p.issuedAt() + "|" + p.expiresAt() + "|" + p.reason();
    }

    private static Punishment decode(Punishment.Kind kind, String target, String value) {
        String[] parts = value.split("\\|", 4);
        if (parts.length < 4) {
            return null;
        }
        try {
            return new Punishment(kind, target, parts[3], parts[0],
                    Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void load() {
        long now = System.currentTimeMillis();
        for (Punishment.Kind kind : Punishment.Kind.values()) {
            Map<String, Punishment> table = live.get(kind);
            int lapsed = 0;
            for (Map.Entry<String, String> row : store.load(kind.table()).entrySet()) {
                Punishment parsed = decode(kind, row.getKey(), row.getValue());
                if (parsed == null) {
                    LOGGER.warn("Skipping an unreadable " + kind + " entry for '" + row.getKey() + "'");
                    continue;
                }
                if (parsed.isExpired(now)) {
                    lapsed++;
                    continue; // read as absent; the next write drops it for good
                }
                table.put(key(parsed.target()), parsed);
            }
            if (!table.isEmpty() || lapsed > 0) {
                LOGGER.info("Loaded " + table.size() + " " + kind.table()
                        + (lapsed > 0 ? " (" + lapsed + " expired)" : ""));
            }
        }
    }

    private void save(Punishment.Kind kind) {
        long now = System.currentTimeMillis();
        Map<String, String> rows = new LinkedHashMap<>();
        Map<String, Punishment> table = live.get(kind);
        synchronized (table) {
            table.values().removeIf(p -> p.isExpired(now)); // the sweep, at the only moment it is free
            for (Punishment p : table.values()) {
                rows.put(p.target(), encode(p));
            }
        }
        store.save(kind.table(), rows);
    }

    /** Targets are matched case-insensitively — a name is a name whichever way it was typed. */
    private static String key(String target) {
        return target.trim().toLowerCase(Locale.ROOT);
    }
}
