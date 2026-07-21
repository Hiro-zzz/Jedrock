package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.entity.CorePuppet;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * {@code /puppet spawn <type> | move <id> | remove <id> | list} — a <b>temporary</b> test harness for the
 * puppet-entity foundation, so puppets can be spawned and exercised before the platform API exists to drive
 * them. The scripting API will supersede this; keep it minimal.
 */
public final class PuppetCommand implements Command {

    @Override
    public String name() {
        return "puppet";
    }

    @Override
    public String description() {
        return "Spawn / move / remove test puppets (temporary)";
    }

    @Override
    public String usage() {
        return "/puppet spawn <type> [name] | move <id> | look <id> | name <id> <text> | "
                + "flag <id> <on_fire|invisible|sneaking> <on|off> | swing <id> | hurt <id> | remove <id> | list";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public String permission() {
        return "jedrock.command.puppet";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        CorePlayer self = (CorePlayer) sender; // playerOnly() guarantees a player
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "spawn" -> spawn(server, self, args);
            case "move" -> move(server, self, args);
            case "look" -> look(server, self, args);
            case "name" -> name(server, self, args);
            case "flag" -> flag(server, self, args);
            case "swing" -> animate(server, self, args, false);
            case "hurt" -> animate(server, self, args, true);
            case "remove", "kill" -> remove(server, self, args);
            case "list" -> list(server, self);
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }

    private void spawn(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /puppet spawn <type>");
            return;
        }
        EntityType type = EntityType.fromString(args[1]);
        if (type == null) {
            String valid = Arrays.stream(EntityType.values())
                    .map(EntityType::canonicalName).collect(Collectors.joining(", "));
            sender.sendMessage("{red}Unknown type: {white}" + ChatText.escape(args[1])
                    + "{red}. Try: {white}" + valid);
            return;
        }
        // Optional name (the rest of the line) — the shown name of a PLAYER NPC; defaults sensibly.
        String name = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : (type.isPlayer() ? "NPC" : type.canonicalName());
        PuppetEntity puppet = server.spawnPuppet(type, sender.getLocation(), name);
        sender.sendMessage("{green}Spawned {white}" + type.canonicalName()
                + (type.isPlayer() ? " {gray}'" + ChatText.escape(name) + "'" : "")
                + "{green} puppet {white}#" + puppet.getEntityId());
    }

    private void move(JedrockServer server, CorePlayer sender, String[] args) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        Location here = sender.getLocation();
        puppet.teleport(new Location(sender.getWorld(), here.x(), here.y(), here.z(), here.yaw(), here.pitch()));
        sender.sendMessage("{green}Moved puppet {white}#" + puppet.getEntityId() + "{green} to you.");
    }

    /** Turn a puppet to face the sender — the whole "it noticed me" illusion, with no AI behind it. */
    private void look(JedrockServer server, CorePlayer sender, String[] args) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        puppet.lookAt(sender.getLocation());
        sender.sendMessage("{green}Puppet {white}#" + puppet.getEntityId() + "{green} is looking at you.");
    }

    private void name(JedrockServer server, CorePlayer sender, String[] args) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("{red}Usage: /puppet name <id> <text>  {gray}(markup works, e.g. {gold}Trader)");
            return;
        }
        if (puppet.getEntityType().isPlayer()) {
            sender.sendMessage("{red}A player puppet's name is its player name — respawn it to rename.");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        puppet.setNameTag(text);
        sender.sendMessage("{green}Named puppet {white}#" + puppet.getEntityId() + "{green}: " + text);
    }

    private void flag(JedrockServer server, CorePlayer sender, String[] args) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        if (args.length < 4) {
            String valid = Arrays.stream(PuppetFlag.values())
                    .map(f -> f.name().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.joining(", "));
            sender.sendMessage("{red}Usage: /puppet flag <id> <" + valid + "> <on|off>");
            return;
        }
        PuppetFlag flag = null;
        for (PuppetFlag candidate : PuppetFlag.values()) {
            if (candidate.name().equalsIgnoreCase(args[2])) {
                flag = candidate;
            }
        }
        if (flag == null) {
            sender.sendMessage("{red}Unknown flag: {white}" + ChatText.escape(args[2]));
            return;
        }
        boolean on = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
        puppet.setFlag(flag, on);
        sender.sendMessage("{green}Puppet {white}#" + puppet.getEntityId() + "{green} "
                + flag.name().toLowerCase(java.util.Locale.ROOT) + ": {white}" + (on ? "on" : "off"));
    }

    private void animate(JedrockServer server, CorePlayer sender, String[] args, boolean hurt) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        if (hurt) {
            puppet.hurt();
        } else {
            puppet.swing();
        }
        sender.sendMessage("{green}Puppet {white}#" + puppet.getEntityId() + "{green} "
                + (hurt ? "flinched." : "swung."));
    }

    private void remove(JedrockServer server, CorePlayer sender, String[] args) {
        CorePuppet puppet = resolve(server, sender, args);
        if (puppet == null) {
            return;
        }
        puppet.remove();
        sender.sendMessage("{green}Removed puppet {white}#" + puppet.getEntityId());
    }

    private void list(JedrockServer server, CorePlayer sender) {
        var all = server.getPuppets().all();
        if (all.isEmpty()) {
            sender.sendMessage("{gray}No puppets.");
            return;
        }
        sender.sendMessage("{gold}{bold}Puppets ({white}" + all.size() + "{gold}):");
        for (CorePuppet p : all) {
            Location loc = p.getLocation();
            sender.sendMessage("{gray}#{white}" + p.getEntityId() + " {gray}" + p.getEntityType().canonicalName()
                    + " {dark_gray}" + String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f",
                    loc.x(), loc.y(), loc.z()));
        }
    }

    /** Parse {@code args[1]} as a puppet id and look it up, messaging the sender on any failure. */
    private CorePuppet resolve(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /puppet " + args[0] + " <id>  {gray}(see /puppet list)");
            return null;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("{red}Id must be a number: {white}" + ChatText.escape(args[1]));
            return null;
        }
        CorePuppet puppet = server.getPuppets().get(id);
        if (puppet == null) {
            sender.sendMessage("{red}No puppet {white}#" + id + "{red} (see {white}/puppet list{red}).");
        }
        return puppet;
    }
}
