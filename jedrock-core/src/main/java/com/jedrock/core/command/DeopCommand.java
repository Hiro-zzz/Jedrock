package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.Optional;

/**
 * {@code /deop <player>} — revoke operator status. Works on an offline name; the change persists to
 * {@code ops.txt}. Guarded by the same op permission as {@code /op}.
 */
public final class DeopCommand implements Command {

    @Override
    public String name() {
        return "deop";
    }

    @Override
    public String description() {
        return "Revoke a player's operator status";
    }

    @Override
    public String usage() {
        return "/deop <player>";
    }

    @Override
    public String permission() {
        return "jedrock.command.op";
    }

    @Override
    public java.util.List<CommandArg> arguments() {
        // Declared for tab-completion only; execute() below still parses the raw args.
        return java.util.List.of(
                CommandArg.required("player", ArgType.PLAYER));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String target = args[0];
        if (!server.getOpList().remove(target)) {
            sender.sendMessage("{yellow}" + ChatText.escape(target) + " is not an operator.");
            return;
        }
        sender.sendMessage("{green}Removed {white}" + ChatText.escape(target) + "{green} from the operators.");
        Optional<Player> online = server.getPlayer(target);
        online.ifPresent(p -> p.sendMessage("{yellow}You are no longer a server operator."));
    }
}
