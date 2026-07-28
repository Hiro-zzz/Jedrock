package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.api.world.WorldTemplate;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /world} — the operator's half of multiple worlds: see them, walk between them, make one, put
 * one away.
 *
 * <p>Deliberately not a wrapper around a config file. Creating a world here does the same thing the api
 * does — takes a template, bakes it, and has it live before the command returns — so the thing an
 * operator can do and the thing a script can do are the same thing.
 */
public final class WorldCommand implements Command {

    @Override
    public String name() {
        return "world";
    }

    @Override
    public List<String> aliases() {
        return List.of("worlds");
    }

    @Override
    public String description() {
        return "List, enter, create and unload worlds";
    }

    @Override
    public String usage() {
        return "/world [list|info|tp <name>|spawn|create <name> <template> [seed]|unload <name>|templates]";
    }

    @Override
    public String permission() {
        return "jedrock.command.world";
    }

    @Override
    public List<String> complete(com.jedrock.api.Server server, CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            for (String sub : new String[]{"list", "info", "tp", "spawn", "create", "unload", "templates"}) {
                out.add(sub);
            }
            return out;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if (args.length == 2 && (sub.equals("tp") || sub.equals("unload") || sub.equals("info"))) {
            for (World w : server.getWorlds()) {
                out.add(w.getName());
            }
        } else if (args.length == 3 && sub.equals("create")) {
            for (WorldTemplate t : server.getWorldTemplates()) {
                out.add(t.name());
            }
        }
        return out;
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "list" -> list(server, sender);
            case "templates" -> templates(server, sender);
            case "info" -> info(server, sender, args);
            case "tp", "go", "enter" -> teleport(server, sender, args);
            case "spawn" -> spawn(server, sender);
            case "create" -> create(server, sender, args);
            case "unload" -> unload(server, sender, args);
            default -> sender.sendMessage("{red}Unknown subcommand. {gray}" + usage());
        }
    }

    private void list(JedrockServer server, CommandSender sender) {
        World here = sender instanceof Player p ? p.getWorld() : server.getDefaultWorld();
        sender.sendMessage("{gold}Worlds {gray}(" + server.getWorlds().size() + "):");
        for (World world : server.getWorlds()) {
            boolean current = world == here;
            sender.sendMessage((current ? "{white}» " : "{gray}  ") + world.getName()
                    + " {dark_gray}" + world.getDimension().name().toLowerCase(java.util.Locale.ROOT)
                    + ", " + world.getPlayers().size() + " player(s)"
                    + (world == server.getDefaultWorld() ? ", default" : ""));
        }
    }

    private void templates(JedrockServer server, CommandSender sender) {
        sender.sendMessage("{gold}World templates:");
        for (WorldTemplate t : server.getWorldTemplates()) {
            sender.sendMessage("{gray}  " + t.name() + " {dark_gray}— "
                    + t.dimension().name().toLowerCase(java.util.Locale.ROOT)
                    + ", " + t.sizeChunks() + "x" + t.sizeChunks() + " chunks"
                    + (t.decorate() ? ", decorated" : ", bare")
                    + (t.seed() == null ? ", random seed" : ", seed " + t.seed()));
        }
    }

    private void info(JedrockServer server, CommandSender sender, String[] args) {
        World world = args.length >= 2 ? server.getWorld(args[1]).orElse(null)
                : sender instanceof Player p ? p.getWorld() : server.getDefaultWorld();
        if (world == null) {
            sender.sendMessage("{red}No world called {white}" + ChatText.escape(args[1]) + "{red}.");
            return;
        }
        sender.sendMessage("{gold}" + world.getName() + " {gray}("
                + world.getDimension().name().toLowerCase(java.util.Locale.ROOT) + ")");
        Location spawn = world.getSpawnLocation();
        sender.sendMessage("{gray}  spawn {white}" + (int) spawn.x() + ", " + (int) spawn.y()
                + ", " + (int) spawn.z());
        sender.sendMessage("{gray}  players {white}" + world.getPlayers().size()
                + " {gray}weather {white}" + world.getWeather().name().toLowerCase(java.util.Locale.ROOT));
        if (world instanceof CoreWorld cw) {
            sender.sendMessage("{gray}  seed {white}" + cw.getSeed()
                    + " {gray}size {white}" + cw.boundsChunks() + "x" + cw.boundsChunks() + " chunks"
                    + " {gray}height {white}" + (cw.maxY() + 1));
            sender.sendMessage("{gray}  sections {white}" + cw.loadedSections()
                    + " {gray}(" + cw.compressedSections() + " compressed)");
        }
    }

    private void teleport(JedrockServer server, CommandSender sender, String[] args) {
        if (!(sender instanceof CorePlayer player)) {
            sender.sendMessage("{red}Only a player can walk into a world.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /world tp <name>");
            return;
        }
        World target = server.getWorld(args[1]).orElse(null);
        if (target == null) {
            sender.sendMessage("{red}No world called {white}" + ChatText.escape(args[1]) + "{red}. "
                    + "{gray}Try /world list.");
            return;
        }
        if (target == player.getWorld()) {
            sender.sendMessage("{gray}You are already in {white}" + target.getName() + "{gray}.");
            return;
        }
        if (!server.teleport(player, target.getSpawnLocation())) {
            sender.sendMessage("{red}Something refused that journey.");
            return;
        }
        player.sendMessage("{green}Welcome to {white}" + target.getName() + "{green}.");
    }

    private void spawn(JedrockServer server, CommandSender sender) {
        if (!(sender instanceof CorePlayer player)) {
            sender.sendMessage("{red}Only a player has a spawn to go to.");
            return;
        }
        server.teleport(player, player.getWorld().getSpawnLocation());
        player.sendMessage("{green}Teleported to this world's spawn.");
    }

    private void create(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("{red}Usage: /world create <name> <template> [seed] "
                    + "{gray}— see /world templates");
            return;
        }
        Long seed = null;
        if (args.length >= 4) {
            try {
                seed = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                seed = (long) args[3].hashCode(); // the same rule Minecraft uses for a worded seed
            }
        }
        // The bake blocks this thread — a few seconds at the default size — so say so before it starts,
        // or an operator watches a dead console and runs it again.
        sender.sendMessage("{gray}Baking {white}" + ChatText.escape(args[1]) + "{gray}… "
                + "this takes a moment and the server will not tick meanwhile.");
        try {
            World created = server.createWorld(args[1], args[2], seed);
            sender.sendMessage("{green}Created {white}" + created.getName() + "{green} ("
                    + created.getDimension().name().toLowerCase(java.util.Locale.ROOT)
                    + "). {gray}Walk in with {white}/world tp " + created.getName());
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage("{red}" + ChatText.escape(String.valueOf(e.getMessage())));
        }
    }

    private void unload(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /world unload <name>");
            return;
        }
        World world = server.getWorld(args[1]).orElse(null);
        if (world == null) {
            sender.sendMessage("{red}No world called {white}" + ChatText.escape(args[1]) + "{red}.");
            return;
        }
        if (server.unloadWorld(args[1])) {
            sender.sendMessage("{green}Saved and unloaded {white}" + world.getName() + "{green}. "
                    + "{gray}Its folder is untouched, so it comes back at the next boot.");
        } else if (world == server.getDefaultWorld()) {
            sender.sendMessage("{red}The default world can't be unloaded.");
        } else {
            sender.sendMessage("{red}Someone is still standing in {white}" + world.getName()
                    + "{red} — move them out first.");
        }
    }
}
