package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * One loaded script — a {@code .js} file, its Rhino scope, and everything it registered while it ran. The
 * manager keeps this so a reload or shutdown can cleanly tear the script down: unregister its event
 * listeners and call its {@code onDisable} if it defined one.
 */
final class ScriptPlugin {

    private final String name;
    private final Scriptable scope;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private final long lastModified;
    private volatile Function onDisable;

    ScriptPlugin(String name, Scriptable scope, long lastModified) {
        this.name = name;
        this.scope = scope;
        this.lastModified = lastModified;
    }

    String name() {
        return name;
    }

    Scriptable scope() {
        return scope;
    }

    long lastModified() {
        return lastModified;
    }

    /** Remember an event subscription so it can be removed when the script is unloaded. */
    void addSubscription(EventBus.Subscription subscription) {
        subscriptions.add(subscription);
    }

    List<EventBus.Subscription> subscriptions() {
        return subscriptions;
    }

    Function onDisable() {
        return onDisable;
    }

    void setOnDisable(Function onDisable) {
        this.onDisable = onDisable;
    }
}
