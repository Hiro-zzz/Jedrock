package com.jedrock.core.player;

import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of online players.
 *
 * <p>Maintains three indexes so joins, disconnects and lookups are all O(1):
 * by UUID, by lower-cased name, and by connection (for disconnect handling).
 */
public final class PlayerRegistry {

    private final ConcurrentHashMap<UUID, CorePlayer> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerConnection, UUID> byConnection = new ConcurrentHashMap<>();

    public void add(CorePlayer player) {
        byId.put(player.getUniqueId(), player);
        byName.put(nameKey(player.getName()), player.getUniqueId());
        byConnection.put(player.getConnection(), player.getUniqueId());
    }

    public Optional<Player> getById(UUID uuid) {
        return Optional.ofNullable(byId.get(uuid));
    }

    public Optional<Player> getByName(String name) {
        UUID uuid = byName.get(nameKey(name));
        return uuid == null ? Optional.empty() : Optional.ofNullable(byId.get(uuid));
    }

    public Optional<Player> getByConnection(PlayerConnection connection) {
        UUID uuid = byConnection.get(connection);
        return uuid == null ? Optional.empty() : Optional.ofNullable(byId.get(uuid));
    }

    /**
     * Hot-path variant of {@link #getByConnection}: returns the player or {@code null}, allocating
     * no {@link Optional}. Used on per-packet paths (movement) where the Optional is pure garbage.
     */
    public CorePlayer getByConnectionOrNull(PlayerConnection connection) {
        UUID uuid = byConnection.get(connection);
        return uuid == null ? null : byId.get(uuid);
    }

    /**
     * Find an online player by their avatar entity id, or {@code null}. A linear scan over the roster —
     * player counts are tiny and attacks are infrequent, so no dedicated index is kept. Used to resolve
     * a PvP attack's target-entity id back to the player being hit.
     */
    public CorePlayer getByEntityIdOrNull(long entityId) {
        for (CorePlayer p : byId.values()) {
            if (p.getEntityId() == entityId) {
                return p;
            }
        }
        return null;
    }

    /**
     * Live view of the online players for internal hot-path iteration. Unlike {@link #all()} it
     * returns the map's cached values view directly, so calling it per packet allocates no
     * unmodifiable wrapper. Read-only by convention — do not mutate.
     */
    public Collection<CorePlayer> online() {
        return byId.values();
    }

    /**
     * Remove the player associated with a connection.
     *
     * @return the removed player, or {@code null} if the connection never registered
     *         (e.g. it disconnected before login).
     */
    public CorePlayer removeByConnection(PlayerConnection connection) {
        UUID uuid = byConnection.remove(connection);
        if (uuid == null) {
            return null;
        }
        CorePlayer player = byId.remove(uuid);
        if (player != null) {
            byName.remove(nameKey(player.getName()), uuid);
        }
        return player;
    }

    public Collection<Player> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    private static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
