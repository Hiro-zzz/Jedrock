package com.jedrock.core.plugin;

import com.jedrock.core.permission.PermissionGroup;
import com.jedrock.core.permission.PermissionManager;

/**
 * One permission group as a script sees it — a named bundle of nodes that players are put into.
 *
 * <pre>{@code
 *   var builders = permissions.createGroup('builders');
 *   builders.add('jedrock.region.plot7.build')
 *           .add('jedrock.command.tp')
 *           .setPrefix('{aqua}[Builder] ');
 *   builders.inherit('default');            // everything the default group grants, plus the above
 * }</pre>
 *
 * <p>Every write goes through the {@link PermissionManager}, which persists it immediately — a group is
 * server state, not the plugin's, so it outlives the script that made it and a hot reload doesn't undo it.
 * That also means a script re-running {@code createGroup} on every load is harmless: it takes the group
 * that is already there.
 *
 * <p>Nodes are the same strings the file and {@code /perm} use: a literal, a {@code a.b.*} wildcard,
 * {@code *} for everything, or {@code -node} to deny — and a deny beats a grant wherever the two meet.
 */
public final class ScriptPermissionGroup {

    private final PermissionManager permissions;
    private final String name;

    ScriptPermissionGroup(PermissionManager permissions, String name) {
        this.permissions = permissions;
        this.name = name;
    }

    /** The group's name, as it was created. */
    public String getName() {
        return name;
    }

    /** Whether this is the group players fall into when they've been put in no other. */
    public boolean isDefault() {
        PermissionGroup group = permissions.getGroup(name);
        return group != null && group.isDefault();
    }

    /** Make this the default group. The previous default stops being one. */
    public ScriptPermissionGroup makeDefault() {
        permissions.setDefaultGroup(name);
        return this;
    }

    /** The chat prefix worn by this group's members, or {@code ""}. Unified markup, e.g. {@code "{aqua}[Mod] "}. */
    public String getPrefix() {
        PermissionGroup group = permissions.getGroup(name);
        return group == null ? "" : group.prefix();
    }

    public ScriptPermissionGroup setPrefix(String prefix) {
        permissions.setGroupPrefix(name, prefix == null ? "" : prefix);
        return this;
    }

    /** The nodes granted directly by this group — not counting anything it inherits. */
    public String[] getNodes() {
        PermissionGroup group = permissions.getGroup(name);
        return group == null ? new String[0] : group.permissions().toArray(new String[0]);
    }

    /** Grant a node to this group. Returns this group, so calls chain. */
    public ScriptPermissionGroup add(String node) {
        permissions.addGroupPermission(name, node);
        return this;
    }

    /** Take a node back off this group. Returns this group, so calls chain. */
    public ScriptPermissionGroup remove(String node) {
        permissions.removeGroupPermission(name, node);
        return this;
    }

    /** The groups this one inherits from, nearest first. */
    public String[] getParents() {
        PermissionGroup group = permissions.getGroup(name);
        return group == null ? new String[0] : group.parents().toArray(new String[0]);
    }

    /** Inherit everything {@code parent} grants. Cycles are tolerated — resolution stops rather than loops. */
    public ScriptPermissionGroup inherit(String parent) {
        permissions.addGroupParent(name, parent);
        return this;
    }

    public ScriptPermissionGroup uninherit(String parent) {
        permissions.removeGroupParent(name, parent);
        return this;
    }

    /** Whether this group still exists — a script may be holding a group somebody has since deleted. */
    public boolean exists() {
        return permissions.getGroup(name) != null;
    }

    @Override
    public String toString() {
        return "PermissionGroup[" + name + "]";
    }
}
