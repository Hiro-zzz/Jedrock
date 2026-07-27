package com.jedrock.core.plugin;

import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;

/**
 * What one player is allowed, as a script sees it — their groups, their own nodes, and the op switch.
 *
 * <pre>{@code
 *   var p = permissions.forPlayer(player);
 *   p.addGroup('builders');                          // a role
 *   p.add('jedrock.region.plot7.build');             // and one thing that is just theirs
 *   if (p.has('example.secret')) …                   // resolved: own nodes + groups + op
 * }</pre>
 *
 * <p>Both halves matter and they answer different questions. A <b>group</b> says what this <em>kind</em> of
 * player may do; a player's <b>own nodes</b> cover the cases that are genuinely about one person — the
 * owner of one plot — without inventing a throwaway group for them. Deny wins between the two, either way
 * round, so {@code -node} on the player overrides a group's grant and a group's {@code -node} overrides
 * theirs.
 *
 * <p>Keyed by <b>name</b>, because that is how the permission file is keyed. So this view works for a
 * player who isn't online — {@code permissions.forPlayer('Steve')} prepares somebody's rights before they
 * ever log in — and it follows the name rather than the account across a rename.
 *
 * <p>Every write persists immediately. This is server state, not the plugin's: it outlives the script.
 */
public final class ScriptUserPermissions {

    private final PermissionManager permissions;
    private final OpList ops;
    private final String name;

    ScriptUserPermissions(PermissionManager permissions, OpList ops, String name) {
        this.permissions = permissions;
        this.ops = ops;
        this.name = name;
    }

    /** The player name this view is about. */
    public String getName() {
        return name;
    }

    /**
     * Whether this player is granted {@code node} — their own nodes, every group they're in (and those
     * groups' parents), and op, which holds everything.
     */
    public boolean has(String node) {
        return isOp() || permissions.has(name, node);
    }

    // ===== Groups: what this kind of player may do =====

    /** The groups this player is in. Empty means they fall to the default group. */
    public String[] getGroups() {
        return permissions.userGroups(name).toArray(new String[0]);
    }

    /** Put them in a group. @return {@code false} if there's no such group, or they were already in it */
    public boolean addGroup(String group) {
        return permissions.addUserGroup(name, group);
    }

    /** Take them out of a group. @return {@code false} if they weren't in it */
    public boolean removeGroup(String group) {
        return permissions.removeUserGroup(name, group);
    }

    // ===== Their own nodes: what this person may do =====

    /** The nodes granted to this player alone, not counting anything their groups give them. */
    public String[] getNodes() {
        return permissions.userPermissions(name).toArray(new String[0]);
    }

    /** Grant a node to this player alone. @return {@code false} if they already had it */
    public boolean add(String node) {
        return permissions.addUserPermission(name, node);
    }

    /** Take back a node granted to this player alone. @return {@code false} if they didn't have it */
    public boolean remove(String node) {
        return permissions.removeUserPermission(name, node);
    }

    // ===== Op: the super-user switch =====

    /** Whether this player is an operator, which holds every node and bypasses every region. */
    public boolean isOp() {
        return ops.isOp(name);
    }

    /**
     * Op or de-op this player.
     *
     * <p>The heaviest switch a script can throw — an op holds every node, passes every command's permission
     * check and is exempt inside every region. It is here because a script could already do it by sending
     * {@code /op} through {@code server.dispatchCommand}, so hiding it bought nothing but a worse spelling.
     *
     * @return whether the flag actually changed
     */
    public boolean setOp(boolean op) {
        return op ? ops.add(name) : ops.remove(name);
    }

    /** This player's effective chat prefix — the first non-blank one among their groups. */
    public String getPrefix() {
        return permissions.prefixOf(name);
    }

    @Override
    public String toString() {
        return "Permissions[" + name + "]";
    }
}
