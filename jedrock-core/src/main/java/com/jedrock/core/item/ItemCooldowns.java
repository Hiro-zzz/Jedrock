package com.jedrock.core.item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each player last used each item — the half of a cooldown that is <b>not</b> a property of the item.
 *
 * <p>Every script that wanted a cooldown before this wrote the same twenty lines: a map, a timestamp, a
 * subtraction, and a message. It is the same twenty lines every time because the shape never varies, which
 * is the argument for it being here instead.
 *
 * <p>It lives on the registry rather than on a {@link CoreCustomItem} for a reason that shows up the first
 * time somebody edits their plugin: a definition is thrown away and rebuilt on every hot reload, so a
 * cooldown kept on one would mean saving a file handed every player a fresh wand. The registry survives
 * reloads, so this does too.
 *
 * <p>The clock is passed in rather than read, so the rule is deterministically testable — the same choice
 * {@code SlotEchoGuard} makes for the same reason.
 */
public final class ItemCooldowns {

    /** Player → (item key → the {@code nanoTime} they last used it). Empty on a server that uses none. */
    private final Map<UUID, Map<String, Long>> used = new ConcurrentHashMap<>();

    /**
     * How long {@code player} must still wait before {@code key} answers again, in milliseconds; {@code 0}
     * if it is ready (which includes an item with no cooldown at all).
     */
    public long remainingMillis(UUID player, String key, long cooldownMillis, long now) {
        if (cooldownMillis <= 0) {
            return 0L;
        }
        Map<String, Long> mine = used.get(player);
        Long last = mine == null ? null : mine.get(key);
        if (last == null) {
            return 0L;
        }
        long elapsed = (now - last) / 1_000_000L;
        long left = cooldownMillis - elapsed;
        return left > 0 ? left : 0L;
    }

    /** Start {@code key}'s cooldown for {@code player}, as of {@code now}. */
    public void start(UUID player, String key, long now) {
        used.computeIfAbsent(player, p -> new ConcurrentHashMap<>()).put(key, now);
    }

    /** End it early — the item decided this one didn't count. */
    public void clear(UUID player, String key) {
        Map<String, Long> mine = used.get(player);
        if (mine != null) {
            mine.remove(key);
        }
    }

    /**
     * Forget everything about one player. Called when they leave: a cooldown is a courtesy to somebody
     * standing in front of you, and keeping one for a player who is gone is a leak with a uuid on it.
     */
    public void forget(UUID player) {
        used.remove(player);
    }

    /** How many players are being tracked — for tests, and for anyone wondering whether it leaks. */
    public int trackedPlayers() {
        return used.size();
    }
}
