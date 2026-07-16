package com.jedrock.core.command;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
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
        return "/puppet spawn <type> [name] | /puppet move <id> | /puppet remove <id> | /puppet list";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "spawn" -> spawn(server, sender, args);
            case "move" -> move(server, sender, args);
            case "remove", "kill" -> remove(server, sender, args);
            case "list" -> list(server, sender);
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
