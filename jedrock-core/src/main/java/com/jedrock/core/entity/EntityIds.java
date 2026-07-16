package com.jedrock.core.entity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The single source of server-assigned entity ids, so players and puppets draw from one space and never
 * collide. Ids start above the self-ids the protocol handlers hardcode for a client's own player (JE
 * JoinGame / PE StartGame both use 1), so an avatar's id can never clash with the client's own.
 */
public final class EntityIds {

    private EntityIds() {}

    private static final AtomicLong NEXT = new AtomicLong(1000);

    /** The next unique entity id. Thread-safe. */
    public static long next() {
        return NEXT.getAndIncrement();
    }
}
