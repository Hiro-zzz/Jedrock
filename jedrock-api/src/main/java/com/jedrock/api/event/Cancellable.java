package com.jedrock.api.event;

/**
 * Mixin for cancellable events. Lightweight.
 */
public interface Cancellable {

    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
