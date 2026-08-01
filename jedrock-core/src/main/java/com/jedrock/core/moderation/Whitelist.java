package com.jedrock.core.moderation;

import com.jedrock.core.data.DataStore;
import com.jedrock.utils.JLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who may connect at all, when the server has decided that is a shorter list than "everybody".
 *
 * <p>The other half of the same gate the ban list sits on, and kept beside it for that reason: a ban
 * answers "not this person" and a whitelist answers "only these people", and both are checked in the same
 * breath at the same moment. <b>Operators are exempt</b> — a whitelist that can lock the administrator out
 * of their own server the moment they enable it is a foot-gun, not a feature. A ban is not waived for an
 * op, because a ban is a decision somebody made deliberately and the console can always lift it.
 *
 * <p>Being on the list does nothing while the list is off, which is what makes it safe to build one before
 * turning it on.
 */
public final class Whitelist {

    private static final JLogger LOGGER = JLogger.getLogger("Moderation");

    /** The table holding the names. */
    private static final String TABLE = "whitelist";
    /** …and the one holding whether it is switched on, since that is a fact about the server, not a name. */
    private static final String SETTINGS = "moderation-settings";
    private static final String ENABLED_KEY = "whitelist.enabled";

    private final DataStore store;
    private final Set<String> names = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile boolean enabled;

    public Whitelist(DataStore store) {
        this.store = store;
        for (String name : store.load(TABLE).keySet()) {
            names.add(key(name));
        }
        this.enabled = Boolean.parseBoolean(store.load(SETTINGS).getOrDefault(ENABLED_KEY, "false"));
        if (enabled || !names.isEmpty()) {
            LOGGER.info("Whitelist " + (enabled ? "ON" : "off") + " with " + names.size() + " name(s)");
        }
    }

    /** Whether the gate is being applied at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Turn it on or off. Persists, so it survives the restart somebody does right after enabling it. */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        Map<String, String> rows = new LinkedHashMap<>(store.load(SETTINGS));
        rows.put(ENABLED_KEY, Boolean.toString(enabled));
        store.save(SETTINGS, rows);
        LOGGER.info("Whitelist " + (enabled ? "enabled" : "disabled"));
    }

    /** Whether {@code name} is on the list — regardless of whether the list is being enforced. */
    public boolean contains(String name) {
        return name != null && names.contains(key(name));
    }

    /** @return {@code true} if this call is the one that added them */
    public boolean add(String name) {
        if (name == null || name.isBlank() || !names.add(key(name))) {
            return false;
        }
        save();
        return true;
    }

    /** @return {@code true} if they were on it */
    public boolean remove(String name) {
        if (name == null || !names.remove(key(name))) {
            return false;
        }
        save();
        return true;
    }

    /** Every name on the list. */
    public List<String> names() {
        synchronized (names) {
            return new ArrayList<>(names);
        }
    }

    public int size() {
        return names.size();
    }

    private void save() {
        Map<String, String> rows = new LinkedHashMap<>();
        synchronized (names) {
            for (String name : names) {
                rows.put(name, "1"); // a set, in a store that keeps key=value pairs
            }
        }
        store.save(TABLE, rows);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
