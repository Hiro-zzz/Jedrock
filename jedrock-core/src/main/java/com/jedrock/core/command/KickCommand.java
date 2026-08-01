package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /kick <player> [reason]} — disconnect somebody who is here, without recording anything.
 *
 * <p>The console has been able to do this since it existed; in the game it could not, which meant an
 * operator standing next to the problem had to go and find a terminal. Nothing is persisted: a kick is
 * "leave and think about it", and if it should outlast the reconnect it wanted to be a ban.
 */
public final class KickCommand implements Command {

    @Override
    public String name() {
        return "kick";
    }

    @Override
    public String description() {
        return "Disconnect a player";
    }

    @Override
    public String usage() {
        return "/kick <player> [reason]";
    }

    @Override
    public String permission() {
        return "jedrock.command.kick";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.required("player", ArgType.PLAYER),
                CommandArg.optional("reason", ArgType.GREEDY));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        Optional<? extends Player> target = server.getPlayer(args[0]);
        if (target.isEmpty()) {
            sender.sendMessage("{red}No player called '" + ChatText.escape(args[0]) + "' is online.");
            return;
        }
        String reason = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "Kicked by an operator";
        Player player = target.get();
        player.kick("{red}" + ChatText.escape(reason));
        sender.sendMessage("{green}Kicked {white}" + ChatText.escape(player.getName())
                + "{gray} — " + ChatText.escape(reason));
    }
}
