package com.jedrock.core.plugin;

/**
 * The handle {@code events.on(…)} hands back — one listener, and a way to stop it.
 *
 * <pre>{@code
 *   const sub = events.on('PlayerMove', watchThem);
 *   scheduler.runLater(function () { sub.remove(); }, 20 * 60);   // …for a minute
 * }</pre>
 *
 * <p>Before this a script could only start listening. Stopping meant editing the file and letting the whole
 * plugin reload, which is a blunt instrument for a listener that was only ever meant to be temporary — a
 * countdown, a tutorial step, a round of a minigame. Everything registered is still torn down with the
 * plugin either way; this just means a script does not have to wait for that.
 *
 * <p>Removing twice is harmless, and so is removing a listener the plugin's own teardown has already taken:
 * both underlying registries treat removal as idempotent, which is what lets a script hold a handle without
 * having to reason about who won.
 */
public final class ScriptSubscription {

    /** What actually unregisters — an {@code EventBus.Subscription} or a custom-event registration. */
    private final Runnable remover;
    private volatile boolean removed;

    ScriptSubscription(Runnable remover) {
        this.remover = remover;
    }

    /** Stop this listener. Idempotent. */
    public void remove() {
        if (removed) {
            return;
        }
        removed = true;
        remover.run();
    }

    /** Whether {@link #remove} has been called on this handle. */
    public boolean isRemoved() {
        return removed;
    }

    @Override
    public String toString() {
        return "Subscription[" + (removed ? "removed" : "active") + "]";
    }
}
