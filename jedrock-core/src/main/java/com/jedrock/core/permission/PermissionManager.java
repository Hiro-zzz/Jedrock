package com.jedrock.core.permission;

import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A native group-based permission system: named {@link PermissionGroup}s (with inheritance, a chat prefix,
 * and a default group), player→group assignments, and node resolution with wildcards and explicit deny.
 * Ops (see {@code OpList}) are a separate super-user escape hatch resolved by the caller — this class only
 * knows groups.
 *
 * <p><b>Resolution</b> of {@link #has}: gather the player's groups (their assignments, or the default group
 * if none), expand inheritance, union the nodes, then — a <b>deny wins</b> — if any node denies the query
 * ({@code -node}, {@code -a.b.*}, {@code -*}) it's refused; else if any grants it ({@code node}, {@code
 * a.b.*}, {@code *}) it's allowed; else refused. Persisted to a plain, human-editable {@code permissions.txt}.
 *
 * <p>Every public method is {@code synchronized} — mutations come from the command thread while reads come
 * from chat/command dispatch; the load is off the hot path (once per command or chat line).
 */
public final class PermissionManager {

    private static final JLogger LOGGER = JLogger.getLogger(PermissionManager.class);

    private final Path file;
    /** Group name (lower-case) → group. Insertion-ordered for a stable listing / file. */
    private final Map<String, PermissionGroup> groups = new LinkedHashMap<>();
    /** Player name (lower-case) → the groups assigned to them. */
    private final Map<String, Set<String>> userGroups = new LinkedHashMap<>();

    public PermissionManager(Path file) {
        this.file = file;
        load();
        if (defaultGroup() == null) {
            // Always have a default group so new players resolve to something; seed + persist on first run.
            PermissionGroup def = getOrCreate("default");
            def.setDefault(true);
            save();
        }
    }

    // ===== resolution =====

    /**
     * Whether {@code playerName} is granted {@code node} by their groups. Does <b>not</b> consider op — the
     * caller ORs that in. Deny ({@code -node}) beats grant; {@code *} and {@code a.b.*} are wildcards.
     */
    public synchronized boolean has(String playerName, String node) {
        if (node == null || node.isBlank()) {
            return true; // an unguarded node
        }
        String query = node.toLowerCase(Locale.ROOT);
        Set<String> nodes = effectivePermissions(playerName);
        boolean granted = false;
        for (String n : nodes) {
            boolean deny = n.startsWith("-");
            String pattern = deny ? n.substring(1) : n;
            if (matches(pattern, query)) {
                if (deny) {
                    return false; // an explicit deny short-circuits — deny wins
                }
                granted = true;
            }
        }
        return granted;
    }

    /** The player's effective chat prefix — the first non-blank prefix among their groups (with parents). */
    public synchronized String prefixOf(String playerName) {
        for (PermissionGroup g : effectiveGroups(playerName)) {
            if (!g.prefix().isBlank()) {
                return g.prefix();
            }
        }
        return "";
    }

    /** @return whether {@code pattern} (a literal, {@code *}, or {@code a.b.*}) matches {@code node}. */
    private static boolean matches(String pattern, String node) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            String base = pattern.substring(0, pattern.length() - 2);
            return node.equals(base) || node.startsWith(base + ".");
        }
        return pattern.equals(node);
    }

    /** Union of all nodes across the player's groups and everything they inherit. */
    private Set<String> effectivePermissions(String playerName) {
        Set<String> out = new LinkedHashSet<>();
        for (PermissionGroup g : effectiveGroups(playerName)) {
            out.addAll(g.permissions());
        }
        return out;
    }

    /** The player's groups, plus inherited parents, in a stable order (cycle-safe). */
    private List<PermissionGroup> effectiveGroups(String playerName) {
        List<PermissionGroup> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String groupName : assignedGroupNames(playerName)) {
            expand(groupName, visited, out);
        }
        return out;
    }

    private void expand(String groupName, Set<String> visited, List<PermissionGroup> out) {
        PermissionGroup g = groups.get(groupName.toLowerCase(Locale.ROOT));
        if (g == null || !visited.add(g.name())) {
            return; // unknown group, or already expanded (breaks inheritance cycles)
        }
        out.add(g);
        for (String parent : g.parents()) {
            expand(parent, visited, out);
        }
    }

    /** The group names assigned to a player, or the default group's name if they have none. */
    private List<String> assignedGroupNames(String playerName) {
        Set<String> assigned = userGroups.get(playerName.toLowerCase(Locale.ROOT));
        if (assigned != null && !assigned.isEmpty()) {
            return new ArrayList<>(assigned);
        }
        PermissionGroup def = defaultGroup();
        return def == null ? List.of() : List.of(def.name());
    }

    // ===== group management =====

    public synchronized PermissionGroup getGroup(String name) {
        return groups.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized Collection<PermissionGroup> groups() {
        return new ArrayList<>(groups.values());
    }

    public synchronized PermissionGroup defaultGroup() {
        for (PermissionGroup g : groups.values()) {
            if (g.isDefault()) {
                return g;
            }
        }
        return null;
    }

    /** Create a group (no-op if it exists). @return the group. */
    public synchronized PermissionGroup createGroup(String name) {
        boolean existed = groups.containsKey(name.toLowerCase(Locale.ROOT));
        PermissionGroup g = getOrCreate(name);
        if (!existed) {
            save();
        }
        return g;
    }

    /** Delete a group and drop it from every parent list and user assignment. @return whether it existed. */
    public synchronized boolean deleteGroup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (groups.remove(key) == null) {
            return false;
        }
        for (PermissionGroup g : groups.values()) {
            g.removeParent(key);
        }
        for (Set<String> assigned : userGroups.values()) {
            assigned.remove(key);
        }
        save();
        return true;
    }

    /** Make {@code name} the sole default group (clears the flag on any other). */
    public synchronized boolean setDefaultGroup(String name) {
        PermissionGroup g = groups.get(name.toLowerCase(Locale.ROOT));
        if (g == null) {
            return false;
        }
        for (PermissionGroup other : groups.values()) {
            other.setDefault(other == g);
        }
        save();
        return true;
    }

    public synchronized boolean addGroupPermission(String group, String node) {
        PermissionGroup g = groups.get(group.toLowerCase(Locale.ROOT));
        if (g == null || !g.addPermission(node)) {
            return false;
        }
        save();
        return true;
    }

    public synchronized boolean removeGroupPermission(String group, String node) {
        PermissionGroup g = groups.get(group.toLowerCase(Locale.ROOT));
        if (g == null || !g.removePermission(node)) {
            return false;
        }
        save();
        return true;
    }

    public synchronized boolean addGroupParent(String group, String parent) {
        PermissionGroup g = groups.get(group.toLowerCase(Locale.ROOT));
        if (g == null || groups.get(parent.toLowerCase(Locale.ROOT)) == null || !g.addParent(parent)) {
            return false;
        }
        save();
        return true;
    }

    public synchronized boolean removeGroupParent(String group, String parent) {
        PermissionGroup g = groups.get(group.toLowerCase(Locale.ROOT));
        if (g == null || !g.removeParent(parent)) {
            return false;
        }
        save();
        return true;
    }

    public synchronized void setGroupPrefix(String group, String prefix) {
        PermissionGroup g = groups.get(group.toLowerCase(Locale.ROOT));
        if (g != null) {
            g.setPrefix(prefix);
            save();
        }
    }

    // ===== user assignment =====

    /** Assign a group to a player (must exist). @return whether it was newly added. */
    public synchronized boolean addUserGroup(String playerName, String group) {
        if (groups.get(group.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        boolean added = userGroups.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                .add(group.toLowerCase(Locale.ROOT));
        if (added) {
            save();
        }
        return added;
    }

    /** Remove a group from a player. @return whether they had it. */
    public synchronized boolean removeUserGroup(String playerName, String group) {
        Set<String> assigned = userGroups.get(playerName.toLowerCase(Locale.ROOT));
        if (assigned == null || !assigned.remove(group.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (assigned.isEmpty()) {
            userGroups.remove(playerName.toLowerCase(Locale.ROOT));
        }
        save();
        return true;
    }

    /** The groups explicitly assigned to a player (empty = they fall to the default group). */
    public synchronized Set<String> userGroups(String playerName) {
        Set<String> assigned = userGroups.get(playerName.toLowerCase(Locale.ROOT));
        return assigned == null ? Set.of() : new LinkedHashSet<>(assigned);
    }

    private PermissionGroup getOrCreate(String name) {
        return groups.computeIfAbsent(name.toLowerCase(Locale.ROOT), PermissionGroup::new);
    }

    // ===== persistence =====

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            PermissionGroup group = null; // current 'group' block
            String user = null;            // current 'user' block
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.stripLeading(); // drop indentation but KEEP trailing (a prefix's space)
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sp = line.indexOf(' ');
                String key = (sp < 0 ? line.stripTrailing() : line.substring(0, sp)).toLowerCase(Locale.ROOT);
                String rest = sp < 0 ? "" : line.substring(sp + 1);
                String value = rest.strip(); // trimmed value for everything except prefix
                switch (key) {
                    case "group" -> {
                        group = getOrCreate(value);
                        user = null;
                    }
                    case "user" -> {
                        user = value.toLowerCase(Locale.ROOT);
                        group = null;
                    }
                    case "default" -> {
                        if (group != null) {
                            group.setDefault(true);
                        }
                    }
                    case "prefix" -> {
                        if (group != null) {
                            group.setPrefix(rest.stripLeading()); // keep a trailing space like "[Mod] "
                        }
                    }
                    case "inherit" -> {
                        if (group != null) {
                            group.addParent(value);
                        }
                    }
                    case "permission" -> {
                        if (group != null && !value.isBlank()) {
                            group.addPermission(value);
                        }
                    }
                    case "member" -> { // a 'group' line inside a user block; 'member' avoids clashing with the block keyword
                        if (user != null && !value.isBlank()) {
                            userGroups.computeIfAbsent(user, k -> new LinkedHashSet<>())
                                    .add(value.toLowerCase(Locale.ROOT));
                        }
                    }
                    default -> LOGGER.warn("Ignoring unknown permissions line: " + raw);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read permissions file " + file + ": " + e);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Jedrock permissions — groups and player assignments.\n");
        sb.append("# group <name> { default | prefix <text> | inherit <group> | permission <node> }\n");
        sb.append("#   node: literal, * / a.b.* wildcard, or -node to deny. user <name> { member <group> }\n\n");
        for (PermissionGroup g : groups.values()) {
            sb.append("group ").append(g.name()).append('\n');
            if (g.isDefault()) {
                sb.append("  default\n");
            }
            if (!g.prefix().isBlank()) {
                sb.append("  prefix ").append(g.prefix()).append('\n');
            }
            for (String parent : g.parents()) {
                sb.append("  inherit ").append(parent).append('\n');
            }
            for (String node : g.permissions()) {
                sb.append("  permission ").append(node).append('\n');
            }
            sb.append('\n');
        }
        for (Map.Entry<String, Set<String>> e : userGroups.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            sb.append("user ").append(e.getKey()).append('\n');
            for (String group : e.getValue()) {
                sb.append("  member ").append(group).append('\n');
            }
            sb.append('\n');
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Could not write permissions file " + file + ": " + ex);
        }
    }

    /** Reload groups and assignments from disk, discarding in-memory state (for a {@code /perm reload}). */
    public synchronized void reload() {
        groups.clear();
        userGroups.clear();
        load();
        if (defaultGroup() == null) {
            getOrCreate("default").setDefault(true);
        }
    }
}
