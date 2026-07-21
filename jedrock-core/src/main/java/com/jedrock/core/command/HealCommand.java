package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Optional;

/**
 * {@code /heal [player]} — restore a player to full health. With no argument it targets the sender. A
 * no-op in creative (no health to restore) — the server reports that back.
 */
public final class HealCommand implements Command {

    @Override
    public String name() {
        return "heal";
    }

    @Override
    public String description() {
        return "Restore full health (survival)";
    }

    @Override
    public String usage() {
        return "/heal [player]";
    }

    @Override
    public String permission() {
        return "jedrock.command.heal";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        CorePlayer target;
        if (args.length >= 1) {
            Optional<Player> found = server.getPlayer(args[0]);
            if (found.isEmpty() || !(found.get() instanceof CorePlayer cp)) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
                return;
            }
            target = cp;
        } else if (sender instanceof CorePlayer self) {
            target = self;
        } else {
            sender.sendMessage("{red}From the console, name a player: {white}" + usage());
            return;
        }
        if (!server.heal(target)) {
            sender.sendMessage("{red}" + (target == sender ? "You have" : ChatText.escape(target.getName())
                    + " has") + " no health to restore in creative.");
            return;
        }
        target.sendMessage("{green}You were healed.");
        if (target != sender) {
            sender.sendMessage("{green}Healed {white}" + ChatText.escape(target.getName()));
        }
    }
}
