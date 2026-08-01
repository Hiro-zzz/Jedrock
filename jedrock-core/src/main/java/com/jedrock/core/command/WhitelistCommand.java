package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Whitelist;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Locale;

/**
 * {@code /whitelist on|off|add|remove|list} — the other side of the login gate: not "keep this person
 * out" but "let only these people in".
 *
 * <p>Turning it on with an empty list is the mistake worth guarding, so this says how many names it has
 * before it does. Operators are exempt, which is what stops an administrator locking themselves out of
 * their own server one keystroke after enabling it.
 */
public final class WhitelistCommand implements Command {

    @Override
    public String name() {
        return "whitelist";
    }

    @Override
    public String description() {
        return "Allow only listed players to connect";
    }

    @Override
    public String usage() {
        return "/whitelist on|off|add <player>|remove <player>|list";
    }

    @Override
    public String permission() {
        return "jedrock.command.whitelist";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.optional("action", ArgType.choice("on", "off", "add", "remove", "list")),
                CommandArg.optional("player", ArgType.PLAYER));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        Whitelist whitelist = server.getModeration().getWhitelist();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "on" -> {
                whitelist.setEnabled(true);
                sender.sendMessage("{green}Whitelist on{gray} — " + whitelist.size()
                        + " name(s) may connect, plus operators.");
                if (whitelist.size() == 0) {
                    sender.sendMessage("{yellow}The list is empty: only operators can get in right now.");
                }
                // Anyone already here who isn't on it stays — the gate is a gate, not a sweep. Say so,
                // because "I enabled it and they're still here" otherwise reads as the feature failing.
                long staying = server.getPlayers().stream()
                        .filter(p -> !whitelist.contains(p.getName()) && !server.getOpList().isOp(p.getName()))
                        .count();
                if (staying > 0) {
                    sender.sendMessage("{gray}" + staying + " player(s) online are not on it; they stay "
                            + "until they leave. {white}/kick{gray} if that isn't what you meant.");
                }
            }
            case "off" -> {
                whitelist.setEnabled(false);
                sender.sendMessage("{green}Whitelist off{gray} — anyone not banned may connect.");
            }
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage("{red}Usage: /whitelist add <player>");
                    return;
                }
                sender.sendMessage(whitelist.add(args[1])
                        ? "{green}Added {white}" + ChatText.escape(args[1]) + "{green} to the whitelist."
                        : "{yellow}" + ChatText.escape(args[1]) + " is already on it.");
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage("{red}Usage: /whitelist remove <player>");
                    return;
                }
                if (!whitelist.remove(args[1])) {
                    sender.sendMessage("{yellow}" + ChatText.escape(args[1]) + " was not on it.");
                    return;
                }
                sender.sendMessage("{green}Removed {white}" + ChatText.escape(args[1])
                        + "{green} from the whitelist.");
                // Removing somebody who is standing here does nothing on its own, for the same reason
                // enabling it doesn't. Offer the obvious follow-up rather than doing it unasked.
                if (whitelist.isEnabled() && server.getPlayer(args[1]).isPresent()) {
                    sender.sendMessage("{gray}They are online and stay until they leave.");
                }
            }
            case "list" -> {
                List<String> names = whitelist.names();
                sender.sendMessage("{gold}Whitelist ({white}" + (whitelist.isEnabled() ? "on" : "off")
                        + "{gold}, {white}" + names.size() + "{gold} name(s)){gray}"
                        + (names.isEmpty() ? "" : ": {white}" + String.join(", ", names)));
            }
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }
}
