package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;

import java.util.List;

/**
 * {@code /tps} (alias {@code /status}) — show the server's live health line (TPS, MSPT + peak, players,
 * memory, uptime) to the sender, the same summary the console {@code status} command prints.
 */
public final class TpsCommand implements Command {

    @Override
    public String name() {
        return "tps";
    }

    @Override
    public List<String> aliases() {
        return List.of("status");
    }

    @Override
    public String description() {
        return "Show server performance (TPS, MSPT, memory)";
    }

    @Override
    public String usage() {
        return "/tps";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        sender.sendMessage("{gray}" + server.getStatus().summary());
    }
}
