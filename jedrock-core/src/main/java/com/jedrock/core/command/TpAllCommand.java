package com.jedrock.core.command;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

/**
 * {@code /tpall} — teleport every other online player to the sender. Each moved player keeps their own
 * facing; the moves are server-authoritative (bypass the blind judge) and relayed to every avatar.
 */
public final class TpAllCommand implements Command {

    @Override
    public String name() {
        return "tpall";
    }

    @Override
    public String description() {
        return "Teleport all players to you";
    }

    @Override
    public String usage() {
        return "/tpall";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        Location here = sender.getLocation();
        int moved = 0;
        for (Player p : server.getPlayers()) {
            if (p == sender || !(p instanceof CorePlayer target)) {
                continue;
            }
            Location facing = target.getLocation();
            server.teleport(target, new Location(target.getWorld(), here.x(), here.y(), here.z(),
                    facing.yaw(), facing.pitch()));
            target.sendMessage("{green}You were teleported to {white}" + ChatText.escape(sender.getName()));
            moved++;
        }
        sender.sendMessage("{green}Teleported {white}" + moved + "{green} player(s) to you.");
    }
}
