package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;

import java.util.List;

/**
 * {@code /say <message>} — broadcast a highlighted server message to every online player, cross-edition.
 * The message body is left raw so the unified {@code {color}} / Markdown markup renders (the same
 * feature as normal chat); joins the words back with spaces since args arrive pre-split.
 */
public final class SayCommand implements Command {

    @Override
    public String name() {
        return "say";
    }

    @Override
    public String description() {
        return "Broadcast a server message to everyone";
    }

    @Override
    public String usage() {
        return "/say <message>";
    }

    @Override
    public String permission() {
        return "jedrock.command.say";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String message = String.join(" ", args);
        server.broadcast("{light_purple}[Server] {reset}" + message);
    }
}
