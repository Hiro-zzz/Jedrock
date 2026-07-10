package com.jedrock.core.command;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /tp <player>} or {@code /tp <x> <y> <z>} — move the sender to another player, or to absolute
 * coordinates. Keeps the sender's own facing. The move is server-authoritative (it bypasses the blind
 * judge's move-delta check, since the server initiated it) and is relayed to every other player's avatar.
 */
public final class TeleportCommand implements Command {

    @Override
    public String name() {
        return "tp";
    }

    @Override
    public List<String> aliases() {
        return List.of("teleport");
    }

    @Override
    public String description() {
        return "Teleport to a player or to coordinates";
    }

    @Override
    public String usage() {
        return "/tp <player> | /tp <x> <y> <z>";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        Location here = sender.getLocation();
        if (args.length == 1) {
            Optional<Player> found = server.getPlayer(args[0]);
            if (found.isEmpty()) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
                return;
            }
            Location dest = found.get().getLocation();
            server.teleport(sender, new Location(sender.getWorld(), dest.x(), dest.y(), dest.z(),
                    here.yaw(), here.pitch()));
            sender.sendMessage("{green}Teleported to {white}" + ChatText.escape(found.get().getName()));
        } else if (args.length == 3) {
            double x, y, z;
            try {
                x = Double.parseDouble(args[0]);
                y = Double.parseDouble(args[1]);
                z = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("{red}Coordinates must be numbers: " + usage());
                return;
            }
            server.teleport(sender, new Location(sender.getWorld(), x, y, z, here.yaw(), here.pitch()));
            sender.sendMessage("{green}Teleported to {white}" + fmt(x) + " " + fmt(y) + " " + fmt(z));
        } else {
            sender.sendMessage("{red}Usage: " + usage());
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
