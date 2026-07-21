package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /op [player]} — grant operator status, or (with no argument) list the current operators. An op
 * holds every permission. Works on an offline name too, and the console is always an op, so the very first
 * op is granted from the console on a fresh server. The grant persists to {@code ops.txt}.
 */
public final class OpCommand implements Command {

    @Override
    public String name() {
        return "op";
    }

    @Override
    public String description() {
        return "Grant operator status (or list operators)";
    }

    @Override
    public String usage() {
        return "/op [player]";
    }

    @Override
    public String permission() {
        return "jedrock.command.op";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            List<String> ops = List.copyOf(server.getOpList().names());
            sender.sendMessage(ops.isEmpty()
                    ? "{gray}No operators. {white}/op <player>{gray} to grant."
                    : "{gold}Operators ({white}" + ops.size() + "{gold}): {white}" + String.join(", ", ops));
            return;
        }
        String target = args[0];
        if (!server.getOpList().add(target)) {
            sender.sendMessage("{yellow}" + ChatText.escape(target) + " is already an operator.");
            return;
        }
        sender.sendMessage("{green}Made {white}" + ChatText.escape(target) + "{green} a server operator.");
        // Tell them, if they're online (op resolves by name, so an offline grant still takes effect).
        Optional<Player> online = server.getPlayer(target);
        online.ifPresent(p -> p.sendMessage("{green}You are now a server operator."));
    }
}
