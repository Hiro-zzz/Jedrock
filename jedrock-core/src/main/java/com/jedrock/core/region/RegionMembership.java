package com.jedrock.core.region;

import java.util.Arrays;

/**
 * What regions one player is standing in, remembered between movement reports so a crossing can be told
 * from a step.
 *
 * <p>It lives on the player rather than in a map inside {@link RegionManager} for two reasons: a map lookup
 * per movement packet is a cost the hottest path in the server shouldn't carry, and a map keyed by player
 * is one more thing that has to be cleaned up when somebody disconnects. Here the membership dies with the
 * player object, because it <em>is</em> part of the player.
 *
 * <p>Both arrays are reused. A player walking around inside a region re-derives the same membership twenty
 * times a second, and that has to allocate nothing — so the candidate set is filled into a scratch buffer
 * and compared, and only an actual crossing (rare, and human-paced) copies it.
 *
 * <p>Not thread-safe, and doesn't need to be: a player's movement is handled on their own network thread.
 */
public final class RegionMembership {

    private static final CoreRegion[] NONE = new CoreRegion[0];

    /** The regions the player is known to be in, in registry order. */
    private CoreRegion[] inside = NONE;
    /** Reused scratch for the candidate set; never read outside an update. */
    private CoreRegion[] scratch = NONE;

    /** A scratch buffer with room for {@code capacity} regions, grown (never shrunk) as needed. */
    CoreRegion[] scratch(int capacity) {
        if (scratch.length < capacity) {
            scratch = new CoreRegion[Math.max(capacity, 4)];
        }
        return scratch;
    }

    /** The regions currently recorded. Read-only by convention — the array is the live one. */
    public CoreRegion[] inside() {
        return inside;
    }

    /** True if {@code candidate[0..count)} is exactly what is already recorded — the common case, no crossing. */
    boolean matches(CoreRegion[] candidate, int count) {
        if (count != inside.length) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (inside[i] != candidate[i]) {
                return false;
            }
        }
        return true;
    }

    /** True if {@code region} is in the recorded set. */
    boolean holds(CoreRegion region) {
        for (CoreRegion held : inside) {
            if (held == region) {
                return true;
            }
        }
        return false;
    }

    /** Adopt {@code candidate[0..count)} as the recorded set. The only path that allocates. */
    void commit(CoreRegion[] candidate, int count) {
        inside = count == 0 ? NONE : Arrays.copyOf(candidate, count);
    }
}
