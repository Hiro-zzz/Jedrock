package com.jedrock.core.entity;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe roster of live puppets, indexed by entity id — the puppet counterpart of the player registry.
 * Attack resolution and the join-time spawn relay iterate it; spawn / remove mutate it.
 */
public final class PuppetRegistry {

    private final ConcurrentHashMap<Long, CorePuppet> byEntityId = new ConcurrentHashMap<>();

    public void add(CorePuppet puppet) {
        byEntityId.put(puppet.getEntityId(), puppet);
    }

    /** Remove a puppet by entity id; returns it, or {@code null} if it wasn't registered. */
    public CorePuppet remove(long entityId) {
        return byEntityId.remove(entityId);
    }

    /** The puppet with the given entity id, or {@code null}. */
    public CorePuppet get(long entityId) {
        return byEntityId.get(entityId);
    }

    /** A live view of every registered puppet. */
    public Collection<CorePuppet> all() {
        return byEntityId.values();
    }

    public int size() {
        return byEntityId.size();
    }
}
