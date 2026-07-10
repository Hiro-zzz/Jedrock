package com.jedrock.core.command;

import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;

/** {@code /help} — lists every registered command with its usage and description. */
public final class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List available commands";
    }

    @Override
    public String usage() {
        return "/help";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        sender.sendMessage("{gold}{bold}Jedrock commands:");
        for (Command command : server.getCommandManager().commands()) {
            sender.sendMessage("{yellow}" + command.usage() + " {gray}— " + command.description());
        }
    }
}
