package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /me <action>} — broadcast a third-person emote, e.g. {@code * Steve waves}. The sender's name is
 * escaped (an untrusted name can't inject markup); the action body is left raw so chat markup renders.
 */
public final class MeCommand implements Command {

    @Override
    public String name() {
        return "me";
    }

    @Override
    public String description() {
        return "Broadcast a third-person emote";
    }

    @Override
    public String usage() {
        return "/me <action>";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String action = String.join(" ", args);
        server.broadcast("{gray}* {white}" + ChatText.escape(sender.getName()) + " {gray}" + action);
    }
}
