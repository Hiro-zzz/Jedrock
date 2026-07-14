package com.jedrock.core.command;

import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Optional;

/**
 * {@code /kill [player]} — kill the sender, or a named player, through the normal damage path (a silent
 * respawn at spawn with a death message). A no-op in creative, which takes no damage — the server reports
 * that back. With no argument it targets the sender.
 */
public final class KillCommand implements Command {

    @Override
    public String name() {
        return "kill";
    }

    @Override
    public String description() {
        return "Kill yourself or another player (survival)";
    }

    @Override
    public String usage() {
        return "/kill [player]";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        CorePlayer target = sender;
        if (args.length >= 1) {
            Optional<Player> found = server.getPlayer(args[0]);
            if (found.isEmpty() || !(found.get() instanceof CorePlayer cp)) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
                return;
            }
            target = cp;
        }
        if (!server.kill(target)) {
            sender.sendMessage("{red}" + (target == sender ? "You take" : ChatText.escape(target.getName())
                    + " takes") + " no damage in creative.");
            return;
        }
        if (target != sender) {
            sender.sendMessage("{green}Killed {white}" + ChatText.escape(target.getName()));
        }
    }
}
