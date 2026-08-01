package com.jedrock.core.moderation;

import com.jedrock.core.data.DataStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each player was last here — the answer to "is this account dormant or did they leave five minutes
 * ago", which is the question anybody about to ban somebody actually asks first.
 *
 * <p>Written on the way out rather than continuously: a timestamp per quit is a handful of rows a day,
 * where a timestamp per tick would be a write loop. A player who is online right now has no useful entry
 * and does not need one — the roster already knows they are here.
 *
 * <p>Deliberately not the same record as {@code player-worlds}: that one exists so a returning player
 * lands in the right world and is keyed by uuid, while this is keyed by name because it answers a question
 * a moderator asks by name about somebody who may never have connected.
 */
public final class LastSeen {

    private static final String TABLE = "last-seen";

    private final DataStore store;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public LastSeen(DataStore store) {
        this.store = store;
        for (Map.Entry<String, String> row : store.load(TABLE).entrySet()) {
            try {
                seen.put(key(row.getKey()), Long.parseLong(row.getValue().trim()));
            } catch (NumberFormatException ignored) {
                // One unreadable timestamp costs one entry, not the file.
            }
        }
    }

    /** Record that {@code name} was here at {@code when}. */
    public void record(String name, long when) {
        if (name == null || name.isBlank()) {
            return;
        }
        seen.put(key(name), when);
        save();
    }

    /** When {@code name} was last here, or {@code 0} if this server has never seen them leave. */
    public long lastSeen(String name) {
        return name == null ? 0L : seen.getOrDefault(key(name), 0L);
    }

    /** How many players have an entry. */
    public int size() {
        return seen.size();
    }

    private void save() {
        Map<String, String> rows = new LinkedHashMap<>();
        seen.forEach((name, when) -> rows.put(name, Long.toString(when)));
        store.save(TABLE, rows);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
