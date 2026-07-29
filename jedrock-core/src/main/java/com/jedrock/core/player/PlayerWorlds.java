package com.jedrock.core.player;

import com.jedrock.core.data.DataStore;
import com.jedrock.utils.JLogger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which world each player was last standing in, kept in the {@code player-worlds} table of whatever
 * {@link DataStore} the server is configured with — the {@code data/player-worlds.txt} it has always
 * written, or a database, without this class knowing which.
 *
 * <p>Keyed by <b>uuid</b> rather than name, unlike the op list: a 0.14 client picks its own username over
 * a plaintext login, and "where you were" is the kind of fact a chosen name must not be able to claim.
 *
 * <p>Only a world is remembered, never a position. That is the whole of the feature and the reason it can
 * be a text file: a returning player arrives at the spawn of the world they left, which is a place the
 * server can always put someone. A remembered <em>spot</em> would have to answer what happens when the
 * ground under it was dug away or the world shrank around it, and this server does not model falling.
 *
 * <p>An entry is written the moment a player crosses between worlds, so nothing accumulates for the
 * players who never leave the default one, and a crash can't lose what a clean shutdown would have saved.
 * Reads are in-memory and thread-safe (they happen on I/O threads, mid-join); a write also persists. A
 * store that can't be read starts the map empty — everyone joins into the default world, which is exactly
 * the behaviour this class adds to, so failing to load costs a convenience and never a login.
 */
public final class PlayerWorlds {

    private static final JLogger LOGGER = JLogger.getLogger(PlayerWorlds.class);

    /** The store's table name — a filename in the file backend, a table in a database. */
    static final String TABLE = "player-worlds";

    private final DataStore store;
    /** uuid → world name, as written. Insertion-ordered so the store doesn't churn between saves. */
    private final Map<UUID, String> worlds = new LinkedHashMap<>();

    public PlayerWorlds(DataStore store) {
        this.store = store;
        load();
    }

    /** The name of the world {@code uuid} was last in, or {@code null} if nothing was ever recorded. */
    public synchronized String worldOf(UUID uuid) {
        return uuid == null ? null : worlds.get(uuid);
    }

    /**
     * Record that {@code uuid} is now in {@code worldName}. A no-op (and no write) when it is already
     * what we hold — travel back and forth between two worlds costs one file write per actual change.
     *
     * @return {@code true} if something changed and the store was rewritten
     */
    public synchronized boolean remember(UUID uuid, String worldName) {
        if (uuid == null || worldName == null || worldName.isBlank()) {
            return false;
        }
        if (worldName.equals(worlds.get(uuid))) {
            return false;
        }
        worlds.put(uuid, worldName);
        save();
        return true;
    }

    /**
     * Drop what we know about {@code uuid} — what a login does when the remembered world is gone, so the
     * fallback to the default world is recorded rather than re-decided on every join.
     *
     * @return {@code true} if there was an entry to remove
     */
    public synchronized boolean forget(UUID uuid) {
        if (uuid == null || worlds.remove(uuid) == null) {
            return false;
        }
        save();
        return true;
    }

    /** How many players have an entry — for {@code /world} diagnostics and tests. */
    public synchronized int size() {
        return worlds.size();
    }

    private void load() {
        for (Map.Entry<String, String> row : store.load(TABLE).entrySet()) {
            if (row.getValue() == null || row.getValue().isBlank()) {
                continue; // a uuid with no world names nothing; the same rule remember() applies
            }
            try {
                worlds.put(UUID.fromString(row.getKey()), row.getValue());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping a " + TABLE + " entry whose key isn't a uuid: " + row.getKey());
            }
        }
    }

    private void save() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> entry : worlds.entrySet()) {
            rows.put(entry.getKey().toString(), entry.getValue());
        }
        store.save(TABLE, rows);
    }
}
