package com.jedrock.core.command;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Optional;

/**
 * {@code /tphere <player>} — bring another player to the sender (the inverse of {@code /tp}). The moved
 * player keeps their own facing; the move is server-authoritative (bypasses the blind judge) and relayed
 * to every other avatar.
 */
public final class TpHereCommand implements Command {

    @Override
    public String name() {
        return "tphere";
    }

    @Override
    public String description() {
        return "Teleport a player to you";
    }

    @Override
    public String usage() {
        return "/tphere <player>";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        Optional<Player> found = server.getPlayer(args[0]);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
            return;
        }
        if (target == sender) {
            sender.sendMessage("{red}You can't teleport yourself to yourself.");
            return;
        }
        Location here = sender.getLocation();
        Location targetFacing = target.getLocation();
        server.teleport(target, new Location(target.getWorld(), here.x(), here.y(), here.z(),
                targetFacing.yaw(), targetFacing.pitch()));
        sender.sendMessage("{green}Teleported {white}" + ChatText.escape(target.getName()) + "{green} to you.");
        target.sendMessage("{green}You were teleported to {white}" + ChatText.escape(sender.getName()));
    }
}
