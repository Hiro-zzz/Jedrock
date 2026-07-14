package com.jedrock.core.command;

import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

/**
 * {@code /help [command]} — lists every registered command, or, given a name/alias, shows one command's
 * usage, description and aliases in detail.
 */
public final class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List commands, or detail one";
    }

    @Override
    public String usage() {
        return "/help [command]";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length >= 1) {
            detail(server, sender, args[0]);
            return;
        }
        sender.sendMessage("{gold}{bold}Jedrock commands:");
        for (Command command : server.getCommandManager().commands()) {
            sender.sendMessage("{yellow}" + command.usage() + " {gray}— " + command.description());
        }
    }

    /** Show one command's usage, description and aliases, or an error if the label is unknown. */
    private void detail(JedrockServer server, CorePlayer sender, String label) {
        Command command = server.getCommandManager().get(label);
        if (command == null) {
            sender.sendMessage("{red}Unknown command: {white}/" + ChatText.escape(label));
            return;
        }
        sender.sendMessage("{gold}{bold}/" + command.name() + "{reset} {gray}— " + command.description());
        sender.sendMessage("{yellow}Usage: {white}" + command.usage());
        if (!command.aliases().isEmpty()) {
            sender.sendMessage("{yellow}Aliases: {white}" + String.join(", ", command.aliases()));
        }
    }
}
