package com.jedrock.core.command;

import com.jedrock.api.entity.Hologram;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.entity.CoreHologram;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hologram spawn <text> | setline <id> <n> <text> | remove <id> | list} — a <b>temporary</b> test
 * harness for holograms, the sibling of {@link PuppetCommand}. Both exist only until the platform API can
 * drive this from a script; keep them minimal.
 */
public final class HologramCommand implements Command {

    /** Lines are typed on one line and split on this — a chat box can't carry a newline. */
    private static final String LINE_SEPARATOR = "\\|";

    @Override
    public String name() {
        return "hologram";
    }

    @Override
    public List<String> aliases() {
        return List.of("holo");
    }

    @Override
    public String description() {
        return "Spawn / edit / remove floating text (temporary)";
    }

    @Override
    public String usage() {
        return "/hologram spawn <line | line | …> | setline <id> <n> <text> | remove <id> | list";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(server, sender, args);
            case "setline" -> setLine(server, sender, args);
            case "remove", "kill" -> remove(server, sender, args);
            case "list" -> list(server, sender);
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }

    private void spawn(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /hologram spawn <line | line | …>  "
                    + "{gray}(markup works, e.g. {gold}{bold}Welcome)");
            return;
        }
        String[] lines = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).split(LINE_SEPARATOR);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].trim();
        }
        // Spawn it at eye level, where the sender is actually looking, rather than at their feet.
        Location here = sender.getLocation();
        Location at = new Location(sender.getWorld(), here.x(), here.y() + 1.6, here.z(), 0f, 0f);
        Hologram hologram = server.spawnHologram(at, lines);
        sender.sendMessage("{green}Spawned hologram {white}#" + hologram.getEntityId()
                + "{green} (" + lines.length + " line" + (lines.length == 1 ? "" : "s") + ").");
    }

    private void setLine(JedrockServer server, CorePlayer sender, String[] args) {
        CoreHologram hologram = resolve(server, sender, args);
        if (hologram == null) {
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("{red}Usage: /hologram setline <id> <n> <text>  {gray}(n starts at 0)");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("{red}Line must be a number: {white}" + ChatText.escape(args[2]));
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        try {
            hologram.setLine(index, text);
        } catch (IndexOutOfBoundsException e) {
            sender.sendMessage("{red}" + e.getMessage());
            return;
        }
        sender.sendMessage("{green}Set line {white}" + index + "{green}: " + text);
    }

    private void remove(JedrockServer server, CorePlayer sender, String[] args) {
        CoreHologram hologram = resolve(server, sender, args);
        if (hologram == null) {
            return;
        }
        hologram.remove();
        sender.sendMessage("{green}Removed hologram {white}#" + hologram.getEntityId());
    }

    private void list(JedrockServer server, CorePlayer sender) {
        List<CoreHologram> all = server.getHolograms();
        if (all.isEmpty()) {
            sender.sendMessage("{gray}No holograms.");
            return;
        }
        sender.sendMessage("{gold}{bold}Holograms ({white}" + all.size() + "{gold}):");
        for (CoreHologram h : all) {
            Location loc = h.getLocation();
            sender.sendMessage("{gray}#{white}" + h.getEntityId() + " {gray}" + h.getLines().size() + " lines"
                    + " {dark_gray}" + String.format(Locale.ROOT, "%.0f,%.0f,%.0f", loc.x(), loc.y(), loc.z()));
        }
    }

    /** Parse {@code args[1]} as a hologram id and look it up, messaging the sender on any failure. */
    private CoreHologram resolve(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: /hologram " + args[0] + " <id>  {gray}(see /hologram list)");
            return null;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("{red}Id must be a number: {white}" + ChatText.escape(args[1]));
            return null;
        }
        for (CoreHologram h : server.getHolograms()) {
            if (h.getEntityId() == id) {
                return h;
            }
        }
        sender.sendMessage("{red}No hologram {white}#" + id + "{red} (see {white}/hologram list{red}).");
        return null;
    }
}
