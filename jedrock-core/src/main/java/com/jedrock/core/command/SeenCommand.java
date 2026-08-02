package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /seen <player>} — when somebody was last here.
 *
 * <p>The question asked immediately before every other moderation decision: is this an account that left
 * five minutes ago or one nobody has seen since spring. Recorded on the way out, so a player who is online
 * right now answers "now" from the roster rather than from the file.
 */
public final class SeenCommand implements Command {

    @Override
    public String name() {
        return "seen";
    }

    @Override
    public String description() {
        return "When a player was last online";
    }

    @Override
    public String usage() {
        return "/seen <player>";
    }

    @Override
    public String permission() {
        return "jedrock.command.seen";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.required("player", ArgType.WORD));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String name = args[0];
        if (server.getPlayer(name).isPresent()) {
            sender.sendMessage("{green}" + ChatText.escape(name) + "{gray} is online now.");
            return;
        }
        long when = server.getModeration().getLastSeen().lastSeen(name);
        if (when <= 0) {
            sender.sendMessage("{gray}This server has never seen {white}" + ChatText.escape(name)
                    + "{gray} leave.");
            return;
        }
        sender.sendMessage("{white}" + ChatText.escape(name) + "{gray} was last here {white}"
                + Durations.describe(System.currentTimeMillis() - when) + "{gray} ago.");
    }
}
