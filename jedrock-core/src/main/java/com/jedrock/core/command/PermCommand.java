package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.permission.PermissionGroup;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.utils.text.ChatText;

import java.util.Locale;
import java.util.Set;

/**
 * {@code /perm …} — manage the native group permission system: create/delete groups, grant/deny nodes (with
 * {@code *} / {@code a.b.*} wildcards and {@code -node} deny), set inheritance, a chat prefix and the default
 * group, and assign players to groups. Every change persists to {@code permissions.txt}. Guarded by
 * {@code jedrock.command.perm} (so ops and the console manage it).
 */
public final class PermCommand implements Command {

    @Override
    public String name() {
        return "perm";
    }

    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of("perms", "permission");
    }

    @Override
    public String description() {
        return "Manage permission groups";
    }

    @Override
    public String usage() {
        return "/perm groups | group <g> <info|default|prefix|add|remove|inherit|uninherit> [value] | "
                + "creategroup <g> | delgroup <g> | user <player> <info|add|remove> [group] | reload";
    }

    @Override
    public String permission() {
        return "jedrock.command.perm";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        PermissionManager perms = server.getPermissions();
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "groups", "list" -> listGroups(perms, sender);
            case "creategroup" -> createGroup(perms, sender, args);
            case "delgroup", "deletegroup" -> deleteGroup(perms, sender, args);
            case "group" -> group(perms, sender, args);
            case "user", "player" -> user(perms, sender, args);
            case "reload" -> {
                perms.reload();
                sender.sendMessage("{green}Reloaded permissions from disk.");
            }
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }

    private void listGroups(PermissionManager perms, CommandSender sender) {
        var groups = perms.groups();
        sender.sendMessage("{gold}{bold}Groups ({white}" + groups.size() + "{gold}):");
        for (PermissionGroup g : groups) {
            sender.sendMessage("{gray}• {white}" + g.name()
                    + (g.isDefault() ? " {green}(default)" : "")
                    + " {dark_gray}" + g.permissions().size() + " node(s)"
                    + (g.parents().isEmpty() ? "" : ", inherits " + String.join(", ", g.parents())));
        }
    }

    private void createGroup(PermissionManager perms, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /perm creategroup <name>");
            return;
        }
        if (perms.getGroup(args[1]) != null) {
            sender.sendMessage("{yellow}Group already exists: {white}" + ChatText.escape(args[1]));
            return;
        }
        perms.createGroup(args[1]);
        sender.sendMessage("{green}Created group {white}" + ChatText.escape(args[1].toLowerCase(Locale.ROOT)));
    }

    private void deleteGroup(PermissionManager perms, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /perm delgroup <name>");
            return;
        }
        sender.sendMessage(perms.deleteGroup(args[1])
                ? "{green}Deleted group {white}" + ChatText.escape(args[1].toLowerCase(Locale.ROOT))
                : "{red}No such group: {white}" + ChatText.escape(args[1]));
    }

    private void group(PermissionManager perms, CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("{red}Usage: /perm group <g> <info|default|prefix|add|remove|inherit|uninherit> [value]");
            return;
        }
        String name = args[1];
        PermissionGroup g = perms.getGroup(name);
        if (g == null) {
            sender.sendMessage("{red}No such group: {white}" + ChatText.escape(name)
                    + "{red} (see {white}/perm groups{red}).");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        String value = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
        switch (action) {
            case "info" -> groupInfo(sender, g);
            case "default" -> {
                perms.setDefaultGroup(g.name());
                sender.sendMessage("{green}{white}" + g.name() + "{green} is now the default group.");
            }
            case "prefix" -> {
                String prefix = (value.isBlank() || value.equalsIgnoreCase("clear")) ? "" : value;
                perms.setGroupPrefix(g.name(), prefix);
                sender.sendMessage(prefix.isEmpty()
                        ? "{green}Cleared {white}" + g.name() + "{green}'s prefix."
                        : "{green}Set {white}" + g.name() + "{green}'s prefix: {reset}" + prefix);
            }
            case "add" -> {
                if (value.isBlank()) {
                    sender.sendMessage("{red}Usage: /perm group " + g.name() + " add <node>  {gray}(-node denies)");
                    return;
                }
                sender.sendMessage(perms.addGroupPermission(g.name(), value)
                        ? "{green}{white}" + g.name() + "{green} + {white}" + ChatText.escape(value)
                        : "{yellow}{white}" + g.name() + "{yellow} already has {white}" + ChatText.escape(value));
            }
            case "remove" -> {
                sender.sendMessage(perms.removeGroupPermission(g.name(), value)
                        ? "{green}{white}" + g.name() + "{green} − {white}" + ChatText.escape(value)
                        : "{yellow}{white}" + g.name() + "{yellow} didn't have {white}" + ChatText.escape(value));
            }
            case "inherit" -> {
                sender.sendMessage(perms.addGroupParent(g.name(), value)
                        ? "{green}{white}" + g.name() + "{green} now inherits {white}" + ChatText.escape(value)
                        : "{red}Couldn't inherit {white}" + ChatText.escape(value)
                                + "{red} (unknown group or already inherited).");
            }
            case "uninherit" -> {
                sender.sendMessage(perms.removeGroupParent(g.name(), value)
                        ? "{green}{white}" + g.name() + "{green} no longer inherits {white}" + ChatText.escape(value)
                        : "{yellow}{white}" + g.name() + "{yellow} didn't inherit {white}" + ChatText.escape(value));
            }
            default -> sender.sendMessage("{red}Unknown action: {white}" + ChatText.escape(action));
        }
    }

    private void groupInfo(CommandSender sender, PermissionGroup g) {
        sender.sendMessage("{gold}{bold}Group {white}" + g.name() + (g.isDefault() ? " {green}(default)" : ""));
        if (!g.prefix().isBlank()) {
            sender.sendMessage("{yellow}Prefix: {reset}" + g.prefix());
        }
        if (!g.parents().isEmpty()) {
            sender.sendMessage("{yellow}Inherits: {white}" + String.join(", ", g.parents()));
        }
        sender.sendMessage("{yellow}Nodes ({white}" + g.permissions().size() + "{yellow}): {white}"
                + (g.permissions().isEmpty() ? "{gray}none" : String.join(", ", g.permissions())));
    }

    private void user(PermissionManager perms, CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("{red}Usage: /perm user <player> <info|add|remove> [group]");
            return;
        }
        String player = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> {
                Set<String> assigned = perms.userGroups(player);
                sender.sendMessage("{gold}{bold}" + ChatText.escape(player) + "{gold}: {white}"
                        + (assigned.isEmpty() ? "{gray}(default group)" : String.join(", ", assigned)));
            }
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage("{red}Usage: /perm user " + ChatText.escape(player) + " add <group>");
                    return;
                }
                sender.sendMessage(perms.addUserGroup(player, args[3])
                        ? "{green}{white}" + ChatText.escape(player) + "{green} + group {white}"
                                + args[3].toLowerCase(Locale.ROOT)
                        : "{red}Couldn't add {white}" + ChatText.escape(args[3])
                                + "{red} (unknown group or already assigned).");
            }
            case "remove" -> {
                if (args.length < 4) {
                    sender.sendMessage("{red}Usage: /perm user " + ChatText.escape(player) + " remove <group>");
                    return;
                }
                sender.sendMessage(perms.removeUserGroup(player, args[3])
                        ? "{green}{white}" + ChatText.escape(player) + "{green} − group {white}"
                                + args[3].toLowerCase(Locale.ROOT)
                        : "{yellow}{white}" + ChatText.escape(player) + "{yellow} wasn't in {white}"
                                + ChatText.escape(args[3]));
            }
            default -> sender.sendMessage("{red}Unknown action: {white}" + ChatText.escape(action));
        }
    }
}
