package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;

/**
 * The {@code permissions} global — groups, players' rights and the op list, as a script sees them.
 *
 * <pre>{@code
 *   permissions.createGroup('builders')
 *              .inherit('default')
 *              .add('jedrock.region.plot7.build')
 *              .setPrefix('{aqua}[Builder] ');
 *
 *   permissions.forPlayer(player).addGroup('builders');
 *   permissions.forPlayer('Steve').add('jedrock.region.plot7.build');   // works offline too
 * }</pre>
 *
 * <p>Until this existed a script could <em>read</em> rights ({@code player.hasPermission}) but only change
 * them by sending {@code /perm} through {@code dispatchCommand} — building a command line as a string,
 * parsing nothing back, and finding out about a typo in the server log. The reason it wasn't here is worth
 * remembering: {@code server.getOpList()} used to be reachable by accident and was deliberately closed off
 * when the script surface became a written contract. This is the door being opened on purpose instead,
 * with a written shape.
 *
 * <p>Everything here is <b>server state</b>, like regions and saved scenes: it is not torn down with the
 * plugin, and every write persists to {@code permissions.txt} / {@code ops.txt} immediately. So a script
 * that sets its groups up on load is idempotent by construction — {@code createGroup} returns the group
 * that is already there rather than a fresh empty one.
 *
 * <p><b>Nodes</b> are the same strings the file and {@code /perm} use: a literal, {@code a.b.*},
 * {@code *}, or {@code -node} to deny. <b>Deny wins</b> wherever a grant and a deny meet.
 */
public final class ScriptPermissions {

    private final PermissionManager permissions;
    private final OpList ops;

    ScriptPermissions(PermissionManager permissions, OpList ops) {
        this.permissions = permissions;
        this.ops = ops;
    }

    // ===== Players =====

    /**
     * The rights of one player, by {@link Player} or by name.
     *
     * <p>A name works because the permission file is keyed by name, so rights can be prepared for somebody
     * who has never logged in.
     */
    public ScriptUserPermissions forPlayer(Object player) {
        return new ScriptUserPermissions(permissions, ops, nameOf(player));
    }

    /** Whether this player is granted {@code node} — their own nodes, their groups, and op. */
    public boolean has(Object player, String node) {
        return forPlayer(player).has(node);
    }

    /** Whether this player is an operator. */
    public boolean isOp(Object player) {
        return ops.isOp(nameOf(player));
    }

    /** Every operator's name. */
    public String[] getOps() {
        return ops.names().toArray(new String[0]);
    }

    // ===== Groups =====

    /** Every group's name, in file order. */
    public String[] getGroups() {
        return permissions.groups().stream()
                .map(com.jedrock.core.permission.PermissionGroup::name)
                .toArray(String[]::new);
    }

    /** The group called {@code name} (case-insensitive), or {@code null}. */
    public ScriptPermissionGroup group(String name) {
        var found = permissions.getGroup(name);
        return found == null ? null : new ScriptPermissionGroup(permissions, found.name());
    }

    /**
     * Create a group, or take the one that already exists under that name.
     *
     * <p>Unlike {@code regions.create}, which refuses a taken name, this is deliberately idempotent: a
     * group is a role a script usually wants to <em>declare</em> on every load, and returning the existing
     * one is what makes that safe. Nothing is lost either way, since taking it doesn't clear its nodes.
     */
    public ScriptPermissionGroup createGroup(String name) {
        var created = permissions.createGroup(name);
        return created == null ? null : new ScriptPermissionGroup(permissions, created.name());
    }

    /** Delete a group. @return {@code false} if there was none, or it is the default group */
    public boolean deleteGroup(String name) {
        return permissions.deleteGroup(name);
    }

    /** The group players fall into when they've been put in no other. */
    public ScriptPermissionGroup defaultGroup() {
        var found = permissions.defaultGroup();
        return found == null ? null : new ScriptPermissionGroup(permissions, found.name());
    }

    /** Make {@code name} the default group. @return {@code false} if there's no such group */
    public boolean setDefaultGroup(String name) {
        return permissions.setDefaultGroup(name);
    }

    /** Re-read {@code permissions.txt} from disk, discarding what is in memory (a scripted {@code /perm reload}). */
    public void reload() {
        permissions.reload();
    }

    /** A {@link Player} (wrapped or not) or a bare name — permissions are keyed by name either way. */
    private static String nameOf(Object player) {
        if (player instanceof CharSequence name) {
            return name.toString();
        }
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("permissions expects a player or a player name");
        }
        return target.getName();
    }
}
