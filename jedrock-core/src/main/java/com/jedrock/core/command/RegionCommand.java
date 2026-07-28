package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.region.Region;
import com.jedrock.api.region.RegionFlag;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.region.CoreRegion;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /region} — create, inspect and rule over named boxes without writing a script.
 *
 * <p>Two corners have to come from somewhere, and this server has no block-selection tool (a wand would
 * need a right-click mode, an item nobody can lose, and per-player state). So a region is defined the two
 * ways that need nothing: <b>{@code pos1} / {@code pos2}</b> mark the block you are standing on, and
 * <b>{@code here <radius>}</b> takes a box around you outright. Both end at a {@code create}, so the
 * selection is never a hidden mode you can forget you are in.
 *
 * <p>The selection lives on the player, not here, so two operators can mark corners at the same time and a
 * disconnect throws the half-made selection away rather than leaving it lying around.
 */
public final class RegionCommand implements Command {

    @Override
    public String name() {
        return "region";
    }

    @Override
    public List<String> aliases() {
        return List.of("rg");
    }

    @Override
    public String description() {
        return "Create and rule named regions";
    }

    @Override
    public String usage() {
        return "/region pos1 | pos2 | create <name> | here <name> <radius> | list | info <name> | "
                + "flag <name> <build|interact|pvp|damage|entry> <allow|deny> | remove <name>";
    }

    @Override
    public String permission() {
        return "jedrock.command.region";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pos1" -> mark(sender, args, true);
            case "pos2" -> mark(sender, args, false);
            case "create" -> create(server, sender, args);
            case "here" -> here(server, sender, args);
            case "list" -> list(server, sender);
            case "info" -> info(server, sender, args);
            case "flag" -> flag(server, sender, args);
            case "remove", "delete" -> remove(server, sender, args);
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }

    // ===== Selection =====

    private void mark(CommandSender sender, String[] args, boolean first) {
        if (!(sender instanceof CorePlayer player)) {
            sender.sendMessage("{red}Only a player can mark a corner — the console isn't standing anywhere.");
            return;
        }
        Location at = player.getLocation();
        int x = (int) Math.floor(at.x());
        int y = (int) Math.floor(at.y());
        int z = (int) Math.floor(at.z());
        player.setRegionCorner(first, x, y, z);
        sender.sendMessage("{green}Corner " + (first ? "1" : "2") + " set to {white}"
                + x + ", " + y + ", " + z);
        int[] other = player.getRegionCorner(!first);
        if (other == null) {
            sender.sendMessage("{gray}Now mark the other corner with {white}/region pos"
                    + (first ? "2" : "1") + "{gray}.");
        } else {
            sender.sendMessage("{gray}Both corners set — name it with {white}/region create <name>{gray}.");
        }
    }

    private void create(JedrockServer server, CommandSender sender, String[] args) {
        if (!(sender instanceof CorePlayer player)) {
            sender.sendMessage("{red}Only a player has a selection; the console can use {white}/region here{red}.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /region create <name>");
            return;
        }
        int[] first = player.getRegionCorner(true);
        int[] second = player.getRegionCorner(false);
        if (first == null || second == null) {
            sender.sendMessage("{red}Mark both corners first: {white}/region pos1{red} and {white}/region pos2{red}.");
            return;
        }
        finish(server, sender, args[1], first[0], first[1], first[2], second[0], second[1], second[2]);
    }

    private void here(JedrockServer server, CommandSender sender, String[] args) {
        if (!(sender instanceof CorePlayer player)) {
            sender.sendMessage("{red}Only a player can use {white}here{red} — it means 'around me'.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("{red}Usage: /region here <name> <radius>");
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("{red}'" + ChatText.escape(args[2]) + "' is not a number.");
            return;
        }
        if (radius < 0 || radius > 512) {
            sender.sendMessage("{red}Radius must be 0..512.");
            return;
        }
        Location at = player.getLocation();
        int x = (int) Math.floor(at.x());
        int y = (int) Math.floor(at.y());
        int z = (int) Math.floor(at.z());
        finish(server, sender, args[1],
                x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius);
    }

    private void finish(JedrockServer server, CommandSender sender, String name,
                        int x1, int y1, int z1, int x2, int y2, int z2) {
        // A region belongs to the world it was drawn in — the sender's, or the default one for a console
        // that isn't standing anywhere.
        com.jedrock.api.world.World world = sender instanceof com.jedrock.api.player.Player p
                ? p.getWorld() : server.getDefaultWorld();
        if (!com.jedrock.core.region.RegionManager.isValidName(name)) {
            sender.sendMessage("{red}'" + ChatText.escape(name) + "' won't do as a name — letters, digits, "
                    + "{white}_{red} and {white}-{red} only, up to 32. "
                    + "{gray}(The name is half of the region's permission node, so a dot or a space in it "
                    + "would make that node ambiguous.)");
            return;
        }
        CoreRegion region = server.getRegions().create(name, world, x1, y1, z1, x2, y2, z2);
        if (region == null) {
            sender.sendMessage("{red}There is already a region called {white}" + ChatText.escape(name)
                    + "{red} — remove it first, or pick another name.");
            return;
        }
        sender.sendMessage("{green}Created {white}" + ChatText.escape(region.getName()) + "{green}: "
                + describeBounds(region) + " {dark_gray}(" + region.getVolume() + " blocks)");
        sender.sendMessage("{gray}It allows everything until you say otherwise — e.g. {white}/region flag "
                + ChatText.escape(region.getName()) + " build deny");
    }

    // ===== Inspection and rules =====

    private void list(JedrockServer server, CommandSender sender) {
        List<Region> all = server.getRegions().all();
        if (all.isEmpty()) {
            sender.sendMessage("{gray}No regions yet. Mark two corners and {white}/region create <name>{gray}.");
            return;
        }
        sender.sendMessage("{gold}Regions ({white}" + all.size() + "{gold}):");
        for (Region region : all) {
            String denied = deniedList(region);
            sender.sendMessage(" {gray}• {white}" + ChatText.escape(region.getName())
                    + " {dark_gray}" + describeBounds(region)
                    + (denied.isEmpty() ? "" : " {red}denies: " + denied));
        }
    }

    private void info(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /region info <name>");
            return;
        }
        Region region = server.getRegions().get(args[1]);
        if (region == null) {
            sender.sendMessage("{red}No region called {white}" + ChatText.escape(args[1]));
            return;
        }
        sender.sendMessage("{gold}" + ChatText.escape(region.getName()));
        sender.sendMessage(" {gray}bounds {white}" + describeBounds(region)
                + " {dark_gray}(" + region.getVolume() + " blocks)");
        boolean anyDenied = false;
        for (RegionFlag flag : RegionFlag.values()) {
            boolean allowed = region.allows(flag);
            anyDenied |= !allowed;
            // A denied flag is the only one whose exemption node is worth reading out — an allowed flag
            // has nothing to be excused from.
            sender.sendMessage(" {gray}" + flag.key() + " "
                    + (allowed ? "{green}allow" : "{red}deny {dark_gray}— bypass: "
                        + region.bypassPermission(flag)));
        }
        if (anyDenied) {
            sender.sendMessage("{gray}Exempt one player with {white}/perm user <name> addnode <node>{gray}, "
                    + "or a whole group with {white}/perm group <name> add <node>{gray}. "
                    + "{dark_gray}(" + region.bypassPermission(RegionFlag.BUILD).replace(".build", ".*")
                    + " covers every flag here; ops are exempt everywhere.)");
        }
    }

    private void flag(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("{red}Usage: /region flag <name> <flag> <allow|deny>");
            return;
        }
        Region region = server.getRegions().get(args[1]);
        if (region == null) {
            sender.sendMessage("{red}No region called {white}" + ChatText.escape(args[1]));
            return;
        }
        RegionFlag flag = RegionFlag.byName(args[2]);
        if (flag == null) {
            sender.sendMessage("{red}No such flag: {white}" + ChatText.escape(args[2])
                    + "{red}. One of: " + flagNames());
            return;
        }
        String verb = args[3].toLowerCase(Locale.ROOT);
        if (verb.equals("allow")) {
            region.allow(flag);
        } else if (verb.equals("deny")) {
            region.deny(flag);
        } else {
            sender.sendMessage("{red}Say {white}allow{red} or {white}deny{red}, not '"
                    + ChatText.escape(args[3]) + "'.");
            return;
        }
        server.getRegions().markDirty();
        sender.sendMessage("{green}" + ChatText.escape(region.getName()) + " now "
                + (verb.equals("deny") ? "{red}denies " : "{green}allows ") + "{white}" + flag.key());
        if (verb.equals("deny")) {
            // Said here rather than only in /region info, because the moment you deny something is the
            // moment you want to know who can still do it.
            sender.sendMessage("{gray}Exempt one player with {white}/perm user <name> addnode "
                    + region.bypassPermission(flag) + "{gray}, or a whole group with "
                    + "{white}/perm group <name> add " + region.bypassPermission(flag));
        }
    }

    private void remove(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /region remove <name>");
            return;
        }
        if (server.getRegions().remove(args[1])) {
            sender.sendMessage("{green}Removed region {white}" + ChatText.escape(args[1]));
        } else {
            sender.sendMessage("{red}No region called {white}" + ChatText.escape(args[1]));
        }
    }

    // ===== Completion =====

    @Override
    public List<String> complete(com.jedrock.api.Server server, CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return ArgType.matching(args.length == 0 ? "" : args[0],
                    List.of("pos1", "pos2", "create", "here", "list", "info", "flag", "remove"));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!(server instanceof JedrockServer js)) {
            return List.of();
        }
        if (args.length == 2 && (sub.equals("info") || sub.equals("flag") || sub.equals("remove")
                || sub.equals("delete"))) {
            List<String> names = new ArrayList<>();
            for (Region region : js.getRegions().all()) {
                names.add(region.getName());
            }
            return ArgType.matching(args[1], names);
        }
        if (args.length == 3 && sub.equals("flag")) {
            return ArgType.matching(args[2], flagKeys());
        }
        if (args.length == 4 && sub.equals("flag")) {
            return ArgType.matching(args[3], List.of("allow", "deny"));
        }
        return List.of();
    }

    private static List<String> flagKeys() {
        List<String> keys = new ArrayList<>();
        for (RegionFlag flag : RegionFlag.values()) {
            keys.add(flag.key());
        }
        return keys;
    }

    private static String flagNames() {
        return String.join(", ", flagKeys());
    }

    private static String deniedList(Region region) {
        List<String> denied = new ArrayList<>();
        for (RegionFlag flag : RegionFlag.values()) {
            if (!region.allows(flag)) {
                denied.add(flag.key());
            }
        }
        return String.join(", ", denied);
    }

    private static String describeBounds(Region region) {
        return region.getMinX() + "," + region.getMinY() + "," + region.getMinZ()
                + " .. " + region.getMaxX() + "," + region.getMaxY() + "," + region.getMaxZ();
    }
}
