package com.jedrock.core.permission;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One permission group: a named bundle of permission nodes, optional parents it inherits, an optional chat
 * prefix, and a flag marking the group new players fall into. Resolution (wildcards, {@code -node} deny,
 * inheritance) lives in {@link PermissionManager}; this is just the mutable holder it reads and persists.
 *
 * <p>A node is a dotted string: a literal ({@code jedrock.command.tp}), a wildcard ({@code *} = everything,
 * {@code jedrock.command.*} = a subtree), or either of those prefixed {@code -} to explicitly deny.
 */
public final class PermissionGroup {

    private final String name;
    /** Granted / denied nodes, insertion-ordered; a leading {@code -} denies. */
    private final Set<String> permissions = new LinkedHashSet<>();
    /** Names of groups this one inherits (transitively expanded by the manager). */
    private final List<String> parents = new CopyOnWriteArrayList<>();
    private volatile boolean isDefault;
    /** Chat prefix (may carry {@code {color}} markup); blank = none. */
    private volatile String prefix = "";

    PermissionGroup(String name) {
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public String name() {
        return name;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public List<String> parents() {
        return parents;
    }

    public boolean isDefault() {
        return isDefault;
    }

    void setDefault(boolean value) {
        this.isDefault = value;
    }

    public String prefix() {
        return prefix;
    }

    void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Add a node (a {@code -} prefix denies). @return whether it was newly added. */
    boolean addPermission(String node) {
        return permissions.add(node.toLowerCase(Locale.ROOT));
    }

    /** Remove a node exactly as written. @return whether it was present. */
    boolean removePermission(String node) {
        return permissions.remove(node.toLowerCase(Locale.ROOT));
    }

    boolean addParent(String group) {
        String g = group.toLowerCase(Locale.ROOT);
        return !parents.contains(g) && parents.add(g);
    }

    boolean removeParent(String group) {
        return parents.remove(group.toLowerCase(Locale.ROOT));
    }
}
